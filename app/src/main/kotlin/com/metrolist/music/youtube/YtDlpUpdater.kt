/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.youtube

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.chaquo.python.Python
import com.metrolist.music.constants.YtDlpManualUpdateTimestampsKey
import com.metrolist.music.utils.dataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * yt-dlp is bundled at build time via Chaquopy's `pip { install("yt-dlp") }`,
 * but that version goes stale the moment YouTube changes something — which,
 * per yt-dlp's own release cadence, is often. Since we can't ship a Play
 * Store update every time that happens, this checks PyPI for a newer release.
 *
 * IMPORTANT: this does NOT use pip at runtime. Chaquopy's `pip` module is a
 * build-time-only Gradle-plugin concept — it is not bundled into the
 * on-device Python distribution, so `Python.getInstance().getModule("pip")`
 * throws `ModuleNotFoundError: No module named 'pip'` on a real device. That
 * was the flagged-but-unverified assumption in the previous version of this
 * file, and it turned out to be wrong (confirmed via Chaquopy's own issue
 * tracker — pip is a build-time-only tool, not a runtime module).
 *
 * Instead, since yt-dlp ships a pure-Python wheel (no native extensions), we
 * download the `py3-none-any` wheel directly from PyPI, unzip it into
 * app-private storage, and prepend that directory to `sys.path`. Python
 * module state can't be safely hot-swapped mid-process, so the new copy
 * takes effect on the next app start rather than immediately — callers
 * should not expect `installedVersion()` to change within the same session
 * after a successful update.
 */
object YtDlpUpdater {
    private const val TAG = "YtDlpUpdater"
    private const val PYPI_URL = "https://pypi.org/pypi/yt-dlp/json"
    private const val UPDATE_DIR_NAME = "yt_dlp_update"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Manual update checks (triggered from Settings) are capped at 5 per rolling 24h window. */
    private const val MANUAL_UPDATE_WINDOW_MS = 24 * 60 * 60 * 1000L
    private const val MANUAL_UPDATE_MAX_PER_WINDOW = 5

    @Volatile
    private var checkedThisSession = false

    /** Result of a user-triggered check from the "YT-DLP Status" settings item. */
    sealed interface ManualUpdateResult {
        data class Updated(val version: String) : ManualUpdateResult
        data class AlreadyUpToDate(val version: String) : ManualUpdateResult
        data class CooldownActive(val remainingMs: Long) : ManualUpdateResult
        data class Failed(val message: String) : ManualUpdateResult
    }

    /**
     * Loads the stored comma-separated timestamps, drops anything older than
     * the 24h window, and returns the pruned list (oldest first).
     */
    private suspend fun prunedManualCheckTimestamps(context: Context, now: Long): List<Long> {
        val raw = context.dataStore.data.first()[YtDlpManualUpdateTimestampsKey].orEmpty()
        return raw.split(",")
            .mapNotNull { it.trim().toLongOrNull() }
            .filter { now - it < MANUAL_UPDATE_WINDOW_MS }
            .sorted()
    }

    /**
     * User-triggered yt-dlp update check, capped at 5 per rolling 24h window
     * (so it can't be used to hammer PyPI). This is independent of
     * [updateIfNeeded]'s once-per-process automatic check and the self-healing
     * forced update in [YouTubeAudioProvider] on playback failure — both of
     * those keep working exactly as before.
     */
    suspend fun manualUpdateCheck(context: Context, channel: Channel = Channel.STABLE): ManualUpdateResult = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val recentChecks = prunedManualCheckTimestamps(context, now)
        if (recentChecks.size >= MANUAL_UPDATE_MAX_PER_WINDOW) {
            val oldest = recentChecks.first()
            return@withContext ManualUpdateResult.CooldownActive(MANUAL_UPDATE_WINDOW_MS - (now - oldest))
        }

        context.dataStore.edit { it[YtDlpManualUpdateTimestampsKey] = (recentChecks + now).joinToString(",") }

        try {
            val installed = installedVersion()
            val release = latestRelease(channel)
                ?: return@withContext ManualUpdateResult.Failed("Could not reach PyPI")

            // Nightly is force-applied regardless of version compare — it's meant
            // as an escape hatch when stable is broken, not a strict upgrade.
            if (channel == Channel.STABLE && installed != null && !isNewer(release.version, installed)) {
                return@withContext ManualUpdateResult.AlreadyUpToDate(installed)
            }

            Timber.tag(TAG).i("Manual check: downloading yt-dlp $installed -> ${release.version}")
            val ok = downloadAndStageWheel(context, release)
            if (ok) {
                // Takes effect next process start (sys.path already finalized this session).
                ManualUpdateResult.Updated(release.version)
            } else {
                ManualUpdateResult.Failed("Wheel download/extract failed")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Manual yt-dlp update check failed")
            ManualUpdateResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Checks PyPI for the latest yt-dlp release and upgrades in place if the
     * installed version is older. Safe to call repeatedly — only does actual
     * work once per app process. Runs entirely on Dispatchers.IO; callers
     * should launch this from a background coroutine at app startup and not
     * block on it, since a pip install can take several seconds on first run.
     */
    /** GitHub publishes an unstable build after every commit; this is the channel toggle. */
    enum class Channel { STABLE, NIGHTLY }

    // Nightlies are NOT a separate PyPI package — they're pre-releases of the
    // same "yt-dlp" package (what `pip install --pre yt-dlp` picks up). The
    // /json endpoint's top-level "info" only reflects the latest *stable*;
    // pre-release versions live as extra keys in the "releases" map, so for
    // the nightly channel we scan all release keys instead.

    suspend fun updateIfNeeded(context: Context, force: Boolean = false, channel: Channel = Channel.STABLE) {
        if (checkedThisSession && !force) return
        checkedThisSession = true

        withContext(Dispatchers.IO) {
            try {
                val installed = installedVersion()
                val release = latestRelease(channel)

                if (release == null) {
                    Timber.tag(TAG).w("Could not reach PyPI to check yt-dlp version; keeping bundled $installed")
                    return@withContext
                }

                if (channel == Channel.STABLE && installed != null && !isNewer(release.version, installed)) {
                    Timber.tag(TAG).i("yt-dlp is up to date ($installed)")
                    return@withContext
                }

                Timber.tag(TAG).i("Staging yt-dlp update ($channel): $installed -> ${release.version}")
                val ok = downloadAndStageWheel(context, release)
                if (ok) {
                    Timber.tag(TAG).i("yt-dlp ${release.version} staged; takes effect next app start")
                } else {
                    Timber.tag(TAG).w("Failed to stage yt-dlp ${release.version}")
                }
            } catch (e: Exception) {
                // Never let an update failure break playback — the bundled
                // version from build time is still there and still works.
                Timber.tag(TAG).e(e, "yt-dlp self-update failed; continuing with bundled version")
            }
        }
    }

    /**
     * Prepends any previously-staged update directory to `sys.path`, if one
     * exists. Must be called once, early, right after `Python.start()` and
     * before anything imports `yt_dlp` (directly or via `ytm_resolver`) —
     * `sys.path` order only matters for the *first* import in a process.
     */
    fun applyStagedUpdateIfPresent(context: Context) {
        val latestStaged = stagedVersionsDir(context)
            .listFiles { f -> f.isDirectory }
            ?.maxByOrNull { it.lastModified() } ?: return
        runCatching {
            val sys = Python.getInstance().getModule("sys")
            sys.get("path")?.callAttr("insert", 0, latestStaged.absolutePath)
            Timber.tag(TAG).i("Injected staged yt-dlp ${latestStaged.name} onto sys.path")
        }.onFailure { Timber.tag(TAG).e(it, "Failed to inject staged yt-dlp onto sys.path") }
    }

    /** Publicly readable so Settings can show the currently installed version. */
    fun installedVersion(): String? =
        runCatching {
            Python.getInstance().getModule("ytm_resolver").callAttr("get_version").toString()
        }.getOrNull()

    private data class PyPiRelease(val version: String, val wheelUrl: String)

    private fun latestRelease(channel: Channel): PyPiRelease? {
        val request = Request.Builder().url(PYPI_URL).get().build()
        val json = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            JSONObject(body)
        }

        val wheelUrlsFor = { entries: org.json.JSONArray ->
            (0 until entries.length()).asSequence()
                .mapNotNull { entries.optJSONObject(it) }
                .firstOrNull {
                    it.optString("packagetype") == "bdist_wheel" &&
                        it.optString("filename").endsWith("-py3-none-any.whl")
                }?.optString("url")
        }

        if (channel == Channel.STABLE) {
            val version = json.optJSONObject("info")?.optString("version")?.takeIf { it.isNotBlank() }
                ?: return null
            val wheelUrl = json.optJSONArray("urls")?.let(wheelUrlsFor) ?: return null
            return PyPiRelease(version, wheelUrl)
        }

        // NIGHTLY: scan every version in "releases" (includes pre-releases,
        // which is where dev/nightly builds of yt-dlp actually live on PyPI)
        // and take the lexicographically-highest one that has a wheel.
        val releases = json.optJSONObject("releases") ?: return null
        val bestVersion = releases.keys().asSequence().maxByOrNull { it } ?: return null
        val wheelUrl = releases.optJSONArray(bestVersion)?.let(wheelUrlsFor) ?: return null
        return PyPiRelease(bestVersion, wheelUrl)
    }

    /** yt-dlp versions are calendar-style: YYYY.MM.DD[.rev] — lexicographic string compare works. */
    private fun isNewer(latest: String, installed: String): Boolean =
        latest.trim() != installed.trim() && latest.trim() > installed.trim()

    private fun stagedVersionsDir(context: Context): File =
        File(context.filesDir, UPDATE_DIR_NAME).apply { mkdirs() }

    /**
     * Downloads the wheel (a zip file) and extracts it into
     * `filesDir/yt_dlp_update/<version>/`. No pip, no build tooling — a wheel
     * is just a zip with the package's importable contents at its root.
     */
    private fun downloadAndStageWheel(context: Context, release: PyPiRelease): Boolean {
        val destDir = File(stagedVersionsDir(context), release.version)
        if (destDir.exists()) destDir.deleteRecursively()
        destDir.mkdirs()

        val request = Request.Builder().url(release.wheelUrl).get().build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return false
            val bytes = response.body?.bytes() ?: return false
            ZipInputStream(bytes.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    // Skip the .dist-info metadata dir; we only need the importable package.
                    if (!entry.isDirectory && !entry.name.contains(".dist-info/")) {
                        val outFile = File(destDir, entry.name)
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { out -> zip.copyTo(out) }
                    }
                    entry = zip.nextEntry
                }
            }
        }

        // Prune older staged versions, keep only this one.
        stagedVersionsDir(context).listFiles { f -> f.isDirectory && f.name != release.version }
            ?.forEach { it.deleteRecursively() }

        return true
    }
}

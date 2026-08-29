/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.youtube

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.chaquo.python.Python
import com.metrolist.music.constants.YtDlpLastManualUpdateAtKey
import com.metrolist.music.utils.dataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * yt-dlp is bundled at build time via Chaquopy's `pip { install("yt-dlp") }`,
 * but that version goes stale the moment YouTube changes something — which,
 * per yt-dlp's own release cadence, is often. Since we can't ship a Play
 * Store update every time that happens, this checks PyPI for a newer release
 * and upgrades the on-device copy in place using Chaquopy's pip module,
 * which is a real importable Python module at runtime (not just a build-time
 * tool) — `pip.main([...])` runs the same install machinery `pip install`
 * would from a shell.
 */
object YtDlpUpdater {
    private const val TAG = "YtDlpUpdater"
    private const val PYPI_URL = "https://pypi.org/pypi/yt-dlp/json"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Manual update checks (triggered from Settings) are throttled to once every 24h. */
    private const val MANUAL_UPDATE_COOLDOWN_MS = 24 * 60 * 60 * 1000L

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
     * User-triggered yt-dlp update check, throttled to once per 24h regardless
     * of outcome (so it can't be used to hammer PyPI). This is independent of
     * [updateIfNeeded]'s once-per-process automatic check and the self-healing
     * forced update in [YouTubeAudioProvider] on playback failure — both of
     * those keep working exactly as before.
     */
    suspend fun manualUpdateCheck(context: Context): ManualUpdateResult = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val lastManualCheck = context.dataStore.data.first()[YtDlpLastManualUpdateAtKey] ?: 0L
        val elapsed = now - lastManualCheck
        if (elapsed < MANUAL_UPDATE_COOLDOWN_MS) {
            return@withContext ManualUpdateResult.CooldownActive(MANUAL_UPDATE_COOLDOWN_MS - elapsed)
        }

        context.dataStore.edit { it[YtDlpLastManualUpdateAtKey] = now }

        try {
            val installed = installedVersion()
            val latest = latestVersionFromPyPi()
                ?: return@withContext ManualUpdateResult.Failed("Could not reach PyPI")

            if (installed != null && !isNewer(latest, installed)) {
                return@withContext ManualUpdateResult.AlreadyUpToDate(installed)
            }

            Timber.tag(TAG).i("Manual check: upgrading yt-dlp $installed -> $latest")
            val exitCode = runPipUpgrade()
            if (exitCode == 0) {
                ManualUpdateResult.Updated(installedVersion() ?: latest)
            } else {
                ManualUpdateResult.Failed("pip exited with code $exitCode")
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
    suspend fun updateIfNeeded(force: Boolean = false) {
        if (checkedThisSession && !force) return
        checkedThisSession = true

        withContext(Dispatchers.IO) {
            try {
                val installed = installedVersion()
                val latest = latestVersionFromPyPi()

                if (latest == null) {
                    Timber.tag(TAG).w("Could not reach PyPI to check yt-dlp version; keeping bundled $installed")
                    return@withContext
                }

                if (installed != null && !isNewer(latest, installed)) {
                    Timber.tag(TAG).i("yt-dlp is up to date ($installed)")
                    return@withContext
                }

                Timber.tag(TAG).i("Upgrading yt-dlp: $installed -> $latest")
                val exitCode = runPipUpgrade()
                if (exitCode == 0) {
                    Timber.tag(TAG).i("yt-dlp upgraded successfully to ${installedVersion()}")
                } else {
                    Timber.tag(TAG).w("pip install --upgrade yt-dlp exited with code $exitCode")
                }
            } catch (e: Exception) {
                // Never let an update failure break playback — the bundled
                // version from build time is still there and still works.
                Timber.tag(TAG).e(e, "yt-dlp self-update failed; continuing with bundled version")
            }
        }
    }

    /** Publicly readable so Settings can show the currently installed version. */
    fun installedVersion(): String? =
        runCatching {
            Python.getInstance().getModule("ytm_resolver").callAttr("get_version").toString()
        }.getOrNull()

    private fun latestVersionFromPyPi(): String? {
        val request = Request.Builder().url(PYPI_URL).get().build()
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            JSONObject(body).optJSONObject("info")?.optString("version")?.takeIf { it.isNotBlank() }
        }
    }

    /** yt-dlp versions are calendar-style: YYYY.MM.DD[.rev] — lexicographic string compare works. */
    private fun isNewer(latest: String, installed: String): Boolean =
        latest.trim() != installed.trim() && latest.trim() > installed.trim()

    /**
     * Runs pip's own install/upgrade routine in-process via Chaquopy's pip
     * module. Returns pip's process-style exit code (0 = success).
     */
    private fun runPipUpgrade(): Int {
        val pip = Python.getInstance().getModule("pip")
        return pip.callAttr("main", listOf("install", "--upgrade", "--no-cache-dir", "yt-dlp")).toInt()
    }
}

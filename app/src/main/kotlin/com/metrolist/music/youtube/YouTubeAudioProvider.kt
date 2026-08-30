/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.youtube

import com.metrolist.innertube.NewPipeExtractor
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.NewPipeUtils
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YouTubeClient
import com.metrolist.innertube.models.response.PlayerResponse
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as ExtractorRequest
import org.schabi.newpipe.extractor.downloader.Response as ExtractorResponse
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import com.metrolist.music.utils.cipher.CipherDeobfuscator
import com.metrolist.music.utils.potoken.PoTokenGenerator
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import android.net.Uri
import com.chaquo.python.Python

object YouTubeAudioProvider {
    const val STREAM_MARKER_QUERY = "_metrolist_youtube"

    private const val TAG = "YouTubeAudioProvider"
    private const val STREAM_MARKER_BRAVEPIPE = "bravepipe"
    private const val MIN_TARGET_KBPS = 128
    private const val MAX_TARGET_KBPS = 256
    private const val MAX_TARGET_BPS = MAX_TARGET_KBPS * 1000
    private const val DEFAULT_STREAM_CACHE_MS = 5 * 60 * 1000L
    private const val WATCH_URL_PREFIX = "https://www.youtube.com/watch?v="
    private const val ORIGIN_YOUTUBE = "https://www.youtube.com"
    private const val REFERER_YOUTUBE = "https://www.youtube.com/"

    private val streamCache = ConcurrentHashMap<String, Resolved>()

    /**
     * Short-lived cache for Innertube player responses. YouTube re-signs
     * streaming URLs every ~5 min, but the player response (format list,
     * playability) is stable for ~60 s. Caching it eliminates the client POST
     * race on skip-back and replay — the single biggest latency win.
     */
    private data class CachedPlayerResponse(
        val response: PlayerResponse,
        val clientKey: String,
        val cachedAtMs: Long,
    )

    private val playerResponseCache = ConcurrentHashMap<String, CachedPlayerResponse>()
    private const val PLAYER_RESPONSE_TTL_MS = 60_000L

    private val httpClient = OkHttpClient.Builder()
        .proxy(YouTube.proxy)
        .proxyAuthenticator { _, response ->
            YouTube.proxyAuth?.let { auth ->
                response.request
                    .newBuilder()
                    .header("Proxy-Authorization", auth)
                    .build()
            } ?: response.request
        }
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val extractorDownloader = BravePipeExtractorDownloader(httpClient)
    private val poTokenGenerator = PoTokenGenerator()
    private val innertubePlaybackClients = listOf(
        PlaybackClientCandidate("android_vr_no_auth", YouTubeClient.ANDROID_VR_NO_AUTH),
        PlaybackClientCandidate("android_vr_143", YouTubeClient.ANDROID_VR_1_43_32),
        PlaybackClientCandidate("tv_embedded", YouTubeClient.TVHTML5_SIMPLY_EMBEDDED_PLAYER),
        PlaybackClientCandidate("ios", YouTubeClient.IOS),
        PlaybackClientCandidate("android_music", YouTubeClient.ANDROID_MUSIC),
        PlaybackClientCandidate("web_remix", YouTubeClient.WEB_REMIX),
    )

    @Volatile
    private var extractorInitialized = false

    private data class PlaybackClientCandidate(
        val key: String,
        val client: YouTubeClient,
    )

    data class Resolved(
        val mediaUri: String,
        val videoId: String,
        val clientKey: String,
        val itag: Int,
        val mimeType: String,
        val codecs: String,
        val bitrate: Int,
        val sampleRate: Int?,
        val contentLength: Long?,
        val loudnessDb: Double?,
        val perceptualLoudnessDb: Double?,
        val expiresAtMs: Long,
    )

    class YouTubeAudioResolutionException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * Track metadata used to resolve a real YouTube video ID via search when
     * the caller's mediaId isn't one (e.g. a Spotify URI falling back to
     * YouTube for playback). Mirrors what the user would get manually by
     * hitting "share" on a track and searching
     * `music.youtube.com/search?q=<title>+<artist>`.
     */
    data class TrackQuery(val title: String, val artist: String)

    /** A real YouTube video ID is always exactly 11 chars of this charset. */
    private val YOUTUBE_VIDEO_ID_REGEX = Regex("^[A-Za-z0-9_-]{11}$")

    private fun isValidYouTubeVideoId(id: String): Boolean = YOUTUBE_VIDEO_ID_REGEX.matches(id)

    suspend fun resolve(mediaId: String, context: android.content.Context, fallbackQuery: TrackQuery? = null): Resolved {
        val now = System.currentTimeMillis()
        // Cache is keyed by the ORIGINAL id (e.g. the Spotify URI), not the
        // resolved YouTube id, so repeat playback of the same non-YouTube
        // track doesn't re-search every time.
        streamCache[mediaId]
            ?.takeIf { it.expiresAtMs > now + 30_000L }
            ?.let { return it }

        val videoId = if (isValidYouTubeVideoId(mediaId)) {
            mediaId
        } else {
            val query = fallbackQuery
                ?: throw YouTubeAudioResolutionException(
                    "Cannot resolve non-YouTube id '$mediaId': no track metadata provided for search fallback",
                )
            resolveViaSearch(query)
        }

        return resolveVideoId(videoId, now, cacheKey = mediaId, context = context)
    }

    /**
     * Searches YouTube Music for [query] and returns the top song result's
     * real video ID — the same mechanism as manually sharing a track and
     * pasting `https://music.youtube.com/search?q=<title>+<artist>`.
     */
    private suspend fun resolveViaSearch(query: TrackQuery): String {
        val searchQuery = listOf(query.title, query.artist)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        if (searchQuery.isBlank()) {
            throw YouTubeAudioResolutionException("Cannot search YouTube: empty title/artist")
        }

        val result = YouTube.search(searchQuery, YouTube.SearchFilter.FILTER_SONG).getOrElse { error ->
            throw YouTubeAudioResolutionException(
                "YouTube search failed for \"$searchQuery\": ${error.readableMessage()}",
                error,
            )
        }

        val topSong = result.items.filterIsInstance<SongItem>().firstOrNull()
            ?: throw YouTubeAudioResolutionException("No YouTube Music results for \"$searchQuery\"")

        Timber.tag(TAG).i("Resolved \"$searchQuery\" -> YouTube video ${topSong.id} (${topSong.title})")
        return topSong.id
    }

    private suspend fun resolveVideoId(videoId: String, now: Long, cacheKey: String, context: android.content.Context): Resolved {

        // yt-dlp (on-device via Chaquopy) is now the sole primary path. It
        // uses the device's own IP, handles SABR internally, and its
        // extractor is kept current independent of app releases via
        // YtDlpUpdater. The innertube-client race and BravePipe/NewPipe
        // extractor that used to live here have been fully retired — that
        // was the whole point of this refactor: one resolution path instead
        // of three that all needed independent maintenance against YouTube's
        // churn.
        var ytDlpFailure: Throwable? = null
        runCatching { resolveWithYtDlpOnDevice(videoId, now) }
            .onSuccess { return cache(cacheKey, it) }
            .onFailure {
                ytDlpFailure = it
                Timber.tag(TAG).w(it, "yt-dlp resolution failed for $videoId, forcing an update and retrying once")
            }

        // First failure this session: force a yt-dlp version check/upgrade
        // (normally rate-limited to once per process) and retry immediately.
        // Most yt-dlp failures on a given device are "YouTube shipped a
        // breaking change and our bundled build-time version doesn't know
        // about it yet" — this is the self-healing path for that.
        runCatching {
            YtDlpUpdater.updateIfNeeded(context, force = true)
            resolveWithYtDlpOnDevice(videoId, now)
        }.onSuccess { return cache(cacheKey, it) }
            .onFailure { ytDlpFailure = it }

        // Last resort: some responses (SABR-only, no direct progressive/
        // adaptive URL at all) can't be handled by URL-extraction no matter
        // how many clients we try — yt-dlp's SABR handling only exists in
        // its actual download path, not extract_info(download=False). Have
        // yt-dlp fully download the audio (SABR handled transparently on
        // its end) to a local cache file and hand ExoPlayer that instead of
        // a remote URL. Slower and uses local storage, so this only runs
        // once URL-based resolution has already failed twice above.
        runCatching { resolveWithYtDlpDownload(videoId, now, context) }
            .onSuccess { return cache(cacheKey, it) }
            .onFailure {
                ytDlpFailure = it
                Timber.tag(TAG).w(it, "yt-dlp download fallback also failed for $videoId")
            }

        throw YouTubeAudioResolutionException(
            "YouTube Music playback failed for $videoId: " +
                    "yt-dlp=${ytDlpFailure?.readableMessage() ?: "no candidate produced a stream"}",
        )
    }

    /**
     * Resolves a stream using yt-dlp running on-device via Chaquopy.
     */
    private suspend fun resolveWithYtDlpOnDevice(
        videoId: String,
        now: Long,
    ): Resolved = withContext(Dispatchers.IO) {
        val py = Python.getInstance()
        val ytmResolver = py.getModule("ytm_resolver")
        val resultJsonString = ytmResolver.callAttr("resolve", videoId).toString()

        val json = org.json.JSONObject(resultJsonString)
        if (json.has("error")) {
            throw YouTubeAudioResolutionException("yt-dlp failed: ${json.getString("error")}")
        }

        val streamUrl = json.getString("url")
        val workingClient = json.optString("working_client", "unknown")

        Resolved(
            mediaUri = addStreamMarker(streamUrl, "ytdlp"),
            videoId = videoId,
            clientKey = "ytdlp:$workingClient",
            itag = json.optInt("itag", 251),
            mimeType = json.optString("mime", "audio/webm"),
            codecs = json.optString("acodec", "opus"),
            bitrate = (json.optDouble("bitrate", 0.0) * 1000).toInt(),
            sampleRate = null,
            contentLength = null,
            loudnessDb = null,
            perceptualLoudnessDb = null,
            // yt-dlp URLs usually last a while
            expiresAtMs = now + 60 * 60 * 1000L,
        )
    }

    /**
     * One-in-flight-at-a-time download cache dir. Only ever expect one
     * download-fallback file to matter at a time in practice (this is a
     * last-resort path, not the common case), so cleanup is opportunistic:
     * deleting the previous file whenever a new one lands covers normal
     * skip/next-track flow, and sweeping the whole subdirectory on each
     * call catches anything orphaned by a crash or force-stop.
     */
    private const val YTDLP_DOWNLOAD_SUBDIR = "ytdlp_downloads"

    @Volatile
    private var lastDownloadedFile: java.io.File? = null

    /**
     * Resolves a stream by having yt-dlp fully download the audio to a
     * local cache file, rather than extracting a direct URL. This is the
     * only way to get SABR-only responses playable at all without
     * reimplementing YouTube's SABR wire protocol in Kotlin/ExoPlayer:
     * yt-dlp's SABR handling lives entirely inside its download path
     * (download=True), not its URL-extraction path (download=False) — a
     * SABR-only client simply has no plain URL to hand back from the
     * latter.
     */
    private suspend fun resolveWithYtDlpDownload(
        videoId: String,
        now: Long,
        context: android.content.Context,
    ): Resolved = withContext(Dispatchers.IO) {
        val downloadDir = java.io.File(context.cacheDir, YTDLP_DOWNLOAD_SUBDIR).apply { mkdirs() }

        // Sweep stray files before starting a new download — catches
        // anything left behind by a crash, force-stop, or a previous
        // failed attempt, not just the last successful one.
        downloadDir.listFiles()?.forEach { it.delete() }

        val py = Python.getInstance()
        val ytmResolver = py.getModule("ytm_resolver")
        val resultJsonString = ytmResolver.callAttr("download", videoId, downloadDir.absolutePath).toString()

        val json = org.json.JSONObject(resultJsonString)
        if (json.has("error")) {
            throw YouTubeAudioResolutionException("yt-dlp download failed: ${json.getString("error")}")
        }

        val filePath = json.getString("filepath")
        val file = java.io.File(filePath)
        if (!file.isFile) {
            throw YouTubeAudioResolutionException("yt-dlp reported a downloaded file that doesn't exist: $filePath")
        }

        lastDownloadedFile = file
        val workingClient = json.optString("working_client", "unknown")

        Resolved(
            mediaUri = Uri.fromFile(file).toString(),
            videoId = videoId,
            clientKey = "ytdlp-download:$workingClient",
            itag = json.optInt("itag", -1),
            mimeType = when (json.optString("mime", "")) {
                "webm" -> "audio/webm"
                "m4a" -> "audio/mp4"
                else -> "audio/mp4"
            },
            codecs = json.optString("acodec", "opus"),
            bitrate = (json.optDouble("bitrate", 0.0) * 1000).toInt(),
            sampleRate = null,
            contentLength = json.optLong("filesize", -1L).takeIf { it > 0 },
            loudnessDb = null,
            perceptualLoudnessDb = null,
            // The file is already fully downloaded and local — there's
            // nothing to re-fetch or expire the way a remote URL would.
            // Give it a long window; it gets cleaned up on the NEXT
            // download call (or via releaseDownloadedFile) regardless.
            expiresAtMs = now + 24 * 60 * 60 * 1000L,
        )
    }

    /**
     * Deletes a downloaded fallback file after playback is done with it.
     * Call this from wherever playback lifecycle is tracked (track
     * finished, skipped, or player released) once that hook is wired up.
     * Safe to call even if [mediaUri] wasn't a download-fallback file (or
     * the file's already gone) — this is best-effort cleanup, not a
     * correctness requirement, since the NEXT download call also sweeps
     * the whole subdirectory regardless.
     */
    fun releaseDownloadedFile(mediaUri: String) {
        val uri = runCatching { Uri.parse(mediaUri) }.getOrNull() ?: return
        if (uri.scheme != "file") return
        val path = uri.path ?: return
        runCatching { java.io.File(path).delete() }
        if (lastDownloadedFile?.absolutePath == path) {
            lastDownloadedFile = null
        }
    }

    private suspend fun resolveWithExtractor(
        videoId: String,
        now: Long,
    ): Resolved = withContext(Dispatchers.IO) {
        ensureExtractorInitialized()

        val streamInfo = runCatching {
            StreamInfo.getInfo("$WATCH_URL_PREFIX$videoId")
        }.getOrElse { error ->
            throw YouTubeAudioResolutionException(
                "BravePipeExtractor could not read YouTube stream info for $videoId: ${error.readableMessage()}",
                error,
            )
        }

        val stream = selectAudioStream(streamInfo.audioStreams)
            ?: throw YouTubeAudioResolutionException(
                "No direct YouTube audio stream found for $videoId at ${MIN_TARGET_KBPS}-${MAX_TARGET_KBPS} kbps",
                streamInfo.errors.firstOrNull(),
            )

        val streamUrl = stream.content.takeIf { stream.isUrl && it.isNotBlank() }
            ?: throw YouTubeAudioResolutionException("Selected YouTube audio stream is not a direct URL")

        val expiresAtMs = resolveExpiryMs(streamUrl, now)
        if (expiresAtMs <= now + 45_000L) {
            throw YouTubeAudioResolutionException("Selected YouTube audio stream expires too soon")
        }

        Resolved(
            mediaUri = addStreamMarker(streamUrl, STREAM_MARKER_BRAVEPIPE),
            videoId = videoId,
            clientKey = STREAM_MARKER_BRAVEPIPE,
            itag = stream.itag.takeIf { it > 0 } ?: stream.id.toIntOrNull() ?: -1,
            mimeType = stream.mimeType,
            codecs = stream.codecString,
            bitrate = stream.safeDisplayBitrate,
            sampleRate = stream.itagItem?.sampleRate?.takeIf { it > 0 },
            contentLength = stream.itagItem?.contentLength?.takeIf { it > 0 },
            loudnessDb = null,
            perceptualLoudnessDb = null,
            expiresAtMs = expiresAtMs,
        )
    }

    private suspend fun resolveWithInnertubeFallback(
        videoId: String,
        now: Long,
    ): Resolved = coroutineScope {
        val signatureTimestampDeferred = async(start = CoroutineStart.LAZY) {
            NewPipeUtils.getSignatureTimestamp(videoId).getOrNull()
        }

        // Check player response cache first — eliminates the entire client
        // race on replay / skip-back within 60 seconds.
        playerResponseCache[videoId]?.let { cached ->
            if (System.currentTimeMillis() - cached.cachedAtMs < PLAYER_RESPONSE_TTL_MS) {
                buildResolvedFromPlayerResponse(cached.response, videoId, now, cached.clientKey)?.let { return@coroutineScope it }
            }
        }

        // Race all 3 clients in parallel — no sequential fallback needed.
        val channel = Channel<Resolved>(innertubePlaybackClients.size)
        val candidateFailures = ConcurrentHashMap<String, String>()

        val jobs = innertubePlaybackClients.map { candidate ->
            launch {
                try {
                    attemptCandidate(candidate, videoId, now, signatureTimestampDeferred, candidateFailures)?.let {
                        channel.send(it)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    candidateFailures[candidate.key] = e.readableMessage()
                    Timber.tag(TAG).d("Candidate ${candidate.key} failed: ${e.message}")
                }
            }
        }

        launch {
            jobs.forEach { it.join() }
            channel.close()
        }

        for (result in channel) {
            jobs.forEach { it.cancel() }
            return@coroutineScope result
        }

        val reasons = candidateFailures.entries.joinToString(", ") { "${it.key}=${it.value}" }
        throw YouTubeAudioResolutionException(
            "No Innertube YouTube audio stream found for $videoId${if (reasons.isNotBlank()) " ($reasons)" else ""}",
        )
    }

    /**
     * Attempts to resolve a stream from a single client candidate.
     * No speculative validation — selects the best format by metadata and
     * returns it. ExoPlayer surfaces URL failures natively.
     */
    private suspend fun attemptCandidate(
        candidate: PlaybackClientCandidate,
        videoId: String,
        now: Long,
        signatureTimestampDeferred: Deferred<Int?>,
        failures: ConcurrentHashMap<String, String>,
    ): Resolved? {
        val client = candidate.client

        val poTokenResult = if (client.useWebPoTokens) {
            val sessionId = YouTube.dataSyncId ?: YouTube.visitorData
            if (sessionId != null) {
                runCatching {
                    poTokenGenerator.getWebClientPoToken(videoId, sessionId)
                }.onFailure {
                    Timber.tag(TAG).e(it, "PoToken generation failed for $videoId")
                }.getOrNull()
            } else null
        } else null

        // If the client requires PoTokens and generation failed, return null immediately.
        if (client.useWebPoTokens && poTokenResult == null) {
            failures[candidate.key] = "poToken unavailable"
            return null
        }

        val playerResponse = withContext(Dispatchers.IO) {
            val signatureTimestamp = if (client.useSignatureTimestamp) signatureTimestampDeferred.await() else null
            YouTube.player(
                videoId = videoId,
                signatureTimestamp = signatureTimestamp,
                client = client,
                poToken = poTokenResult?.playerRequestPoToken
            ).getOrNull()
        }

        if (playerResponse == null) {
            failures[candidate.key] = "player response null"
            return null
        }
        if (playerResponse.playabilityStatus.status != "OK") {
            failures[candidate.key] = "playability=${playerResponse.playabilityStatus.status}"
            return null
        }

        // Cache the successful player response, tagged with its client key,
        // so a later cache hit rebuilds headers for the right client.
        playerResponseCache[videoId] = CachedPlayerResponse(playerResponse, candidate.key, System.currentTimeMillis())

        var resolved = buildResolvedFromPlayerResponse(playerResponse, videoId, now, candidate.key)
        if (resolved == null) {
            failures[candidate.key] = "no usable audio format in player response"
            return null
        }

        // Apply n-parameter transformation and PoToken to the stream URL if required.
        if (client.useWebPoTokens) {
            try {
                var streamUrl = resolved.mediaUri
                // Prioritize NewPipe throttling deobfuscation
                val newPipeUrl = NewPipeExtractor.getThrottlingDeobfuscatedUrl(videoId, streamUrl)
                if (newPipeUrl != null && newPipeUrl != streamUrl) {
                    streamUrl = newPipeUrl
                } else {
                    // Fallback to CipherDeobfuscator
                    val transformedUrl = CipherDeobfuscator.transformNParamInUrl(streamUrl)
                    if (transformedUrl != streamUrl) {
                        streamUrl = transformedUrl
                    }
                }

                // Append pot= parameter if streaming poToken is available
                poTokenResult?.streamingDataPoToken?.let { streamingPoToken ->
                    val uri = Uri.parse(streamUrl)
                    if (uri.getQueryParameter("pot") == null) {
                        val separator = if (streamUrl.contains("?")) "&" else "?"
                        streamUrl = "${streamUrl}${separator}pot=${Uri.encode(streamingPoToken)}"
                    }
                }

                if (streamUrl != resolved.mediaUri) {
                    resolved = resolved.copy(mediaUri = streamUrl)
                }
            } catch (e: Exception) {
                failures[candidate.key] = "n-transform/poToken failed: ${e.readableMessage()}"
                Timber.tag(TAG).e(e, "Failed to apply n-transform or PoToken to stream URL")
                return null // Return null if transformation/PoToken fails
            }
        }

        return resolved
    }



    /**
     * Selects the best audio format from a player response and builds a
     * [Resolved] without any network validation. Shared by the client race
     * and the playerResponseCache hit path.
     */
    private fun buildResolvedFromPlayerResponse(
        playerResponse: PlayerResponse,
        videoId: String,
        now: Long,
        clientKey: String? = null,
    ): Resolved? {
        val expectedDurationMs = playerResponse.videoDetails?.lengthSeconds
            ?.toLongOrNull()
            ?.takeIf { it > 0 }
            ?.times(1000L)

        val format = selectInnertubeAudioFormats(playerResponse, expectedDurationMs).firstOrNull()
            ?: return null

        val streamUrl = NewPipeUtils.getStreamUrl(format, videoId).getOrNull()
            ?: format.url
            ?: return null

        val expiresAtMs = resolveExpiryMs(
            url = streamUrl,
            now = now,
            apiExpiresInSeconds = playerResponse.streamingData?.expiresInSeconds,
        )
        if (expiresAtMs <= now + 45_000L) return null

        val effectiveClientKey = clientKey ?: innertubePlaybackClients.first().key

        return Resolved(
            mediaUri = addStreamMarker(streamUrl, effectiveClientKey),
            videoId = videoId,
            clientKey = effectiveClientKey,
            itag = format.itag,
            mimeType = format.cleanMimeType,
            codecs = format.codecString,
            bitrate = (format.averageBitrate ?: format.bitrate).takeIf { it > 0 } ?: 0,
            sampleRate = format.audioSampleRate?.takeIf { it > 0 },
            contentLength = format.contentLength?.takeIf { it > 0 },
            loudnessDb = format.loudnessDb ?: playerResponse.playerConfig?.audioConfig?.loudnessDb,
            perceptualLoudnessDb = playerResponse.playerConfig?.audioConfig?.perceptualLoudnessDb,
            expiresAtMs = expiresAtMs,
        )
    }

    fun invalidate(videoId: String) {
        streamCache.remove(videoId)
        playerResponseCache.remove(videoId)
    }

    fun userAgentFor(clientKey: String?): String =
        innertubePlaybackClients.firstOrNull { it.key == clientKey }?.client?.userAgent
            ?: BravePipeExtractorDownloader.USER_AGENT

    fun addYouTubePlaybackHeaders(
        builder: Request.Builder,
        clientKey: String?,
        hasRangeHeader: Boolean,
    ): Request.Builder {
        return addYouTubeHeaders(builder, clientKey)
            .apply {
                if (!hasRangeHeader) {
                    header("Range", "bytes=0-")
                }
            }
    }

    fun addYouTubeHeaders(
        builder: Request.Builder,
        clientKey: String?,
    ): Request.Builder {
        val candidate = innertubePlaybackClients.firstOrNull { it.key == clientKey }
        val client = candidate?.client

        builder
            .header("User-Agent", client?.userAgent ?: BravePipeExtractorDownloader.USER_AGENT)
            .header("Accept", "*/*")
            .header("Accept-Encoding", "identity")
            .header("Origin", ORIGIN_YOUTUBE)
            .header("Referer", REFERER_YOUTUBE)

        if (client != null) {
            builder.header("X-YouTube-Client-Name", client.clientId)
            builder.header("X-YouTube-Client-Version", client.clientVersion)
            builder.header("X-Goog-Api-Format-Version", client.apiFormatVersion)
            builder.header("X-YouTube-Device", YouTubeClient.X_YOUTUBE_DEVICE_ONEPLUS_6T)
        }

        YouTube.cookie?.takeIf { it.isNotBlank() }?.let { builder.header("Cookie", it) }

        return builder
    }

    @Synchronized
    private fun ensureExtractorInitialized() {
        if (extractorInitialized) return

        NewPipe.init(
            extractorDownloader,
            Localization("en", "US"),
            ContentCountry("US"),
        )
        extractorInitialized = true
    }

    private fun selectAudioStream(streams: List<AudioStream>): AudioStream? {
        val directStreams = streams
            .asSequence()
            .filter { it.isUrl }
            .filter { it.content.isNotBlank() }
            .filter { stream ->
                val bitrate = stream.effectiveKbps
                bitrate == 0 || bitrate <= MAX_TARGET_KBPS
            }
            .toList()

        val inTarget = directStreams.filter { it.averageKbps in MIN_TARGET_KBPS..MAX_TARGET_KBPS }
        val underCap = directStreams.filter { it.effectiveKbps in 1..MAX_TARGET_KBPS }

        return (inTarget.ifEmpty { underCap }.ifEmpty { directStreams })
            .minWithOrNull(
                compareBy<AudioStream> { it.formatPreference }
                    .thenBy { if (it.effectiveKbps >= MIN_TARGET_KBPS) 0 else 1 }
                    .thenByDescending { it.effectiveKbps },
            )
    }

    private fun addStreamMarker(
        url: String,
        clientKey: String,
    ): String =
        url.toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter(STREAM_MARKER_QUERY, clientKey)
            ?.build()
            ?.toString()
            ?: url

    private fun resolveExpiryMs(
        url: String,
        now: Long,
        apiExpiresInSeconds: Int? = null,
    ): Long {
        val urlExpiry = url.toHttpUrlOrNull()
            ?.queryParameter("expire")
            ?.toLongOrNull()
            ?.times(1000L)
            ?.minus(30_000L)
        val apiExpiry = apiExpiresInSeconds
            ?.takeIf { it > 0 }
            ?.let { now + (it * 1000L) - 30_000L }
        return listOfNotNull(urlExpiry, apiExpiry, now + DEFAULT_STREAM_CACHE_MS)
            .minOrNull()
            ?.coerceAtLeast(now + 30_000L)
            ?: (now + DEFAULT_STREAM_CACHE_MS)
    }

    private fun selectInnertubeAudioFormats(
        playerResponse: PlayerResponse,
        expectedDurationMs: Long?,
    ): List<PlayerResponse.StreamingData.Format> {
        val audioFormats = playerResponse.streamingData?.adaptiveFormats
            ?.asSequence()
            ?.filter { it.isAudio }
            ?.filter { it.url != null || it.signatureCipher != null || it.cipher != null }
            ?.filterNot { expectedDurationMs != null && it.isLikelyPreview(expectedDurationMs) }
            ?.filter { format ->
                val bitrateKbps = ((format.averageBitrate ?: format.bitrate).takeIf { it > 0 } ?: 0) / 1000
                bitrateKbps == 0 || bitrateKbps <= MAX_TARGET_KBPS
            }
            ?.toList()
            .orEmpty()

        val inTarget = audioFormats.filter {
            ((it.averageBitrate ?: it.bitrate).takeIf { bitrate -> bitrate > 0 } ?: 0) / 1000 in MIN_TARGET_KBPS..MAX_TARGET_KBPS
        }
        val underCap = audioFormats.filter {
            ((it.averageBitrate ?: it.bitrate).takeIf { bitrate -> bitrate > 0 } ?: 0) / 1000 in 1..MAX_TARGET_KBPS
        }

        return (inTarget.ifEmpty { underCap }.ifEmpty { audioFormats })
            .sortedWith(
                compareByDescending<PlayerResponse.StreamingData.Format> { it.isDirectUrl }
                    .thenByDescending { it.codecRank }
                    .thenByDescending { it.averageBitrate ?: it.bitrate },
            )
    }

    private val AudioStream.averageKbps: Int
        get() = averageBitrate.takeIf { it > 0 }
            ?: (bitrate.takeIf { it > 0 }?.div(1000))
            ?: 0

    private val AudioStream.effectiveKbps: Int
        get() = averageKbps.takeIf { it > 0 }
            ?: fallbackBitrateBps.div(1000)

    private val AudioStream.fallbackBitrateBps: Int
        get() = bitrate.takeIf { it > 0 } ?: 0

    private val AudioStream.safeDisplayBitrate: Int
        get() = (averageKbps.takeIf { it > 0 }?.times(1000) ?: fallbackBitrateBps)
            .takeIf { it > 0 }
            ?.coerceAtMost(MAX_TARGET_BPS)
            ?: 0

    private val AudioStream.formatPreference: Int
        get() = when (format) {
            MediaFormat.M4A -> 0
            MediaFormat.WEBMA_OPUS -> 1
            MediaFormat.WEBMA -> 2
            else -> 3
        }

    private val AudioStream.mimeType: String
        get() = format?.mimeType ?: when {
            codecString.startsWith("mp4a", ignoreCase = true) -> "audio/mp4"
            codecString.equals("opus", ignoreCase = true) -> "audio/webm"
            else -> "audio/mp4"
        }

    private val AudioStream.codecString: String
        get() = codec
            ?.takeIf { it.isNotBlank() }
            ?: when (format) {
                MediaFormat.M4A -> "mp4a.40.2"
                MediaFormat.WEBMA_OPUS -> "opus"
                MediaFormat.WEBMA -> "opus"
                else -> ""
            }

    private val PlayerResponse.StreamingData.Format.isDirectUrl: Boolean
        get() = url != null

    private val PlayerResponse.StreamingData.Format.cleanMimeType: String
        get() = mimeType.substringBefore(';').trim().ifBlank { "audio/mp4" }

    private val PlayerResponse.StreamingData.Format.codecString: String
        get() = Regex("""codecs="([^"]+)"""")
            .find(mimeType)
            ?.groupValues
            ?.getOrNull(1)
            ?.split(',')
            ?.firstOrNull()
            ?.trim()
            ?: when {
                cleanMimeType.contains("webm", ignoreCase = true) -> "opus"
                cleanMimeType.contains("mp4", ignoreCase = true) -> "mp4a.40.2"
                else -> ""
            }

    private val PlayerResponse.StreamingData.Format.codecRank: Int
        get() = when {
            codecString.contains("opus", ignoreCase = true) -> 3
            codecString.contains("mp4a", ignoreCase = true) -> 2
            else -> 1
        }

    private fun PlayerResponse.StreamingData.Format.isLikelyPreview(expectedDurationMs: Long): Boolean {
        if (expectedDurationMs < 90_000L) return false
        val approxDurationMs = approxDurationMs?.toLongOrNull() ?: return false
        return approxDurationMs in 1L..minOf(90_000L, (expectedDurationMs * 9L) / 10L)
    }

    private fun cache(
        videoId: String,
        resolved: Resolved,
    ): Resolved =
        resolved.also {
            Timber.tag(TAG).i(
                "Resolved YouTube stream for $videoId via ${resolved.clientKey}: " +
                        "itag=${resolved.itag}, mime=${resolved.mimeType}, bitrate=${resolved.bitrate}",
            )
            streamCache[videoId] = resolved
        }

    private fun Throwable.readableMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

    private class BravePipeExtractorDownloader(
        private val client: OkHttpClient,
    ) : Downloader() {
        override fun execute(request: ExtractorRequest): ExtractorResponse {
            val requestBody = request.dataToSend()?.toRequestBody()
            val builder = Request.Builder()
                .url(request.url())
                .method(request.httpMethod(), requestBody)
                .header("User-Agent", USER_AGENT)

            if (request.url().isYouTubeHost()) {
                YouTube.cookie?.takeIf { it.isNotBlank() }?.let { builder.header("Cookie", it) }
            }

            request.headers().forEach { (name, values) ->
                builder.removeHeader(name)
                values.forEach { value -> builder.addHeader(name, value) }
            }

            try {
                client.newCall(builder.build()).execute().use { response ->
                    if (response.code == 429) {
                        throw ReCaptchaException("reCaptcha Challenge requested", request.url())
                    }

                    return ExtractorResponse(
                        response.code,
                        response.message,
                        response.headers.toMultimap(),
                        response.body.string(),
                        response.request.url.toString(),
                    )
                }
            } catch (e: ReCaptchaException) {
                throw e
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                throw IOException("BravePipeExtractor request failed", e)
            }
        }

        private fun String.isYouTubeHost(): Boolean {
            return toHttpUrlOrNull()?.host?.let { host ->
                host == "youtube.com" ||
                        host.endsWith(".youtube.com") ||
                        host == "googlevideo.com" ||
                        host.endsWith(".googlevideo.com")
            } ?: false
        }

        companion object {
            const val USER_AGENT =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
        }
    }
}
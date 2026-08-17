/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import android.content.Context
import com.metrolist.music.constants.EnableMusixmatchKey
import com.metrolist.music.constants.MusixmatchForceLineSyncedKey
import com.metrolist.music.constants.MusixmatchUserTokenKey
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import androidx.datastore.preferences.core.edit
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.contentOrNull
import timber.log.Timber

/**
 * Client-side Musixmatch lyrics provider, talking directly to the unofficial
 * apic-desktop endpoints (same ones the official desktop app uses).
 *
 * Musixmatch usertokens are short-lived session tokens, not permanent
 * credentials, so this provider fetches its own token via `token.get` on
 * first use, caches it (in-memory + persisted to [MusixmatchUserTokenKey]),
 * and transparently refreshes it if a request comes back with status 401.
 */
object MusixmatchLyricsProvider : LyricsProvider {
    private const val TAG = "MusixmatchProvider"

    // Seed fallback token, used only until token.get gives us a fresh one.
    private const val SEED_USER_TOKEN = "260816fe956776e6cdfa67bb7f3d7e204ce0cf23432d1ed897d70a"

    private const val APP_ID = "web-desktop-app-v1.0"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    override val name = "Musixmatch"

    override fun isEnabled(context: Context): Boolean =
        context.dataStore[EnableMusixmatchKey] ?: true

    @Volatile
    private var cachedToken: String? = null
    private val tokenMutex = Mutex()

    private val client by lazy {
        HttpClient(CIO) {
            install(ContentNegotiation) {
                val lenientJson = Json {
                    isLenient = true
                    ignoreUnknownKeys = true
                }
                // Musixmatch's apic-desktop endpoints return actual JSON bodies but
                // mislabel the Content-Type as text/plain, so register the JSON
                // converter for both content types or ktor throws
                // NoTransformationFoundException.
                json(lenientJson)
                json(lenientJson, contentType = io.ktor.http.ContentType.Text.Plain)
            }

            install(HttpTimeout) {
                requestTimeoutMillis = 15000
                connectTimeoutMillis = 10000
                socketTimeoutMillis = 15000
            }

            defaultRequest {
                url("https://apic-desktop.musixmatch.com")
                headers {
                    append("User-Agent", USER_AGENT)
                    append("Cookie", "x-mxm-token-guid=")
                }
            }

            expectSuccess = false
        }
    }

    // Safe nested JSON object navigation: message -> body
    // Musixmatch returns `body: []` (empty array) instead of an object when a
    // given macro sub-call (e.g. track.richsync.get) has no data for the track,
    // even while reporting status_code 200 — so this must not hard-cast.
    private fun JsonObject.messageBody(): JsonObject? =
        (this["message"] as? JsonObject)?.get("body") as? JsonObject

    private fun JsonObject.statusCode(): Int? =
        ((this["message"] as? JsonObject)?.get("header") as? JsonObject)?.get("status_code")?.jsonPrimitive?.contentOrNull?.toIntOrNull()

    /** Fetch a fresh usertoken from Musixmatch's own token.get endpoint. */
    private suspend fun fetchFreshToken(): String? = runCatching {
        val response = client.get("/ws/1.1/token.get") {
            parameter("app_id", APP_ID)
            parameter("user_language", "en")
        }.body<JsonObject>()

        val status = response.statusCode()
        if (status != 200) {
            Timber.tag(TAG).w("token.get returned status $status")
            return@runCatching null
        }

        val token = response.messageBody()?.get("user_token")?.jsonPrimitive?.contentOrNull
        if (!token.isNullOrBlank()) {
            Timber.tag(TAG).d("Got fresh Musixmatch usertoken")
        }
        token
    }.getOrElse { e ->
        Timber.tag(TAG).e(e, "Exception during token.get")
        null
    }

    /** Resolve a usable token: user-set pref > in-memory cache > seed, fetching fresh if needed. */
    private suspend fun resolveToken(context: Context, forceRefresh: Boolean = false): String? {
        val stored = context.dataStore[MusixmatchUserTokenKey]
        if (!forceRefresh) {
            if (!stored.isNullOrBlank()) return stored
            cachedToken?.let { return it }
        }

        return tokenMutex.withLock {
            // Double-check after acquiring the lock in case another call already refreshed it.
            if (!forceRefresh) {
                cachedToken?.let { return@withLock it }
            }
            val fresh = fetchFreshToken() ?: SEED_USER_TOKEN
            cachedToken = fresh
            context.dataStore.edit { it[MusixmatchUserTokenKey] = fresh }
            fresh
        }
    }

    /** Format a fractional-seconds timestamp as `MM:SS.mm` for our bracket rich-sync format. */
    private fun Double.toBracketTimestamp(): String {
        val totalCenti = (this * 100).toLong().coerceAtLeast(0)
        val minutes = totalCenti / 6000
        val seconds = (totalCenti % 6000) / 100
        val centis = totalCenti % 100
        return "%02d:%02d.%02d".format(minutes, seconds, centis)
    }

    /**
     * Convert Musixmatch's richsync_body (itself a JSON string, doubly-encoded) into
     * our `[MM:SS.mm]<MM:SS.mm> word <MM:SS.mm> word ...` bracket format, which
     * LyricsUtils.parseRichSyncLyrics already understands for word-by-word highlighting.
     */
    private fun buildRichSyncLrc(richsyncBody: String): String? = runCatching {
        val lines = Json { ignoreUnknownKeys = true }.parseToJsonElement(richsyncBody).jsonArray
        if (lines.isEmpty()) return@runCatching null

        buildString {
            for (lineEl in lines) {
                val line = lineEl.jsonObject
                val ts = line["ts"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: continue
                val words = line["l"]?.jsonArray ?: continue
                if (words.isEmpty()) continue

                append('[').append(ts.toBracketTimestamp()).append(']')
                for (wordEl in words) {
                    val word = wordEl.jsonObject
                    val text = word["c"]?.jsonPrimitive?.contentOrNull ?: continue
                    val offset = word["o"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
                    val wordTime = ts + offset
                    append('<').append(wordTime.toBracketTimestamp()).append('>').append(text).append(' ')
                }
                // trailing end-of-line timestamp so the last word gets a proper end time
                val te = line["te"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                if (te != null) append('<').append(te.toBracketTimestamp()).append('>')
                append('\n')
            }
        }.trim().ifBlank { null }
    }.getOrElse { e ->
        Timber.tag(TAG).e(e, "Failed to parse richsync_body")
        null
    }

    /** Fetch word-by-word richsync as its own call — Musixmatch does NOT include this in
     *  the macro.subtitles.get response regardless of namespace; it must be requested
     *  separately using the numeric track_id resolved by matcher.track.get. */
    private suspend fun richsyncGet(trackId: Long, duration: Int, token: String): String? = runCatching {
        val response = client.get("/ws/1.1/track.richsync.get") {
            parameter("app_id", APP_ID)
            parameter("usertoken", token)
            parameter("track_id", trackId)
            parameter("subtitle_format", "mxm")
            if (duration > 0) parameter("f_subtitle_length", duration)
        }.body<JsonObject>()

        val status = response.statusCode()
        if (status != 200) {
            Timber.tag(TAG).d("track.richsync.get returned status $status")
            return@runCatching null
        }

        val richsyncBody = response.messageBody()?.get("richsync")?.jsonObject?.get("richsync_body")?.jsonPrimitive?.contentOrNull
        richsyncBody?.let { buildRichSyncLrc(it) }
    }.getOrElse { e ->
        Timber.tag(TAG).e(e, "Exception during richsyncGet")
        null
    }

    private suspend fun subtitlesGet(
        artist: String,
        title: String,
        duration: Int,
        token: String,
        forceLineSynced: Boolean,
    ): Pair<String?, Boolean> {
        // Returns (lyrics, wasUnauthorized)
        return runCatching {
            val response = client.get("/ws/1.1/macro.subtitles.get") {
                parameter("format", "json")
                parameter("namespace", "lyrics_richsynched")
                parameter("subtitle_format", "lrc")
                parameter("app_id", APP_ID)
                parameter("usertoken", token)
                parameter("q_artist", artist)
                parameter("q_track", title)
                if (duration > 0) parameter("q_duration", duration)
                parameter("f_subtitle_length", if (duration > 0) duration else 0)
            }.body<JsonObject>()

            val topStatus = response.statusCode()
            if (topStatus == 401) {
                Timber.tag(TAG).w("macro.subtitles.get: token rejected (401)")
                return@runCatching null to true
            }

            val body = response.messageBody() ?: return@runCatching null to false
            val macroCalls = body["macro_calls"]?.jsonObject ?: return@runCatching null to false

            // Richsync is never present in this macro response — pull the resolved
            // track_id from matcher.track.get and fire a dedicated richsync request,
            // unless the user has forced line-synced only (richsync word timing can
            // be inaccurate/jittery on some tracks even when the line sync is solid).
            val matcherBody = macroCalls["matcher.track.get"]?.jsonObject?.messageBody()
            val matcherStatus = macroCalls["matcher.track.get"]?.jsonObject?.statusCode()
            val trackInfo = matcherBody?.get("track")?.jsonObject
            val trackId = trackInfo?.get("track_id")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            val hasRichsync = trackInfo?.get("has_richsync")?.jsonPrimitive?.contentOrNull?.toIntOrNull() == 1

            Timber.tag(TAG).d("matcher status=$matcherStatus trackId=$trackId hasRichsync=$hasRichsync forceLineSynced=$forceLineSynced")

            if (!forceLineSynced && matcherStatus == 200 && trackId != null && hasRichsync) {
                val richsync = richsyncGet(trackId, duration, token)
                if (!richsync.isNullOrBlank()) {
                    Timber.tag(TAG).d("Got word-synced lyrics from track.richsync.get")
                    return@runCatching richsync to false
                }
            }

            // Fall back to line-synced subtitle
            val subtitleGetBody = macroCalls["track.subtitles.get"]?.jsonObject?.messageBody()
            val subtitleStatus = macroCalls["track.subtitles.get"]?.jsonObject?.statusCode()
            if (subtitleStatus == 200) {
                val subtitleList = subtitleGetBody?.get("subtitle_list")?.jsonArray
                val firstSubtitle = subtitleList?.firstOrNull()?.jsonObject
                    ?.get("message")?.jsonObject?.get("body")?.jsonObject
                    ?.get("subtitle")?.jsonObject
                val lrc = firstSubtitle?.get("subtitle_body")?.jsonPrimitive?.contentOrNull
                if (!lrc.isNullOrBlank()) {
                    Timber.tag(TAG).d("Got synced lyrics from track.subtitles.get")
                    return@runCatching lrc to false
                }
            }

            // Fall back to plain lyrics from the same macro response
            val lyricsGetBody = macroCalls["track.lyrics.get"]?.jsonObject?.messageBody()
            val lyricsStatus = macroCalls["track.lyrics.get"]?.jsonObject?.statusCode()
            if (lyricsStatus == 200) {
                val plain = lyricsGetBody?.get("lyrics")?.jsonObject?.get("lyrics_body")?.jsonPrimitive?.contentOrNull
                if (!plain.isNullOrBlank()) {
                    Timber.tag(TAG).d("Got plain lyrics fallback from track.lyrics.get")
                    return@runCatching plain to false
                }
            }

            null to false
        }.getOrElse { e ->
            Timber.tag(TAG).e(e, "Exception during subtitlesGet")
            null to false
        }
    }

    override suspend fun getLyrics(
        context: Context,
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<String> {
        Timber.tag(TAG).d("getLyrics called: title='$title', artist='$artist', duration=$duration")

        return try {
            var token = resolveToken(context)
                ?: return Result.failure(IllegalStateException("Could not obtain a Musixmatch usertoken"))

            val forceLineSynced = context.dataStore[MusixmatchForceLineSyncedKey] ?: false
            var (lyrics, unauthorized) = subtitlesGet(artist, title, duration, token, forceLineSynced)

            if (unauthorized) {
                Timber.tag(TAG).d("Refreshing Musixmatch usertoken after 401")
                token = resolveToken(context, forceRefresh = true)
                    ?: return Result.failure(IllegalStateException("Could not refresh Musixmatch usertoken"))
                val retry = subtitlesGet(artist, title, duration, token, forceLineSynced)
                lyrics = retry.first
            }

            if (lyrics.isNullOrBlank()) {
                throw IllegalStateException("Lyrics unavailable")
            }

            Timber.tag(TAG).i("Success! Got ${lyrics.length} chars of lyrics")
            Result.success(lyrics)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to get lyrics")
            Result.failure(e)
        }
    }
}

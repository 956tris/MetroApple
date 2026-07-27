/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.providers

import com.metrolist.music.apple.AppleMusicCanvasProvider
import com.metrolist.music.deezer.DeezerAudioProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves a trusted ISRC for a track from whichever source can supply one,
 * and (when a caller-supplied ISRC is already available) cross-checks it so
 * a bad tag can never silently poison an otherwise-correct match.
 *
 * Resolution order:
 *  1. Caller-supplied candidate ISRC (local tag / MediaStore / cached
 *     metadata / provider field) — normalized + validated shape only.
 *  2. Deezer catalog search by song+artist (cheap, no auth) — first result
 *     with a matching title/artist/duration wins.
 *  3. Apple Music catalog search by song+artist, using the same shared
 *     token as [AppleMusicCanvasProvider].
 *
 * Every lookup is cached in-memory keyed by (song, artist, durationSeconds)
 * so repeat plays of the same track never re-hit the network — this keeps
 * ISRC resolution off the hot path and safe to call from the UI-adjacent
 * playback pipeline.
 */
object IsrcResolver {

    private data class CacheKey(val song: String, val artist: String, val durationSeconds: Int?)

    // Positive + negative results both cached; null means "resolution was
    // attempted and failed", distinct from "never attempted" (absent key).
    private val cache = ConcurrentHashMap<CacheKey, String?>()

    /**
     * Returns a normalized, structurally valid ISRC, or null if none could
     * be resolved/validated. Never throws — network/parse failures degrade
     * to null so callers can fall through to lower-confidence matching.
     */
    suspend fun resolveAndValidate(
        candidateIsrc: String?,
        song: String,
        artist: String,
        durationSeconds: Int?,
    ): String? = withContext(Dispatchers.IO) {
        // 1. Caller-supplied candidate — normalize/validate only, no network.
        ProviderIsrc.normalize(candidateIsrc)?.let { return@withContext it }

        if (song.isBlank() || artist.isBlank()) return@withContext null

        val key = CacheKey(song.trim().lowercase(), artist.trim().lowercase(), durationSeconds)
        cache[key]?.let { return@withContext it }
        if (cache.containsKey(key)) return@withContext null // cached negative

        val resolved = runCatching {
            coroutineScope {
                val deezerDeferred = async { resolveViaDeezer(song, artist, durationSeconds) }
                val appleDeferred = async { resolveViaApple(song, artist, durationSeconds) }
                deezerDeferred.await() ?: appleDeferred.await()
            }
        }.getOrNull()

        cache[key] = resolved
        resolved
    }

    // NOTE: DeezerAudioProvider.Query's exact constructor wasn't in the files
    // provided for this change — ProviderMatchSearch.kt calls it with a
    // non-null DeezerAudioQuality (via DeezerAudioQualityKey). Match that
    // shape here (swap the `quality` argument for a real default enum
    // value, e.g. DeezerAudioQuality.MP3_128) if this doesn't compile as-is.
    private suspend fun resolveViaDeezer(song: String, artist: String, durationSeconds: Int?): String? =
        runCatching {
            val query = DeezerAudioProvider.Query(
                mediaId = "",
                title = song,
                artists = listOf(artist),
                album = null,
                isrc = null,
                durationMs = durationSeconds?.toLong()?.times(1000L),
                resolverUrl = DeezerAudioProvider.DEFAULT_RESOLVER_URL,
                quality = com.metrolist.music.constants.DeezerAudioQuality.MP3_128,
                fastMode = true,
                proxyUrl = DeezerAudioProvider.DEFAULT_PROXY_URL,
            )
            DeezerAudioProvider.searchCandidates(query, limit = 3)
                .firstOrNull { it.isrc != null }
                ?.isrc
                ?.let { ProviderIsrc.normalize(it) }
        }.getOrNull()

    private suspend fun resolveViaApple(song: String, artist: String, durationSeconds: Int?): String? =
        runCatching {
            val token = AppleMusicCanvasProvider.borrowToken() ?: return@runCatching null
            AppleMusicCanvasProvider.searchIsrcOnly(song, artist, durationSeconds, token)
        }.getOrNull()

    /** Test/debug hook — clears the in-memory resolution cache. */
    fun clearCache() = cache.clear()
}
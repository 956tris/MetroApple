/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.apple

import java.util.concurrent.ConcurrentHashMap

/** How a [CanvasMatchEntry] was matched to its underlying track. */
enum class CanvasMatchTier(val baseConfidence: Int) {
    ISRC_EXACT(100),
    APPLE_CATALOG_ID(90),
    ALBUM_ARTIST_TITLE(75),
    FUZZY(50),
}

/**
 * A single resolved Canvas record. Distinct from the raw AMP response —
 * this is the durable, cacheable unit the rest of the app should key off.
 */
data class CanvasMatchEntry(
    val isrc: String?,
    val appleCatalogId: String?,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long?,
    val sourceUrl: String,
    val matchTier: CanvasMatchTier,
    val confidence: Int,
    val lastMatchedAtMs: Long,
)

/**
 * In-memory Canvas index keyed for O(1) ISRC lookup, with a secondary
 * (song, artist) index for the pre-ISRC fallback path. This sits in front
 * of / alongside [AppleMusicCanvasProvider]'s existing cache and gives the
 * rest of the app a single place to ask "do we already know the canvas for
 * this ISRC" without re-deriving cache keys.
 *
 * A lower-confidence entry (e.g. FUZZY) is never allowed to overwrite an
 * existing higher-confidence entry for the same ISRC — see [put].
 */
object CanvasIndex {

    private val byIsrc = ConcurrentHashMap<String, CanvasMatchEntry>()
    private val bySongArtist = ConcurrentHashMap<String, CanvasMatchEntry>()

    /** O(1) lookup by normalized ISRC. */
    fun getByIsrc(isrc: String): CanvasMatchEntry? = byIsrc[isrc]

    /** Fallback lookup when no ISRC is available. */
    fun getBySongArtist(song: String, artist: String): CanvasMatchEntry? =
        bySongArtist[songArtistKey(song, artist)]

    /**
     * Stores [entry], refusing to let a lower-confidence match clobber a
     * higher-confidence one already on record for the same ISRC. This is
     * the enforcement point for "never allow a lower-confidence metadata
     * match to override a valid ISRC match".
     */
    fun put(entry: CanvasMatchEntry) {
        if (entry.isrc != null) {
            val existing = byIsrc[entry.isrc]
            if (existing == null || entry.confidence >= existing.confidence) {
                byIsrc[entry.isrc] = entry
            }
        } else {
            val key = songArtistKey(entry.title, entry.artist)
            val existing = bySongArtist[key]
            if (existing == null || entry.confidence >= existing.confidence) {
                bySongArtist[key] = entry
            }
        }
    }

    fun clear() {
        byIsrc.clear()
        bySongArtist.clear()
    }

    private fun songArtistKey(song: String, artist: String): String =
        "${song.trim().lowercase()}\u001F${artist.trim().lowercase()}"
}

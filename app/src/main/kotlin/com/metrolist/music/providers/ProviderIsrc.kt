/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.providers

/**
 * Single source of truth for ISRC parsing, normalization and validation.
 *
 * ISRC format: CC-XXX-YY-NNNNN
 *   CC    = 2-letter ISO country code (registrant country)
 *   XXX   = 3-character alphanumeric registrant code
 *   YY    = 2-digit reference year
 *   NNNNN = 5-digit designation code
 * Total: 12 alphanumeric characters once dashes/spaces are stripped.
 *
 * This object is the ONLY place ISRC strings should be cleaned/compared
 * anywhere in the app. Every provider (Tidal, Deezer, Qobuz, Apple, local
 * tags, MediaStore, caches) must funnel its raw ISRC value through
 * [normalize] before it is used as a lookup key or comparison value.
 */
object ProviderIsrc {

    private val ISRC_REGEX = Regex("^[A-Z]{2}[A-Z0-9]{3}\\d{2}\\d{5}$")

    /**
     * Cleans a raw ISRC string:
     *  - uppercases
     *  - strips whitespace, dashes, dots and any other non-alphanumeric noise
     *  - validates the resulting 12-character shape
     *
     * Returns null if the value is blank or does not match a valid ISRC
     * shape after cleaning — callers must never treat a non-null return
     * from this function as anything but a structurally valid ISRC.
     */
    fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.trim().uppercase().replace(Regex("[^A-Z0-9]"), "")
        return cleaned.takeIf { ISRC_REGEX.matches(it) }
    }

    /** True if [raw] normalizes to a structurally valid ISRC. */
    fun isValid(raw: String?): Boolean = normalize(raw) != null

    /**
     * Extracts + normalizes the first valid ISRC found across an arbitrary
     * set of candidate sources (local tag, MediaStore column, cached
     * metadata, provider response field, embedded id string, etc). Callers
     * pass candidates in priority order — the first one that normalizes
     * successfully wins.
     *
     * This replaces ad-hoc "pull an isrc out of the media id" logic that
     * previously lived inline in each provider query builder.
     */
    fun firstOf(vararg candidates: String?): String? {
        for (candidate in candidates) {
            normalize(candidate)?.let { return it }
        }
        return null
    }

    /** Convenience for a single nullable source (kept for call-site brevity). */
    fun firstOf(candidate: String?): String? = normalize(candidate)

    /** Registrant country code embedded in the ISRC, e.g. "US", "GB". */
    fun countryCode(isrc: String): String? = normalize(isrc)?.substring(0, 2)

    /** 2-digit reference year embedded in the ISRC (e.g. "23" for 2023). */
    fun referenceYear(isrc: String): String? = normalize(isrc)?.substring(5, 7)

    /**
     * True if [a] and [b] are the same ISRC after normalization. Use this
     * instead of raw string equality anywhere ISRCs are compared, since one
     * side may still carry formatting (dashes, lowercase, whitespace).
     */
    fun equal(a: String?, b: String?): Boolean {
        val na = normalize(a) ?: return false
        val nb = normalize(b) ?: return false
        return na == nb
    }
}
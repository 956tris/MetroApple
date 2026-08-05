/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils.mix

/**
 * Harmonic-mixing helpers shared by the MetroMix studio preview and the
 * live Automix engine in MusicService, so the waveform editor and the
 * actual playback behavior never disagree about key compatibility.
 */

/** How well two Camelot-wheel keys mix together, from best to worst. */
enum class KeyCompatibility {
    /** Identical key. Longest, widest blends are safe. */
    PERFECT,

    /** Adjacent wheel position (+-1) or relative major/minor (same number, other letter). */
    COMPATIBLE,

    /** Perfect fourth/fifth (+-7, wraps mod 12) - usable but noticeably brighter/darker. */
    ENERGY_SHIFT,

    /** Anything else. Short, EQ-separated blends recommended. */
    CLASHING,

    /** One or both tracks have no resolved key. */
    UNKNOWN,
}

data class HarmonicMixHint(
    val compatibility: KeyCompatibility,
    // Multiplies the base Automix blend duration. Compatible keys can sustain
    // a long, wide blend; clashing keys should get in and out quickly before
    // the dissonance is audible.
    val durationScale: Float,
    // How hard to duck the outgoing track's low end while the two overlap.
    // Clashing keys need more separation to stay listenable.
    val bassSeparation: Float,
)

/**
 * Parses a Camelot code like "8A" / "11B" into (number 1..12, minor: Boolean).
 * Returns null for anything that isn't already a valid Camelot code - run
 * musical key strings (e.g. "F#m") through toCamelotKey() first.
 */
private fun parseCamelot(code: String): Pair<Int, Boolean>? {
    val match = Regex("""^(\d{1,2})([AB])$""").matchEntire(code.trim().uppercase()) ?: return null
    val number = match.groupValues[1].toIntOrNull() ?: return null
    if (number !in 1..12) return null
    return number to (match.groupValues[2] == "A")
}

fun camelotCompatibility(
    keyA: String?,
    keyB: String?,
): KeyCompatibility {
    val a = keyA?.let(::parseCamelot) ?: return KeyCompatibility.UNKNOWN
    val b = keyB?.let(::parseCamelot) ?: return KeyCompatibility.UNKNOWN
    val (numberA, minorA) = a
    val (numberB, minorB) = b

    if (numberA == numberB && minorA == minorB) return KeyCompatibility.PERFECT

    val delta = ((numberA - numberB + 12) % 12).let { minOf(it, 12 - it) }
    return when {
        delta == 0 && minorA != minorB -> KeyCompatibility.COMPATIBLE // relative major/minor
        delta == 1 -> KeyCompatibility.COMPATIBLE // adjacent on the wheel
        delta == 7 -> KeyCompatibility.ENERGY_SHIFT // perfect 4th/5th jump
        else -> KeyCompatibility.CLASHING
    }
}

fun harmonicMixHint(
    keyA: String?,
    keyB: String?,
): HarmonicMixHint {
    val compatibility = camelotCompatibility(keyA, keyB)
    return when (compatibility) {
        KeyCompatibility.PERFECT -> HarmonicMixHint(compatibility, durationScale = 1.25f, bassSeparation = 0.08f)
        KeyCompatibility.COMPATIBLE -> HarmonicMixHint(compatibility, durationScale = 1.1f, bassSeparation = 0.12f)
        KeyCompatibility.ENERGY_SHIFT -> HarmonicMixHint(compatibility, durationScale = 0.95f, bassSeparation = 0.16f)
        KeyCompatibility.CLASHING -> HarmonicMixHint(compatibility, durationScale = 0.75f, bassSeparation = 0.22f)
        KeyCompatibility.UNKNOWN -> HarmonicMixHint(compatibility, durationScale = 1f, bassSeparation = 0.12f)
    }
}

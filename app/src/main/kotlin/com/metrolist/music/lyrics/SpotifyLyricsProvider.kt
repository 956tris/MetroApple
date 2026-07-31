/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import android.content.Context
import com.metrolist.music.constants.EnableSpotifyLyricsKey
import com.metrolist.music.constants.SpotifyCookieKey
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import com.metrolist.music.utils.spotify.SpotifyCanvasClient
import com.metrolist.music.utils.spotify.isSpotifyCookieConfigured

object SpotifyLyricsProvider : LyricsProvider {
    override val name = "Spotify"

    override fun isEnabled(context: Context): Boolean =
        (context.dataStore.get(EnableSpotifyLyricsKey, true)) &&
            isSpotifyCookieConfigured(context.dataStore.get(SpotifyCookieKey, ""))

    override suspend fun getLyrics(
        context: Context,
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<String> {
        val cookie = context.dataStore.get(SpotifyCookieKey, "")
        if (!isSpotifyCookieConfigured(cookie)) {
            return Result.failure(IllegalStateException("Spotify cookie not configured"))
        }
        val result =
            SpotifyCanvasClient.resolveLyrics(
                title = title,
                artist = artist,
                durationSec = duration,
                cookie = cookie,
            ) ?: return Result.failure(NoSuchElementException("No Spotify lyrics found for $title - $artist"))

        return Result.success(result.lrc)
    }
}

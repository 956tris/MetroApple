/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import android.content.Context
import com.metrolist.paxsenix.Paxsenix
import com.metrolist.music.constants.EnablePaxsenixAppleMusicKey
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import timber.log.Timber

/** Apple Music lyrics via Paxsenix, as an independent, individually toggleable provider. */
object PaxsenixAppleMusicLyricsProvider : LyricsProvider {
    private const val TAG = "PaxsenixAppleMusicProvider"

    override val name = "PaxsenixAppleMusic"

    override fun isEnabled(context: Context): Boolean = context.dataStore[EnablePaxsenixAppleMusicKey] ?: true

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
            Paxsenix.init(context)
            val result = Paxsenix.getAppleMusicLyrics(title, artist, duration, album)

            result.onSuccess { lyrics ->
                Timber.tag(TAG).i("Success! Got ${lyrics.length} chars of lyrics")
            }.onFailure { e ->
                Timber.tag(TAG).e(e, "Failed to get lyrics")
            }

            result
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Exception in getLyrics")
            Result.failure(e)
        }
    }
}

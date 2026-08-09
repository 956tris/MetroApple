package com.metrolist.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExperimentalPlaybackCachePolicyTest {
    @Test
    fun cacheIsClearedByDefaultAfterQualityChange() {
        assertEquals(true, ExperimentalPlaybackCachePolicy.shouldClearCacheOnQualityChange(false))
    }

    @Test
    fun cacheIsKeptWhenExperimentIsEnabled() {
        assertEquals(false, ExperimentalPlaybackCachePolicy.shouldClearCacheOnQualityChange(true))
    }

    @Test
    fun historySyncOnlyAcceptsYouTubeVideoIds() {
        assertEquals("qoZxk5qc1YU", YouTubeMusicHistorySyncPolicy.videoIdOrNull("qoZxk5qc1YU"))
        assertNull(YouTubeMusicHistorySyncPolicy.videoIdOrNull("deezer:12345"))
    }

    @Test
    fun historySyncWaitsForHalfOfShortSongsOrThirtySecondsForLongSongs() {
        assertEquals(10_000L, YouTubeMusicHistorySyncPolicy.delayBeforeReportMs(20_000L))
        assertEquals(30_000L, YouTubeMusicHistorySyncPolicy.delayBeforeReportMs(240_000L))
    }
}

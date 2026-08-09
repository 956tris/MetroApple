package com.metrolist.music.providers

import com.metrolist.music.constants.AudioProviderOrderItem
import org.junit.Assert.assertEquals
import org.junit.Test

class ExperimentalPlaybackPolicyTest {
    @Test
    fun `keeps provider order unchanged when deezer first is disabled`() {
        val providers = listOf(
            AudioProviderOrderItem.SOUNDCLOUD,
            AudioProviderOrderItem.DEEZER,
            AudioProviderOrderItem.YOUTUBE_MUSIC,
        )

        assertEquals(providers, ExperimentalPlaybackPolicy.prioritizeDeezer(providers, enabled = false))
    }

    @Test
    fun `moves deezer to the first position without duplication`() {
        val providers = listOf(
            AudioProviderOrderItem.SOUNDCLOUD,
            AudioProviderOrderItem.DEEZER,
            AudioProviderOrderItem.YOUTUBE_MUSIC,
        )

        assertEquals(
            listOf(
                AudioProviderOrderItem.DEEZER,
                AudioProviderOrderItem.SOUNDCLOUD,
                AudioProviderOrderItem.YOUTUBE_MUSIC,
            ),
            ExperimentalPlaybackPolicy.prioritizeDeezer(providers, enabled = true),
        )
    }

    @Test
    fun `uses a fallback resolver only when enabled`() {
        assertEquals(
            listOf("https://primary.example"),
            ExperimentalPlaybackPolicy.deezerResolverUrls(
                primary = "https://primary.example",
                fallback = "https://fallback.example",
                enabled = false,
            ),
        )
        assertEquals(
            listOf("https://primary.example", "https://fallback.example"),
            ExperimentalPlaybackPolicy.deezerResolverUrls(
                primary = "https://primary.example",
                fallback = "https://fallback.example",
                enabled = true,
            ),
        )
    }
}

package com.metrolist.paxsenix

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import timber.log.Timber

/** Musixmatch lyrics path — paxsenix's /musixmatch/lyrics, direct title/artist/duration lookup, no search step. */
internal object MusixmatchLyrics {
    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var httpClient: HttpClient

    fun init(client: HttpClient) {
        httpClient = client
    }

    suspend fun fetchLyrics(
        title: String,
        artist: String,
        duration: Int,
    ): Result<String> = runCatching {
        Timber.d("Fetching Musixmatch lyrics for '$title' by '$artist' (${duration}s)")

        val response = httpClient.get("/musixmatch/lyrics") {
            parameter("t", title)
            parameter("a", artist)
            parameter("d", duration)
            parameter("format", "lrc")
            parameter("enchanted", true)
            parameter("v", 2)
        }

        val raw = response.bodyAsText()
        // The endpoint's documented schema returns a bare JSON string; some
        // deployments instead return raw LRC text directly, so handle both.
        val lrc = runCatching { json.decodeFromString<String>(raw) }.getOrDefault(raw)

        if (lrc.isBlank() || lrc.equals("null", ignoreCase = true)) {
            throw IllegalStateException("Empty Musixmatch lyrics response")
        }
        Timber.d("Musixmatch returned ${lrc.length} chars")
        lrc
    }.onFailure { e ->
        Timber.w(e, "Musixmatch lyrics fetch failed for '$title' by '$artist'")
    }
}

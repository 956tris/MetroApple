package com.metrolist.paxsenix

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import timber.log.Timber
import kotlin.math.abs

/**
 * QQ Music lyrics path — paxsenix's /qq/search (title/artist -> songid) then
 * /qq/lyrics (metadata POST -> lyrics).
 *
 * NOTE: the paxsenix docs give no response schema for either endpoint (just
 * "string"). Field names below follow the common public QQ Music API shape
 * (songid/songname/singer[].name/albumname/interval). If real responses use
 * different keys, only qqField/qqLong/qqArtistNames/extractSongList need
 * adjusting.
 */
internal object QQMusicLyrics {
    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var httpClient: HttpClient

    fun init(client: HttpClient) {
        httpClient = client
    }

    suspend fun fetchLyrics(
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ): Result<String> = runCatching {
        val results = search("$title $artist")
        if (results.isEmpty()) throw IllegalStateException("No QQ Music search results for '$title' by '$artist'")

        val cleanedTitle = title.lowercase().trim()
        val scored = results.mapNotNull { song ->
            val songid = song.qqLong("songid", "song_id", "id") ?: return@mapNotNull null
            val songTitle = song.qqField("songname", "name", "title") ?: return@mapNotNull null
            var score = 0
            when {
                songTitle.lowercase().trim() == cleanedTitle -> score += 80
                songTitle.lowercase().contains(cleanedTitle) || cleanedTitle.contains(songTitle.lowercase()) -> score += 40
            }
            song.qqLong("interval", "duration")?.toInt()?.let { d ->
                score += when (abs(d - duration)) {
                    in 0..2 -> 100
                    in 3..5 -> 50
                    in 6..10 -> 10
                    else -> -50
                }
            }
            Triple(songid, song, score)
        }

        val best = scored.maxByOrNull { it.third } ?: throw IllegalStateException("No matching QQ Music track for '$title'")
        val (songid, song, _) = best
        val artistNames = song.qqArtistNames().ifEmpty { listOf(artist) }
        val albumName = song.qqField("albumname", "album", "album_name") ?: album

        lyricsRaw(title, artistNames, albumName, songid, duration).getOrThrow()
    }.onFailure { e ->
        Timber.w(e, "QQ Music lyrics resolution failed for '$title' by '$artist'")
    }

    private suspend fun search(query: String): List<JsonObject> = runCatching {
        val response = httpClient.get("/qq/search") {
            parameter("q", query)
        }
        val raw = response.bodyAsText()
        val decoded = runCatching { json.decodeFromString<String>(raw) }.getOrDefault(raw)
        extractSongList(json.parseToJsonElement(decoded))
    }.onFailure { e ->
        Timber.w(e, "QQ Music search failed for '$query'")
    }.getOrDefault(emptyList())

    private suspend fun lyricsRaw(
        title: String,
        artist: List<String>,
        album: String?,
        songid: Long,
        duration: Int,
    ): Result<String> = runCatching {
        val response = httpClient.post("/qq/lyrics") {
            parameter("v", 2)
            header("Content-Type", ContentType.Application.Json.toString())
            setBody(
                buildJsonObject {
                    put("title", title)
                    putJsonArray("artist") { artist.forEach { add(it) } }
                    album?.let { put("album", it) }
                    put("songid", songid)
                    put("duration", duration)
                }.toString(),
            )
        }
        val raw = response.bodyAsText()
        val lrc = runCatching { json.decodeFromString<String>(raw) }.getOrDefault(raw)
        if (lrc.isBlank() || lrc.equals("null", ignoreCase = true)) {
            throw IllegalStateException("Empty QQ Music lyrics response")
        }
        lrc
    }.onFailure { e ->
        Timber.w(e, "QQ Music lyrics fetch failed for songid=$songid")
    }

    private fun JsonObject.qqField(vararg keys: String): String? {
        for (k in keys) {
            (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    private fun JsonObject.qqLong(vararg keys: String): Long? {
        for (k in keys) {
            (this[k] as? JsonPrimitive)?.longOrNull?.let { return it }
        }
        return null
    }

    private fun JsonObject.qqArtistNames(): List<String> {
        for (k in listOf("singer", "artist", "artists")) {
            when (val v = this[k]) {
                is JsonArray -> return v.mapNotNull { el ->
                    (el as? JsonObject)?.qqField("name", "title") ?: (el as? JsonPrimitive)?.contentOrNull
                }
                is JsonPrimitive -> v.contentOrNull?.let { return listOf(it) }
                else -> {}
            }
        }
        return emptyList()
    }

    private fun extractSongList(element: JsonElement): List<JsonObject> {
        fun JsonObject.arr(key: String) = this[key] as? JsonArray
        fun JsonObject.objAt(key: String) = this[key] as? JsonObject

        (element as? JsonArray)?.let { return it.mapNotNull { it as? JsonObject } }
        val obj = element as? JsonObject ?: return emptyList()
        obj.arr("songs")?.let { return it.mapNotNull { it as? JsonObject } }
        obj.arr("list")?.let { return it.mapNotNull { it as? JsonObject } }
        obj.objAt("data")?.let { data ->
            data.arr("songs")?.let { return it.mapNotNull { it as? JsonObject } }
            data.arr("list")?.let { return it.mapNotNull { it as? JsonObject } }
            data.objAt("song")?.arr("list")?.let { return it.mapNotNull { it as? JsonObject } }
        }
        obj.objAt("result")?.let { result ->
            result.arr("songs")?.let { return it.mapNotNull { it as? JsonObject } }
            result.arr("list")?.let { return it.mapNotNull { it as? JsonObject } }
        }
        return emptyList()
    }
}

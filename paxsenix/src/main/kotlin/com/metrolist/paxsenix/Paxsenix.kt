package com.metrolist.paxsenix

import android.content.Context
import com.metrolist.paxsenix.models.SearchResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import timber.log.Timber

object Paxsenix {
    @Volatile
    private var client: HttpClient? = null
    private var appVersion: String = "Unknown"

    fun init(context: Context) {
        if (client != null) return // Already initialized

        synchronized(this) {
            if (client != null) return

            appVersion = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
                    ?: "Unknown"
            } catch (e: Exception) {
                Timber.e(e, "Failed to get app version")
                "Unknown"
            }

            Timber.d("Initializing Paxsenix with version: $appVersion")

            val newClient = HttpClient(CIO) {
                install(HttpTimeout) {
                    requestTimeoutMillis = 15000
                    connectTimeoutMillis = 10000
                }
                install(ContentNegotiation) {
                    json(
                        Json {
                            isLenient = true
                            ignoreUnknownKeys = true
                        },
                    )
                }

                defaultRequest {
                    url("https://lyrics.paxsenix.org")
                    header("User-Agent", "Metrolist/$appVersion")
                }

                expectSuccess = true
            }

            client = newClient
            AppleMusicLyrics.init(newClient)
            MusixmatchLyrics.init(newClient)
            QQMusicLyrics.init(newClient)

            Timber.d("Paxsenix HTTP client initialized")
        }
    }

    private val titleCleanupPatterns = listOf(
        Regex("""\s*\(.*?(official|video|audio|lyrics|lyric|visualizer|hd|hq|4k|remaster|remix|live|acoustic|version|edit|extended|radio|clean|explicit).*?\)""", RegexOption.IGNORE_CASE),
        Regex("""\s*\[.*?(official|video|audio|lyrics|lyric|visualizer|hd|hq|4k|remaster|remix|live|acoustic|version|edit|extended|radio|clean|explicit).*?\]""", RegexOption.IGNORE_CASE),
        Regex("""\s*【.*?】"""),
        Regex("""\s*\|.*$"""),
        Regex("""\s*-\s*(official|video|audio|lyrics|lyric|visualizer).*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*\(feat\..*?\)""", RegexOption.IGNORE_CASE),
        Regex("""\s*\(ft\..*?\)""", RegexOption.IGNORE_CASE),
        Regex("""\s*feat\..*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*ft\..*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*\([^)]*\d{4}[^)]*\)""", RegexOption.IGNORE_CASE),
    )

    private val artistSeparators = listOf(" & ", " and ", ", ", " x ", " X ", " feat. ", " feat ", " ft. ", " ft ", " featuring ", " with ")

    private fun cleanTitle(title: String): String {
        var cleaned = title.trim()
        for (pattern in titleCleanupPatterns) {
            cleaned = cleaned.replace(pattern, "")
        }
        return cleaned.trim()
    }

    private fun cleanArtist(artist: String): String {
        var cleaned = artist.trim()
        for (separator in artistSeparators) {
            if (cleaned.contains(separator, ignoreCase = true)) {
                cleaned = cleaned.split(separator, ignoreCase = true, limit = 2)[0]
                break
            }
        }
        return cleaned.trim()
    }

    private fun getQuality(lrc: String): Int {
        if (lrc.isBlank()) return 0
        val hasWordTimings = (lrc.contains("<") && lrc.contains(">") && (lrc.contains("|") || lrc.contains(":"))) ||
                lrc.contains(Regex("<\\d{1,2}:\\d{2}\\.\\d{2,3}>"))

        if (hasWordTimings) return 3

        val hasLineTimings = lrc.contains(Regex("\\[\\d\\d:\\d\\d\\.\\d{2,3}\\]")) ||
                lrc.contains(Regex("^\\[bg:.*\\]", RegexOption.MULTILINE))

        if (hasLineTimings) return 2
        return 1
    }

    suspend fun getLyrics(
        title: String,
        artist: String,
        duration: Int,
        album: String? = null,
    ): Result<String> = runCatching {
        val cleanedTitle = cleanTitle(title)
        val cleanedArtist = cleanArtist(artist)

        Timber.d("getLyrics called: title='$title', artist='$artist', duration=$duration, album=$album")
        Timber.d("Cleaned: title='$cleanedTitle', artist='$cleanedArtist'")

        val searchQueries = buildList {
            add("$cleanedTitle $cleanedArtist")
            add(cleanedTitle)
            if (!album.isNullOrBlank()) {
                add("$cleanedTitle $cleanedArtist $album")
            }
        }

        var allResults: List<Pair<SearchResult, Double>> = emptyList()

        for (query in searchQueries) {
            if (allResults.isEmpty()) {
                Timber.d("Trying search query: $query")
                val searchResults = AppleMusicLyrics.search(query)

                if (searchResults.isNotEmpty()) {
                    allResults = AppleMusicLyrics.scoreAndFilterResults(searchResults, title, artist, duration, ::cleanArtist)
                }
            }
        }

        var bestLyrics: String? = null
        var bestQuality = 0

        if (allResults.isEmpty()) {
            Timber.w("No tracks found on Apple Music for any query")
        } else {
            for ((result, score) in allResults.take(10)) {
                Timber.d("Trying: ${result.displayName} (ID: ${result.id}, dur: ${result.duration}, score: $score)")
                val lrc = AppleMusicLyrics.fetchLyricsForTrack(result.id).getOrNull() ?: continue
                if (lrc.isEmpty()) continue

                val quality = getQuality(lrc)
                Timber.d("Got lyrics, quality=$quality")

                if (quality > bestQuality) {
                    bestQuality = quality
                    bestLyrics = lrc
                }

                if (bestQuality == 3) break // Word-synced is best we can get
            }
        }

        bestLyrics?.let {
            if (bestQuality < 3) {
                MusixmatchLyrics.fetchLyrics(cleanedTitle, cleanedArtist, duration).getOrNull()?.let { mxLrc ->
                    val mxQuality = getQuality(mxLrc)
                    if (mxQuality > bestQuality) {
                        bestQuality = mxQuality
                        bestLyrics = mxLrc
                    }
                }
            }
            if (bestQuality < 3) {
                QQMusicLyrics.fetchLyrics(cleanedTitle, cleanedArtist, album, duration).getOrNull()?.let { qqLrc ->
                    val qqQuality = getQuality(qqLrc)
                    if (qqQuality > bestQuality) {
                        bestQuality = qqQuality
                        bestLyrics = qqLrc
                    }
                }
            }
            Timber.d("Using Paxsenix lyrics with quality $bestQuality (respects provider order)")
            return Result.success(bestLyrics!!)
        }

        MusixmatchLyrics.fetchLyrics(cleanedTitle, cleanedArtist, duration).getOrNull()?.let { mxLrc ->
            Timber.d("Using Musixmatch lyrics (Apple Music had none)")
            return Result.success(mxLrc)
        }

        QQMusicLyrics.fetchLyrics(cleanedTitle, cleanedArtist, album, duration).getOrNull()?.let { qqLrc ->
            Timber.d("Using QQ Music lyrics (Apple Music/Musixmatch had none)")
            return Result.success(qqLrc)
        }

        Timber.w("No lyrics content from Paxsenix for matched tracks")
        return Result.failure(IllegalStateException("No lyrics available from Paxsenix"))
    }

    suspend fun getAllLyrics(
        title: String,
        artist: String,
        duration: Int,
        album: String? = null,
        callback: (String) -> Unit,
    ) {
        val cleanedTitle = cleanTitle(title)
        val cleanedArtist = cleanArtist(artist)

        val searchQueries = listOf(
            "$cleanedTitle $cleanedArtist",
            cleanedTitle,
        )

        var scoredResults: List<Pair<SearchResult, Double>> = emptyList()
        searchLoop@ for (query in searchQueries) {
            val results = AppleMusicLyrics.search(query)
            if (results.isEmpty()) continue

            val filtered = AppleMusicLyrics.scoreAndFilterResults(results, title, artist, duration, ::cleanArtist)
            if (filtered.isNotEmpty()) {
                scoredResults = filtered
                break@searchLoop
            }
        }

        val collectedLyrics = mutableListOf<Pair<String, Int>>()

        for ((result, _) in scoredResults.take(5)) {
            Timber.d("Trying lyrics for: ${result.displayName}")
            val lrc = AppleMusicLyrics.fetchLyricsForTrack(result.id).getOrNull() ?: continue
            if (lrc.isNotEmpty()) {
                val quality = getQuality(lrc)
                collectedLyrics.add(lrc to quality)
                if (quality == 3) break //
            }
        }

        // Sort by quality descending and callback
        collectedLyrics.sortedByDescending { it.second }.forEach { (lrc, _) ->
            callback(lrc)
        }

        MusixmatchLyrics.fetchLyrics(cleanedTitle, cleanedArtist, duration).getOrNull()?.let { mxLrc ->
            callback(mxLrc)
        }

        QQMusicLyrics.fetchLyrics(cleanedTitle, cleanedArtist, album, duration).getOrNull()?.let { qqLrc ->
            callback(qqLrc)
        }
    }
}

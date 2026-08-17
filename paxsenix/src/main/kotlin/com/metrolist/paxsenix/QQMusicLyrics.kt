package com.metrolist.paxsenix

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import timber.log.Timber
import java.util.zip.Inflater
import kotlin.math.abs

/**
 * QQ Music lyrics path — QQ's own public (undocumented) web API, since the
 * paxsenix.org "/qq/" endpoints are dead. Pipeline (ported from the QRCD
 * reference tool's decrypt algorithm, itself credited to
 * qwe7989199/Lyric-Importer-for-Aegisub — a purely technical DES/zlib
 * container format, not creative content):
 *
 *   1. Search: c.y.qq.com/soso/fcgi-bin/client_search_cp (title/artist ->
 *      numeric songid + songmid candidates).
 *   2. Download: c.y.qq.com/qqmusic/fcgi-bin/lyric_download.fcg using the
 *      numeric songid (musicid param) -> XML wrapped in HTML comments,
 *      containing a hex-encoded encrypted blob in <content>.
 *   3. Decrypt: triple-DES cascade — decrypt(KEY1) -> encrypt(KEY2) ->
 *      decrypt(KEY3), ECB, no padding, each 16-byte key treated as a 2-key
 *      3DES key (K1||K2||K1 expanded to 24 bytes for javax.crypto).
 *   4. Inflate: the decrypted bytes are zlib-compressed; inflate to get
 *      either a QRC XML blob (<Lyric_1 LyricContent="..."/>) or plain LRC.
 *   5. If QRC, convert its `word(startMs,durMs)` line format into the
 *      app's own rich-sync format (`[MM:SS.mmm]<MM:SS.mmm> word ...`, see
 *      LyricsUtils.RICH_SYNC_*) so word-by-word highlighting works.
 */
internal object QQMusicLyrics {
    private val json = Json { ignoreUnknownKeys = true }

    private val KEY1 = "!@#)(NHLiuy*$%^&".toByteArray(Charsets.ISO_8859_1)
    private val KEY2 = "123ZXC!@#)(*$%^&".toByteArray(Charsets.ISO_8859_1)
    private val KEY3 = "!@#)(*$%^&abcDEF".toByteArray(Charsets.ISO_8859_1)

    private val qrcLineRegex = Regex("""^\[(\d+),(\d+)\](.*)$""")
    private val qrcWordRegex = Regex("""([^(]*)\((\d+),(\d+)\)""")
    private val lyricContentRegex = Regex("""<Lyric_1[^>]*LyricContent="(.*?)"\s*/?>""", RegexOption.DOT_MATCHES_ALL)

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
        Timber.d("Fetching QQ Music lyrics for '$title' by '$artist' (${duration}s)")

        val candidates = search("$title $artist")
        if (candidates.isEmpty()) throw IllegalStateException("No QQ Music search results for '$title' by '$artist'")

        val cleanedTitle = title.lowercase().trim()
        val best = candidates.maxByOrNull { song ->
            var score = 0
            val songTitle = song.qqField("songname", "name")?.lowercase()?.trim()
            when {
                songTitle == null -> {}
                songTitle == cleanedTitle -> score += 80
                songTitle.contains(cleanedTitle) || cleanedTitle.contains(songTitle) -> score += 40
            }
            song.qqLong("interval", "duration")?.let { d ->
                score += when (abs(d - duration)) {
                    in 0..2 -> 100
                    in 3..5 -> 50
                    in 6..10 -> 10
                    else -> -50
                }
            }
            score
        } ?: throw IllegalStateException("No matching QQ Music track for '$title'")

        val songid = best.qqLong("songid", "id")
            ?: throw IllegalStateException("QQ Music result missing songid")

        val decoded = downloadAndDecrypt(songid).getOrThrow()
        toAppLyricsFormat(decoded)
    }.onFailure { e ->
        Timber.w(e, "QQ Music lyrics fetch failed for '$title' by '$artist'")
    }

    private suspend fun search(query: String): List<JsonObject> = runCatching {
        val response = httpClient.get("https://c.y.qq.com/soso/fcgi-bin/client_search_cp") {
            header("Referer", "https://y.qq.com/")
            parameter("w", query)
            parameter("format", "json")
            parameter("p", 1)
            parameter("n", 20)
        }
        val root = json.parseToJsonElement(response.bodyAsText()) as? JsonObject ?: return@runCatching emptyList()
        val data = root["data"] as? JsonObject ?: return@runCatching emptyList()
        val song = data["song"] as? JsonObject ?: return@runCatching emptyList()
        (song["list"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
    }.onFailure { e ->
        Timber.w(e, "QQ Music search failed for '$query'")
    }.getOrDefault(emptyList())

    private suspend fun downloadAndDecrypt(songid: Long): Result<String> = runCatching {
        val response = httpClient.get("https://c.y.qq.com/qqmusic/fcgi-bin/lyric_download.fcg") {
            header("Referer", "https://y.qq.com/")
            parameter("version", "15")
            parameter("miniversion", "82")
            parameter("lrctype", "4")
            parameter("musicid", songid)
        }
        val xml = response.bodyAsText().replace("<!--", "").replace("-->", "")
        val contentBlock = Regex("""<content[^>]*>(.*?)</content>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("No lyric content for songid=$songid")
        // The content block is itself CDATA-wrapped (<![CDATA[<hex>]]>); strip that
        // wrapper explicitly rather than relying on hexToBytes' char filter, since
        // "CDATA" itself contains valid hex letters (C, D, A) that would otherwise
        // get silently spliced into the decrypted byte stream.
        val hex = Regex("""<!\[CDATA\[(.*?)]]>""", RegexOption.DOT_MATCHES_ALL)
            .find(contentBlock)?.groupValues?.get(1)?.trim()
            ?: contentBlock

        val encrypted = hexToBytes(hex)
        val step1 = QQMusicDes.crypt(encrypted, KEY1, decrypt = true)
        val step2 = QQMusicDes.crypt(step1, KEY2, decrypt = false)
        val step3 = QQMusicDes.crypt(step2, KEY3, decrypt = true)
        val inflated = inflate(step3)
        String(inflated, Charsets.UTF_8)
    }.onFailure { e ->
        Timber.w(e, "QQ Music lyric download/decrypt failed for songid=$songid")
    }

    /** Converts either QRC XML (word-timed) or plain LRC into the app's LyricsUtils rich-sync format. */
    private fun toAppLyricsFormat(decoded: String): String {
        val qrcBody = lyricContentRegex.find(decoded)?.groupValues?.get(1) ?: decoded
        if (!qrcBody.contains(Regex("""\(\d+,\d+\)"""))) {
            // Not word-timed QRC (e.g. plain LRC) — pass through as-is.
            return qrcBody
        }

        val out = StringBuilder()
        qrcBody.lines().forEach { rawLine ->
            val m = qrcLineRegex.find(rawLine.trim()) ?: return@forEach
            val lineStartMs = m.groupValues[1].toLongOrNull() ?: return@forEach
            val wordsPart = m.groupValues[3]
            val words = qrcWordRegex.findAll(wordsPart).toList()
            if (words.isEmpty()) return@forEach

            out.append('[').append(formatTime(lineStartMs)).append(']')
            var lastWordEndMs: Long? = null
            words.forEach { w ->
                val text = w.groupValues[1]
                val startMs = w.groupValues[2].toLongOrNull() ?: 0L
                val durMs = w.groupValues[3].toLongOrNull() ?: 0L
                if (text.isNotEmpty()) {
                    out.append('<').append(formatTime(startMs)).append('>').append(text)
                    lastWordEndMs = startMs + durMs
                }
            }
            // QQ's QRC data gives us each word's real (startMs, durMs), so we know
            // the true end time of the last word in the line — emit it as a trailing
            // timestamp instead of letting LyricsUtils interpolate to the *next
            // line's* start time, which stretches the last word across any
            // instrumental gap between lines.
            lastWordEndMs?.let { endMs -> out.append('<').append(formatTime(endMs)).append('>') }
            out.append('\n')
        }
        return out.toString().trimEnd()
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val millis = ms % 1000
        return "%02d:%02d.%03d".format(minutes, seconds, millis)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    /** Single-DES cascade (not real 3DES — see QQMusicDes.kt), matches QQMusicCommon.dll's des/Ddes exactly. */

    private fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(data)
        val out = java.io.ByteArrayOutputStream(data.size * 4)
        val buf = ByteArray(8192)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0) {
                if (inflater.needsInput() || inflater.needsDictionary()) break
            }
            out.write(buf, 0, n)
        }
        inflater.end()
        return out.toByteArray()
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
}

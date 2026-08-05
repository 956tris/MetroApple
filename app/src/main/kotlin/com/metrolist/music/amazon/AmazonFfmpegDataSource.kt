/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.amazon

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.SessionState
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * Streams a CloudFront-hosted encrypted CMAF/MP4 Amazon Music track straight into FFmpeg and
 * exposes the decrypted PCM/WAV output as a Media3 [DataSource], so ExoPlayer can start playback
 * as soon as FFmpeg has produced its first output bytes instead of waiting for
 * [AmazonFfmpegDecryptor]'s full download+decrypt pass (still used for the cache-hit path).
 *
 * FFmpeg does all the real work - CENC parsing, per-fragment IV handling, cenc/cbcs schemes -
 * via its native `-decryption_key` flag, reading the CDN URL as an HTTP input. This class only
 * wires that into ExoPlayer's DataSource contract:
 *  - [open] restarts FFmpeg with `-ss <seconds>` on every seek, since decrypted-PCM byte offsets
 *    don't correspond to encrypted-source byte ranges - timestamp-based restart is the correct
 *    seek model here. Media3's WavExtractor resyncs at the next frame boundary after restart.
 *  - Output is WAV/PCM, not FLAC: FLAC's muxer needs to seek back on close to patch its header,
 *    which a FIFO pipe can't support and fails immediately. WAV tolerates a non-seekable output
 *    (ffmpeg writes a placeholder data-chunk size instead) - but that placeholder is read
 *    completely at face value by Media3's WavHeaderReader with no sanity check, so [read] walks
 *    the actual RIFF chunk structure (fmt chunk length varies - e.g. WAVE_FORMAT_EXTENSIBLE for
 *    24-bit PCM is 40 bytes, not the classic 16) to find the real "data" chunk size field and
 *    rewrite it with a value computed from the known track duration, before any bytes reach
 *    ExoPlayer.
 *  - [read] tees bytes to a scratch file as they stream past (first-open / non-seek only, when
 *    caching is enabled), and on a clean end-of-stream promotes that scratch file into the same
 *    cache slot [AmazonFfmpegDecryptor.prepareStream] uses, so repeat plays hit its cache-hit
 *    path. Currently disabled since it would write PCM bytes into a cache slot the old
 *    file-based FLAC decrypt path expects to contain FLAC.
 */
@OptIn(UnstableApi::class)
class AmazonFfmpegDataSource(
    private val context: Context,
    private val resolved: AmazonAudioProvider.Resolved,
    private val knownDurationMs: Long? = null,
) : BaseDataSource(true) {

    private var session: FFmpegSession? = null
    private var pipePath: String? = null
    private var inputStream: InputStream? = null
    private var opened = false
    private var currentDataSpec: DataSpec? = null

    // Tee-to-cache state for the current open() - only armed for a fresh (non-seeked) read
    // from the start of the track, so a seek or a mid-stream stop never gets cached as if it
    // were the complete file. Currently never armed (see class doc) - kept intact for when
    // streaming-path caching is reintroduced.
    private var teeFile: File? = null
    private var teeOutput: OutputStream? = null
    private var teeFailed = false

    // resolved.bitrate is bits/sec; used only to translate a seek's byte offset into an
    // approximate restart timestamp for FFmpeg's -ss. Exact precision isn't needed - the
    // extractor resyncs to the next frame boundary after restart.
    private val effectiveBitrate: Long
        get() = resolved.bitrate.takeIf { it > 0 }?.toLong() ?: FALLBACK_BITRATE_BPS

    @Volatile
    private var openedStreamHolder: FileInputStream? = null

    // Whether the current open() still needs its WAV header parsed/patched before falling
    // through to plain passthrough reads. False for a mid-file seek restart, whose output has
    // no header to parse at all.
    private var headerPending: Boolean = false
    // Once the header's been parsed and patched, any bytes of it still waiting to be handed to
    // the caller (delivery doesn't necessarily align with the caller's read() buffer sizes).
    private var headerOut: ByteArray? = null
    private var headerOutPos: Int = 0

    override fun open(dataSpec: DataSpec): Long {
        close() // Ensure any previous session/pipe/tee is torn down first.
        currentDataSpec = dataSpec
        transferInitializing(dataSpec)

        val isFreshStart = dataSpec.position <= 0L
        headerPending = isFreshStart
        headerOut = null
        headerOutPos = 0

        val startOffsetS = if (isFreshStart) {
            0.0
        } else {
            dataSpec.position.toDouble() / (effectiveBitrate / 8.0)
        }

        Timber.d(
            "Opening AmazonFfmpegDataSource for %s at byte %d (~%.2fs, bitrate=%d bps)",
            resolved.trackId, dataSpec.position, startOffsetS, effectiveBitrate,
        )

        val key = resolved.decryptionKey ?: throw IOException("Missing decryption key for ${resolved.trackId}")
        val url = resolved.mediaUri

        pipePath = FFmpegKitConfig.registerNewFFmpegPipe(context)
            ?: throw IOException("Failed to register native pipe for ${resolved.trackId}")

        val command = buildString {
            append("-y ")
            append("-user_agent \"").append(AmazonAudioProvider.BROWSER_USER_AGENT).append("\" ")
            // Reasonable network resilience for a live HTTP input FFmpeg is reading directly -
            // there's no local file to fall back to if the CDN stalls.
            append("-reconnect 1 -reconnect_streamed 1 -reconnect_delay_max 4 ")
            if (startOffsetS > 0.1) {
                append("-ss ").append("%.3f".format(startOffsetS)).append(" ")
            }
            append("-decryption_key ").append(key).append(" ")
            append("-i \"").append(url).append("\" ")
            append("-vn ")
            // Not -c copy: WAV can't hold compressed FLAC frames (it expects PCM), and decoding
            // FLAC to PCM here is exact/lossless anyway - FLAC is just compressed PCM, so this
            // loses nothing, it just costs a bit more CPU than a pure remux.
            append("-c:a pcm_s24le ")
            append("-f wav ")
            append("\"").append(pipePath).append("\"")
        }

        Timber.d("Starting FFmpeg for streaming decrypt: %s", command)

        session = FFmpegKit.executeAsync(command) { sess ->
            Timber.d("FFmpeg session for %s finished with rc %s", resolved.trackId, sess.returnCode)
        }

        // Tee-to-cache into AmazonFfmpegDecryptor's slot is intentionally disabled for now - see
        // class doc. Left as a no-op call site (rather than deleted) so re-enabling later is a
        // one-line change once a separate cache namespace/format handoff exists for PCM output.
        if (false && isFreshStart) {
            armTeeToCache()
        }

        // A FIFO's read-open blocks until a writer opens it. If FFmpeg fails before that (bad
        // key, DNS failure, CDN 403, etc.) this would otherwise hang forever - so we open it on
        // a helper thread and bound the wait, checking FFmpeg's own state so we can fail fast
        // with a real error instead of an indefinite ANR-risking block.
        try {
            inputStream = openPipeWithWatchdog(pipePath!!)
            opened = true
            transferStarted(dataSpec)
        } catch (e: Exception) {
            close()
            throw IOException("Failed to open FFmpeg pipe for ${resolved.trackId}: ${e.message}", e)
        }

        return C.LENGTH_UNSET.toLong()
    }

    /**
     * Opens [path] for reading with a bounded wait, polling the FFmpeg session's state so a
     * process that dies before writing anything fails fast instead of blocking the pipe open
     * indefinitely (a plain FileInputStream(path) would otherwise hang until a writer shows up,
     * which never happens if FFmpeg already exited).
     */
    private fun openPipeWithWatchdog(path: String): InputStream {
        openedStreamHolder = null
        val deadline = System.currentTimeMillis() + PIPE_OPEN_TIMEOUT_MS
        var lastError: Exception? = null
        val opener = Thread {
            try {
                openedStreamHolder = FileInputStream(path)
            } catch (e: Exception) {
                lastError = e
            }
        }.apply { isDaemon = true; start() }

        while (System.currentTimeMillis() < deadline) {
            val result = openedStreamHolder
            if (result != null) {
                return result
            }
            val sess = session
            if (sess != null && sess.state == SessionState.COMPLETED) {
                // FFmpeg already finished (almost certainly failed) before ever opening the
                // pipe for writing - no point waiting out the rest of the timeout. Pull its own
                // log output directly (no logcat/adb needed) so the real failure reason surfaces
                // straight into the exception message and shows up in the in-app error dialog.
                val tail = sess.allLogsAsString.orEmpty().trim().takeLast(800)
                throw IOException(
                    "FFmpeg exited (rc=${sess.returnCode}) before opening pipe for ${resolved.trackId}. " +
                            "Last output: $tail",
                )
            }
            Thread.sleep(50)
        }
        opener.interrupt()
        throw (lastError ?: IOException("Timed out waiting for FFmpeg to open pipe for ${resolved.trackId}"))
    }

    private fun armTeeToCache() {
        teeFailed = false
        val file = AmazonFfmpegDecryptor.pendingFlacPath(resolved.trackId) ?: return
        try {
            file.parentFile?.mkdirs()
            teeOutput = FileOutputStream(file)
            teeFile = file
        } catch (e: IOException) {
            Timber.w(e, "Could not open tee-to-cache file for %s; continuing without caching", resolved.trackId)
            teeFile = null
            teeOutput = null
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0

        headerOut?.let { pending ->
            return deliverHeaderBytes(pending, buffer, offset, length)
        }

        val input = inputStream ?: return C.RESULT_END_OF_INPUT

        if (headerPending) {
            headerPending = false
            val parsed = try {
                parseAndPatchWavHeader(input)
            } catch (e: IOException) {
                Timber.e(e, "Error reading WAV header from FFmpeg pipe for %s", resolved.trackId)
                throw e
            }
            if (parsed == null) {
                // Stream ended before any header bytes arrived at all.
                finishTee(promote = false)
                return C.RESULT_END_OF_INPUT
            }
            headerOut = parsed
            headerOutPos = 0
            return deliverHeaderBytes(parsed, buffer, offset, length)
        }

        val read = try {
            input.read(buffer, offset, length)
        } catch (e: IOException) {
            Timber.e(e, "Error reading from FFmpeg pipe for %s", resolved.trackId)
            throw e
        }

        if (read == -1) {
            // Clean end of stream - if we were teeing to a cache file, this track played
            // through completely, so promote the scratch file into the real cache slot.
            finishTee(promote = true)
            return C.RESULT_END_OF_INPUT
        }

        bytesTransferred(read)
        tryTeeWrite(buffer, offset, read)
        return read
    }

    /**
     * Reads exactly [n] bytes from [input] (looping across underlying reads as needed - a pipe
     * read can return fewer bytes than requested). Returns null if EOF hits before any bytes are
     * read at all; throws if EOF hits partway through (a genuinely malformed/truncated stream).
     */
    private fun readExactly(input: InputStream, n: Int): ByteArray? {
        val buf = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = input.read(buf, got, n - got)
            if (r == -1) {
                return if (got == 0) null else throw IOException(
                    "Unexpected EOF ${got}/${n} bytes into WAV header for ${resolved.trackId}",
                )
            }
            got += r
        }
        return buf
    }

    /**
     * Walks the actual RIFF/WAVE chunk structure - rather than assuming a fixed header size -
     * since the fmt chunk's length varies (16 bytes for classic PCM, 40 for
     * WAVE_FORMAT_EXTENSIBLE, which ffmpeg commonly emits for pcm_s24le). Reads real
     * numChannels/sampleRate/bitsPerSample from the fmt chunk FFmpeg wrote correctly (it knows
     * the real decoded stream properties), locates the "data" chunk's declared-size field
     * wherever it actually falls, and rewrites just that one field using [knownDurationMs] - if
     * available - instead of trusting FFmpeg's non-seekable-pipe placeholder or leaving it
     * wrong. Returns the complete header bytes (RIFF start through the data sub-chunk header,
     * patched), to be delivered verbatim to the caller; raw PCM sample bytes after that point
     * are streamed through unmodified via the normal read() path. Returns null if the pipe
     * closed before any bytes arrived.
     */
    private fun parseAndPatchWavHeader(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream(256)

        val riffWave = readExactly(input, 12) ?: return null
        out.write(riffWave)
        val isRiffWave = riffWave.decodeAscii(0, 4) == "RIFF" && riffWave.decodeAscii(8, 4) == "WAVE"
        if (!isRiffWave) {
            // Not a WAV we understand - hand back what we've read verbatim and let the normal
            // passthrough path continue; nothing to patch.
            Timber.w("Unexpected FFmpeg pipe output header for %s (not RIFF/WAVE) - not patching", resolved.trackId)
            return out.toByteArray()
        }

        var numChannels = 0
        var sampleRate = 0L
        var bitsPerSample = 0
        var chunksRead = 0

        while (chunksRead < MAX_CHUNKS_TO_SCAN && out.size() < MAX_HEADER_BYTES) {
            chunksRead++
            val chunkHeader = readExactly(input, 8) ?: break
            out.write(chunkHeader)
            val chunkId = chunkHeader.decodeAscii(0, 4)
            val chunkSize = readLeU32(chunkHeader, 4)

            if (chunkId == "data") {
                // Position of this chunk's 4-byte size field within `out` - the size field is
                // the last 4 bytes just written as part of chunkHeader.
                val dataSizeFieldPos = out.size() - 4
                val headerBytes = out.toByteArray()
                patchDataChunkSize(headerBytes, dataSizeFieldPos, numChannels, sampleRate, bitsPerSample)
                return headerBytes
            }

            if (chunkSize < 0 || chunkSize > MAX_CHUNK_BODY_BYTES) {
                Timber.w("Implausible WAV chunk size %d for '%s' (%s) - stopping header scan", chunkSize, chunkId, resolved.trackId)
                break
            }
            val body = readExactly(input, chunkSize.toInt()) ?: break
            out.write(body)
            if (chunkId == "fmt " && body.size >= 16) {
                numChannels = readLeU16(body, 2)
                sampleRate = readLeU32(body, 4)
                bitsPerSample = readLeU16(body, 14)
            }
            // WAV chunks are word-aligned: an odd-sized chunk body has one pad byte after it.
            if (chunkSize % 2L == 1L) {
                readExactly(input, 1)?.let { out.write(it) }
            }
        }

        // No "data" chunk found within the scan bounds - return whatever was read verbatim,
        // unpatched, rather than risk corrupting a header shape we don't recognize.
        Timber.w("Could not locate WAV 'data' chunk for %s within header scan - not patching", resolved.trackId)
        return out.toByteArray()
    }

    private fun patchDataChunkSize(
        header: ByteArray,
        dataSizeFieldPos: Int,
        numChannels: Int,
        sampleRate: Long,
        bitsPerSample: Int,
    ) {
        val duration = knownDurationMs
        if (duration == null || duration <= 0 || numChannels <= 0 || sampleRate <= 0 || bitsPerSample <= 0) {
            // No known duration (or fmt chunk wasn't found/parseable) to compute a correct value
            // from. Deliberately NOT writing 0 here: WavExtractor treats a declared 0-byte data
            // chunk as an already-complete, zero-length track, which made ExoPlayer instantly
            // finish the track before a single sample played and silently auto-advance the
            // queue - i.e. exactly the track "randomly skips without playing" symptom. Leaving
            // FFmpeg's own placeholder value in place is strictly safer: worst case duration
            // displays wrong (the original problem this patching exists to fix), but the track
            // actually plays, which matters far more.
            return
        }
        val bytesPerSecond = sampleRate * numChannels * (bitsPerSample / 8)
        val dataSizeBytes = (duration / 1000.0 * bytesPerSecond).toLong().coerceIn(0L, 0xFFFFFFFEL)
        writeLeU32(header, dataSizeFieldPos, dataSizeBytes)
        Timber.d(
            "Patched WAV data size for %s: %d bytes (%.1fs @ %dHz/%dch/%dbit)",
            resolved.trackId, dataSizeBytes, duration / 1000.0, sampleRate, numChannels, bitsPerSample,
        )
    }

    private fun ByteArray.decodeAscii(off: Int, len: Int): String =
        String(this, off, len, StandardCharsets.US_ASCII)

    /**
     * Copies as much of [source] (from [headerOutPos] onward) into the caller's [buffer] as
     * fits, stashing any leftover in [headerOut]/[headerOutPos] for the next read() call.
     */
    private fun deliverHeaderBytes(source: ByteArray, buffer: ByteArray, offset: Int, length: Int): Int {
        val available = source.size - headerOutPos
        val toCopy = minOf(available, length)
        System.arraycopy(source, headerOutPos, buffer, offset, toCopy)
        headerOutPos += toCopy
        if (headerOutPos >= source.size) {
            headerOut = null
            headerOutPos = 0
        }
        bytesTransferred(toCopy)
        tryTeeWrite(buffer, offset, toCopy)
        return toCopy
    }

    private fun tryTeeWrite(buffer: ByteArray, offset: Int, len: Int) {
        if (len <= 0) return
        val out = teeOutput ?: return
        try {
            out.write(buffer, offset, len)
        } catch (e: IOException) {
            if (!teeFailed) {
                Timber.w(e, "Tee-to-cache write failed for %s; disabling cache write for this session", resolved.trackId)
                teeFailed = true
            }
            finishTee(promote = false)
        }
    }

    private fun readLeU16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun readLeU32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or
                ((b[off + 1].toLong() and 0xFF) shl 8) or
                ((b[off + 2].toLong() and 0xFF) shl 16) or
                ((b[off + 3].toLong() and 0xFF) shl 24)

    private fun writeLeU32(b: ByteArray, off: Int, value: Long) {
        b[off] = (value and 0xFF).toByte()
        b[off + 1] = ((value shr 8) and 0xFF).toByte()
        b[off + 2] = ((value shr 16) and 0xFF).toByte()
        b[off + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun finishTee(promote: Boolean) {
        val file = teeFile
        val out = teeOutput
        teeFile = null
        teeOutput = null
        if (out == null) return
        try {
            out.flush()
            out.close()
        } catch (ignored: IOException) {}
        if (promote && file != null) {
            AmazonFfmpegDecryptor.promoteStreamingTempToCache(resolved.trackId, file)
        } else {
            file?.delete()
        }
    }

    override fun getUri(): Uri? = currentDataSpec?.uri

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
        }

        // Any close() that isn't preceded by a clean read() EOF (seek, stop, error, app
        // backgrounded, etc.) is a partial read - never promote a partial file as if it were
        // the complete decrypted track.
        finishTee(promote = false)

        try {
            inputStream?.close()
        } catch (ignored: IOException) {}
        inputStream = null
        openedStreamHolder = null

        session?.let { sess ->
            // Every seek tears this down and open() immediately starts a brand new FFmpeg
            // session + native pipe. cancel() alone doesn't wait for the process to actually
            // die, and ffmpeg-kit only supports a small number of concurrent native pipes - on
            // rapid or large seeks, cancelled-but-not-yet-dead sessions could pile up faster
            // than they're reaped, and the next open() would then block waiting for a pipe slot
            // still held by a zombie session (observed as playback freezing on seek). Bound the
            // wait so a session that's slow to die can't hang the seek indefinitely either way.
            Timber.d("Cancelling FFmpeg session for %s", resolved.trackId)
            try {
                sess.cancel()
            } catch (ignored: Exception) {}
            val deadline = System.currentTimeMillis() + SESSION_TEARDOWN_TIMEOUT_MS
            while (sess.state != SessionState.COMPLETED && sess.state != SessionState.FAILED &&
                System.currentTimeMillis() < deadline
            ) {
                try {
                    Thread.sleep(20)
                } catch (ignored: InterruptedException) {
                    break
                }
            }
        }
        session = null

        pipePath?.let {
            try {
                FFmpegKitConfig.closeFFmpegPipe(it)
            } catch (ignored: Exception) {}
        }
        pipePath = null
        currentDataSpec = null
    }

    class Factory(
        private val context: Context,
        private val resolved: AmazonAudioProvider.Resolved,
        private val knownDurationMs: Long? = null,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = AmazonFfmpegDataSource(context, resolved, knownDurationMs)
    }

    private companion object {
        const val FALLBACK_BITRATE_BPS = 1_000_000L // ~1000 kbps, only used if resolved.bitrate is unknown
        const val PIPE_OPEN_TIMEOUT_MS = 15_000L
        const val SESSION_TEARDOWN_TIMEOUT_MS = 3_000L // bound on waiting for a cancelled session to actually die
        const val MAX_CHUNKS_TO_SCAN = 32 // safety bound against a malformed/unbounded stream
        const val MAX_HEADER_BYTES = 4096 // safety bound on total header size buffered
        const val MAX_CHUNK_BODY_BYTES = 4096 // safety bound on any single non-data chunk body
    }
}
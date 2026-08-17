package com.metrolist.paxsenix

/**
 * Faithful reimplementation of the (buggy) single-DES cipher exposed by
 * QQMusicCommon.dll's `des`/`Ddes` exports, which QQ Music's client uses to
 * encrypt/decrypt QRC lyric blobs. This is NOT standard DES — it's a
 * B-Con/crypto-algorithms-derived implementation with a genuine S-box typo
 * (S4 row 4 has a duplicated "10" where standard DES has "10, 1"), so a
 * standard library DES implementation will not produce matching output.
 * Ported bit-for-bit (including the bug) from the reverse-engineered
 * reference at github.com/wangqr/QQMusicDES (MIT), itself crediting
 * github.com/B-Con/crypto-algorithms for the base DES implementation.
 *
 * QQ's actual pipeline is three cascaded *single*-DES passes (not 3DES/EDE),
 * each using only the first 8 bytes of a 16-byte "key" string:
 * decrypt(KEY1) -> encrypt(KEY2) -> decrypt(KEY3), ECB (independent 8-byte
 * blocks). See QQMusicLyrics.kt for the call site.
 */
internal object QQMusicDes {

    // S-boxes exactly as reverse-engineered, bug included (sbox4 has "10, 10"
    // where standard DES S4 row 4 has "10, 1").
    private val sbox1 = intArrayOf(
        14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7,
        0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8,
        4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0,
        15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13,
    )
    private val sbox2 = intArrayOf(
        15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10,
        3, 13, 4, 7, 15, 2, 8, 15, 12, 0, 1, 10, 6, 9, 11, 5,
        0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15,
        13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9,
    )
    private val sbox3 = intArrayOf(
        10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8,
        13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1,
        13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7,
        1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12,
    )
    private val sbox4 = intArrayOf(
        7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15,
        13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9,
        10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4,
        3, 15, 0, 6, 10, 10, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14, // <- bug: "10, 10"
    )
    private val sbox5 = intArrayOf(
        2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9,
        14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6,
        4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14,
        11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3,
    )
    private val sbox6 = intArrayOf(
        12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11,
        10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8,
        9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6,
        4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13,
    )
    private val sbox7 = intArrayOf(
        4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1,
        13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6,
        1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2,
        6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12,
    )
    private val sbox8 = intArrayOf(
        13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7,
        1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2,
        7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8,
        2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11,
    )

    private const val MASK32 = -1L and 0xFFFFFFFFL

    private fun bitnum(a: ByteArray, b: Int, c: Int): Long {
        val idx = (b / 32) * 4 + 3 - (b % 32) / 8
        val byte = a[idx].toInt() and 0xFF
        val bit = (byte ushr (7 - (b % 8))) and 1
        return (bit.toLong()) shl c
    }

    private fun bitnumIntR(a: Long, b: Int, c: Int): Long {
        return ((a ushr (31 - b)) and 1L) shl c
    }

    private fun bitnumIntL(a: Long, b: Int, c: Int): Long {
        var v = (a shl b) and MASK32
        v = v and 0x80000000L
        return v ushr c
    }

    private fun sboxBit(a: Int): Int {
        return (a and 0x20) or ((a and 0x1f) ushr 1) or ((a and 0x01) shl 4)
    }

    private fun ip(inb: ByteArray): LongArray {
        val s0 = (
            bitnum(inb, 57, 31) or bitnum(inb, 49, 30) or bitnum(inb, 41, 29) or bitnum(inb, 33, 28) or
                bitnum(inb, 25, 27) or bitnum(inb, 17, 26) or bitnum(inb, 9, 25) or bitnum(inb, 1, 24) or
                bitnum(inb, 59, 23) or bitnum(inb, 51, 22) or bitnum(inb, 43, 21) or bitnum(inb, 35, 20) or
                bitnum(inb, 27, 19) or bitnum(inb, 19, 18) or bitnum(inb, 11, 17) or bitnum(inb, 3, 16) or
                bitnum(inb, 61, 15) or bitnum(inb, 53, 14) or bitnum(inb, 45, 13) or bitnum(inb, 37, 12) or
                bitnum(inb, 29, 11) or bitnum(inb, 21, 10) or bitnum(inb, 13, 9) or bitnum(inb, 5, 8) or
                bitnum(inb, 63, 7) or bitnum(inb, 55, 6) or bitnum(inb, 47, 5) or bitnum(inb, 39, 4) or
                bitnum(inb, 31, 3) or bitnum(inb, 23, 2) or bitnum(inb, 15, 1) or bitnum(inb, 7, 0)
            ) and MASK32
        val s1 = (
            bitnum(inb, 56, 31) or bitnum(inb, 48, 30) or bitnum(inb, 40, 29) or bitnum(inb, 32, 28) or
                bitnum(inb, 24, 27) or bitnum(inb, 16, 26) or bitnum(inb, 8, 25) or bitnum(inb, 0, 24) or
                bitnum(inb, 58, 23) or bitnum(inb, 50, 22) or bitnum(inb, 42, 21) or bitnum(inb, 34, 20) or
                bitnum(inb, 26, 19) or bitnum(inb, 18, 18) or bitnum(inb, 10, 17) or bitnum(inb, 2, 16) or
                bitnum(inb, 60, 15) or bitnum(inb, 52, 14) or bitnum(inb, 44, 13) or bitnum(inb, 36, 12) or
                bitnum(inb, 28, 11) or bitnum(inb, 20, 10) or bitnum(inb, 12, 9) or bitnum(inb, 4, 8) or
                bitnum(inb, 62, 7) or bitnum(inb, 54, 6) or bitnum(inb, 46, 5) or bitnum(inb, 38, 4) or
                bitnum(inb, 30, 3) or bitnum(inb, 22, 2) or bitnum(inb, 14, 1) or bitnum(inb, 6, 0)
            ) and MASK32
        return longArrayOf(s0, s1)
    }

    private fun invIp(state: LongArray): ByteArray {
        val s1 = state[1]
        val s0 = state[0]
        val out = ByteArray(8)
        out[3] = ((bitnumIntR(s1, 7, 7) or bitnumIntR(s0, 7, 6) or bitnumIntR(s1, 15, 5) or bitnumIntR(s0, 15, 4) or
            bitnumIntR(s1, 23, 3) or bitnumIntR(s0, 23, 2) or bitnumIntR(s1, 31, 1) or bitnumIntR(s0, 31, 0)) and 0xFF).toByte()
        out[2] = ((bitnumIntR(s1, 6, 7) or bitnumIntR(s0, 6, 6) or bitnumIntR(s1, 14, 5) or bitnumIntR(s0, 14, 4) or
            bitnumIntR(s1, 22, 3) or bitnumIntR(s0, 22, 2) or bitnumIntR(s1, 30, 1) or bitnumIntR(s0, 30, 0)) and 0xFF).toByte()
        out[1] = ((bitnumIntR(s1, 5, 7) or bitnumIntR(s0, 5, 6) or bitnumIntR(s1, 13, 5) or bitnumIntR(s0, 13, 4) or
            bitnumIntR(s1, 21, 3) or bitnumIntR(s0, 21, 2) or bitnumIntR(s1, 29, 1) or bitnumIntR(s0, 29, 0)) and 0xFF).toByte()
        out[0] = ((bitnumIntR(s1, 4, 7) or bitnumIntR(s0, 4, 6) or bitnumIntR(s1, 12, 5) or bitnumIntR(s0, 12, 4) or
            bitnumIntR(s1, 20, 3) or bitnumIntR(s0, 20, 2) or bitnumIntR(s1, 28, 1) or bitnumIntR(s0, 28, 0)) and 0xFF).toByte()
        out[7] = ((bitnumIntR(s1, 3, 7) or bitnumIntR(s0, 3, 6) or bitnumIntR(s1, 11, 5) or bitnumIntR(s0, 11, 4) or
            bitnumIntR(s1, 19, 3) or bitnumIntR(s0, 19, 2) or bitnumIntR(s1, 27, 1) or bitnumIntR(s0, 27, 0)) and 0xFF).toByte()
        out[6] = ((bitnumIntR(s1, 2, 7) or bitnumIntR(s0, 2, 6) or bitnumIntR(s1, 10, 5) or bitnumIntR(s0, 10, 4) or
            bitnumIntR(s1, 18, 3) or bitnumIntR(s0, 18, 2) or bitnumIntR(s1, 26, 1) or bitnumIntR(s0, 26, 0)) and 0xFF).toByte()
        out[5] = ((bitnumIntR(s1, 1, 7) or bitnumIntR(s0, 1, 6) or bitnumIntR(s1, 9, 5) or bitnumIntR(s0, 9, 4) or
            bitnumIntR(s1, 17, 3) or bitnumIntR(s0, 17, 2) or bitnumIntR(s1, 25, 1) or bitnumIntR(s0, 25, 0)) and 0xFF).toByte()
        out[4] = ((bitnumIntR(s1, 0, 7) or bitnumIntR(s0, 0, 6) or bitnumIntR(s1, 8, 5) or bitnumIntR(s0, 8, 4) or
            bitnumIntR(s1, 16, 3) or bitnumIntR(s0, 16, 2) or bitnumIntR(s1, 24, 1) or bitnumIntR(s0, 24, 0)) and 0xFF).toByte()
        return out
    }

    private fun feistel(state: Long, key6: IntArray): Long {
        val t1 = (
            bitnumIntL(state, 31, 0) or ((state and 0xf0000000L) ushr 1) or bitnumIntL(state, 4, 5) or
                bitnumIntL(state, 3, 6) or ((state and 0x0f000000L) ushr 3) or bitnumIntL(state, 8, 11) or
                bitnumIntL(state, 7, 12) or ((state and 0x00f00000L) ushr 5) or bitnumIntL(state, 12, 17) or
                bitnumIntL(state, 11, 18) or ((state and 0x000f0000L) ushr 7) or bitnumIntL(state, 16, 23)
            ) and MASK32
        val t2 = (
            bitnumIntL(state, 15, 0) or ((state and 0x0000f000L) shl 15) or bitnumIntL(state, 20, 5) or
                bitnumIntL(state, 19, 6) or ((state and 0x00000f00L) shl 13) or bitnumIntL(state, 24, 11) or
                bitnumIntL(state, 23, 12) or ((state and 0x000000f0L) shl 11) or bitnumIntL(state, 28, 17) or
                bitnumIntL(state, 27, 18) or ((state and 0x0000000fL) shl 9) or bitnumIntL(state, 0, 23)
            ) and MASK32

        val lrg = IntArray(6)
        lrg[0] = ((t1 ushr 24) and 0xffL).toInt()
        lrg[1] = ((t1 ushr 16) and 0xffL).toInt()
        lrg[2] = ((t1 ushr 8) and 0xffL).toInt()
        lrg[3] = ((t2 ushr 24) and 0xffL).toInt()
        lrg[4] = ((t2 ushr 16) and 0xffL).toInt()
        lrg[5] = ((t2 ushr 8) and 0xffL).toInt()

        for (i in 0 until 6) lrg[i] = lrg[i] xor key6[i]

        val s = (
            (sbox1[sboxBit(lrg[0] ushr 2)].toLong() shl 28) or
                (sbox2[sboxBit(((lrg[0] and 0x03) shl 4) or (lrg[1] ushr 4))].toLong() shl 24) or
                (sbox3[sboxBit(((lrg[1] and 0x0f) shl 2) or (lrg[2] ushr 6))].toLong() shl 20) or
                (sbox4[sboxBit(lrg[2] and 0x3f)].toLong() shl 16) or
                (sbox5[sboxBit(lrg[3] ushr 2)].toLong() shl 12) or
                (sbox6[sboxBit(((lrg[3] and 0x03) shl 4) or (lrg[4] ushr 4))].toLong() shl 8) or
                (sbox7[sboxBit(((lrg[4] and 0x0f) shl 2) or (lrg[5] ushr 6))].toLong() shl 4) or
                sbox8[sboxBit(lrg[5] and 0x3f)].toLong()
            ) and MASK32

        return (
            bitnumIntL(s, 15, 0) or bitnumIntL(s, 6, 1) or bitnumIntL(s, 19, 2) or
                bitnumIntL(s, 20, 3) or bitnumIntL(s, 28, 4) or bitnumIntL(s, 11, 5) or
                bitnumIntL(s, 27, 6) or bitnumIntL(s, 16, 7) or bitnumIntL(s, 0, 8) or
                bitnumIntL(s, 14, 9) or bitnumIntL(s, 22, 10) or bitnumIntL(s, 25, 11) or
                bitnumIntL(s, 4, 12) or bitnumIntL(s, 17, 13) or bitnumIntL(s, 30, 14) or
                bitnumIntL(s, 9, 15) or bitnumIntL(s, 1, 16) or bitnumIntL(s, 7, 17) or
                bitnumIntL(s, 23, 18) or bitnumIntL(s, 13, 19) or bitnumIntL(s, 31, 20) or
                bitnumIntL(s, 26, 21) or bitnumIntL(s, 2, 22) or bitnumIntL(s, 8, 23) or
                bitnumIntL(s, 18, 24) or bitnumIntL(s, 12, 25) or bitnumIntL(s, 29, 26) or
                bitnumIntL(s, 5, 27) or bitnumIntL(s, 21, 28) or bitnumIntL(s, 10, 29) or
                bitnumIntL(s, 3, 30) or bitnumIntL(s, 24, 31)
            ) and MASK32
    }

    private val keyRndShift = intArrayOf(1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1)
    private val keyPermC = intArrayOf(
        56, 48, 40, 32, 24, 16, 8, 0, 57, 49, 41, 33, 25, 17,
        9, 1, 58, 50, 42, 34, 26, 18, 10, 2, 59, 51, 43, 35,
    )
    private val keyPermD = intArrayOf(
        62, 54, 46, 38, 30, 22, 14, 6, 61, 53, 45, 37, 29, 21,
        13, 5, 60, 52, 44, 36, 28, 20, 12, 4, 27, 19, 11, 3,
    )
    private val keyCompression = intArrayOf(
        13, 16, 10, 23, 0, 4, 2, 27, 14, 5, 20, 9,
        22, 18, 11, 3, 25, 7, 15, 6, 26, 19, 12, 1,
        40, 51, 30, 36, 46, 54, 29, 39, 50, 44, 32, 47,
        43, 48, 38, 55, 33, 52, 45, 41, 49, 35, 28, 31,
    )

    private fun keySetup(key8: ByteArray, decrypt: Boolean): Array<IntArray> {
        var c = 0L
        var j = 31
        for (i in 0 until 28) {
            c = c or bitnum(key8, keyPermC[i], j)
            j--
        }
        c = c and MASK32
        var d = 0L
        j = 31
        for (i in 0 until 28) {
            d = d or bitnum(key8, keyPermD[i], j)
            j--
        }
        d = d and MASK32

        val schedule = Array(16) { IntArray(6) }
        for (i in 0 until 16) {
            val shift = keyRndShift[i]
            c = ((c shl shift) or (c ushr (28 - shift))) and 0xfffffff0L and MASK32
            d = ((d shl shift) or (d ushr (28 - shift))) and 0xfffffff0L and MASK32
            val toGen = if (decrypt) 15 - i else i
            val sched = IntArray(6)
            for (j2 in 0 until 24) {
                sched[j2 / 8] = sched[j2 / 8] or bitnumIntR(c, keyCompression[j2], 7 - (j2 % 8)).toInt()
            }
            for (j2 in 24 until 48) {
                sched[j2 / 8] = sched[j2 / 8] or bitnumIntR(d, keyCompression[j2] - 27, 7 - (j2 % 8)).toInt()
            }
            schedule[toGen] = sched
        }
        return schedule
    }

    private fun cryptBlock(inb: ByteArray, schedule: Array<IntArray>): ByteArray {
        val state = ip(inb)
        for (idx in 0 until 15) {
            val t = state[1]
            state[1] = (feistel(state[1], schedule[idx]) xor state[0]) and MASK32
            state[0] = t
        }
        state[0] = (feistel(state[1], schedule[15]) xor state[0]) and MASK32
        return invIp(state)
    }

    /** Runs single-DES (ECB, independent 8-byte blocks) over [data] using the first 8 bytes of [key]. */
    fun crypt(data: ByteArray, key: ByteArray, decrypt: Boolean): ByteArray {
        require(key.size >= 8) { "QQ Music DES key must be at least 8 bytes" }
        val key8 = key.copyOfRange(0, 8)
        val schedule = keySetup(key8, decrypt)
        val out = ByteArray(data.size)
        var i = 0
        while (i < data.size) {
            val block = if (i + 8 <= data.size) data.copyOfRange(i, i + 8) else {
                // Shouldn't happen for well-formed QQ blobs (always a multiple of 8), but
                // guard anyway rather than throwing on a truncated trailing block.
                val padded = ByteArray(8)
                System.arraycopy(data, i, padded, 0, data.size - i)
                padded
            }
            val outBlock = cryptBlock(block, schedule)
            val n = minOf(8, data.size - i)
            System.arraycopy(outBlock, 0, out, i, n)
            i += 8
        }
        return out
    }
}

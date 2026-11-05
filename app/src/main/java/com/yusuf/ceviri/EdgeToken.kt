package com.yusuf.ceviri

/**
 * Edge "sesli oku" servisinin istediği Sec-MS-GEC belirteci (v2).
 * edge-tts 7.3.1 (drm.py) ile birebir aynı hesap; masaüstünde aynı çıktı verdiği test edildi.
 */
object EdgeToken {
    const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val WIN_EPOCH = 11644473600L
    private const val M = 0xFFFFFFFFL

    private val INITIAL = longArrayOf(
        0x6A09E667L, 0xBB67AE85L, 0x3C6EF372L, 0xA54FF53AL, 0x510E527FL, 0x9B05688CL, 0x1F83D9ABL, 0x5BE0CD19L,
    )
    private val STATE = longArrayOf(
        0xFD9FBEA2L, 0x91243CE7L, 0xBE499094L, 0xCD2E1E0AL, 0x6C006F49L, 0x04BD35ABL, 0xFEB0A7E4L, 0x03EFFBD2L,
    )
    private val SCHEDULE = longArrayOf(
        0xEB24BF9EL, 0x1558691FL, 0xA0C09EE2L, 0x0CFA32BAL, 0xA622EE8CL, 0xD1DD9491L, 0xC0DD25A0L,
        0xF9E70AB6L, 0xF0C31573L, 0x7058CE4EL, 0x7188E606L, 0x00E5AB33L, 0xD258B060L, 0x72766564L,
    )
    private val K = longArrayOf(
        0x428A2F98L, 0x71374491L, 0xB5C0FBCFL, 0xE9B5DBA5L, 0x3956C25BL, 0x59F111F1L, 0x923F82A4L, 0xAB1C5ED5L,
        0xD807AA98L, 0x12835B01L, 0x243185BEL, 0x550C7DC3L, 0x72BE5D74L, 0x80DEB1FEL, 0x9BDC06A7L, 0xC19BF174L,
        0xE49B69C1L, 0xEFBE4786L, 0x0FC19DC6L, 0x240CA1CCL, 0x2DE92C6FL, 0x4A7484AAL, 0x5CB0A9DCL, 0x76F988DAL,
        0x983E5152L, 0xA831C66DL, 0xB00327C8L, 0xBF597FC7L, 0xC6E00BF3L, 0xD5A79147L, 0x06CA6351L, 0x14292967L,
        0x27B70A85L, 0x2E1B2138L, 0x4D2C6DFCL, 0x53380D13L, 0x650A7354L, 0x766A0ABBL, 0x81C2C92EL, 0x92722C85L,
        0xA2BFE8A1L, 0xA81A664BL, 0xC24B8B70L, 0xC76C51A3L, 0xD192E819L, 0xD6990624L, 0xF40E3585L, 0x106AA070L,
        0x19A4C116L, 0x1E376C08L, 0x2748774CL, 0x34B0BCB5L, 0x391C0CB3L, 0x4ED8AA4AL, 0x5B9CCA4FL, 0x682E6FF3L,
        0x748F82EEL, 0x78A5636FL, 0x84C87814L, 0x8CC70208L, 0x90BEFFFAL, 0xA4506CEBL, 0xBEF9A3F7L, 0xC67178F2L,
    )
    private const val ADJUSTMENT = 0xC9DC06A7L
    private val BLOCK_K = K.copyOf().also { it[14] = ADJUSTMENT }
    private const val SIGMA0_ADJ = 0x059C0B80L
    private const val SIGMA1_ADJ = 0x000B92C0L

    private val CS64 = arrayOf(
        longArrayOf(0xE82E91B1L, 0xB32700D0L), longArrayOf(0x44DE24C6L, 0x88DFF51DL),
        longArrayOf(0x3BEB65BCL, 0x29514038L), longArrayOf(0xC89C5933L, 0xB328CBDFL),
        longArrayOf(0x8D1EC211L, 0xD405B4D5L), longArrayOf(0x314EF5D5L, 0x26E55F3CL),
        longArrayOf(0xE92491B1L, 0x607801C6L), longArrayOf(0xF0A5B9D5L, 0x82B2C1FBL),
        longArrayOf(0x06064CF3L, 0xFA51A3DAL), longArrayOf(0xDA79FA60L, 0x207CC464L),
        longArrayOf(0xBED20560L, 0x3C3C74F3L), longArrayOf(0x89F9C9F8L, 0xD2D14B61L),
        longArrayOf(0x35FA38C6L, 0xBB04A2C1L), longArrayOf(0xCB49E3C6L, 0xC5626167L),
    )
    private const val CS64_FACTOR = 0x82893933L
    private const val CS64_ADJ0 = 0xF2F90B1AL
    private const val CS64_ADJ1 = 0x0B1A0000L

    private fun rotr(x: Long, n: Int): Long = ((x ushr n) or (x shl (32 - n))) and M
    private fun rol16(v: Long): Long {
        val x = v and M
        return ((x shl 16) or (x ushr 16)) and M
    }

    private fun sigma0(x: Long): Long = (rotr(x, 7) xor rotr(x, 18) xor (x ushr 3)) and M
    private fun sigma1(x: Long): Long = (rotr(x, 17) xor rotr(x, 19) xor (x ushr 10)) and M

    private fun rounds(state: LongArray, w: LongArray, first: Int): LongArray {
        var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
        var e = state[4]; var f = state[5]; var g = state[6]; var h = state[7]
        val k = if (first == 14) BLOCK_K else K
        for (i in first until 64) {
            val s1 = rotr(e, 6) xor rotr(e, 11) xor rotr(e, 25)
            val ch = g xor (e and (f xor g))
            val t1 = (h + s1 + ch + k[i] + w[i]) and M
            val s0 = rotr(a, 2) xor rotr(a, 13) xor rotr(a, 22)
            val maj = (a and b) or (c and (a or b))
            val t2 = (s0 + maj) and M
            h = g; g = f; f = e
            e = (d + t1) and M
            d = c; c = b; b = a
            a = (t1 + t2) and M
        }
        return longArrayOf(a, b, c, d, e, f, g, h)
    }

    private fun expand(w: LongArray, firstWord: Int) {
        for (i in firstWord until 64) {
            w[i] = (sigma1(w[i - 2]) + w[i - 7] + sigma0(w[i - 15]) + w[i - 16]) and M
        }
    }

    private fun be(data: ByteArray, from: Int, len: Int): Long {
        var v = 0L
        for (i in from until from + len) v = (v shl 8) or (data[i].toLong() and 0xFF)
        return v
    }

    private fun shaInit(d7: ByteArray): LongArray {
        val w = LongArray(64)
        w[14] = be(d7, 0, 3)
        w[15] = be(d7, 3, 4)
        for (i in 16 until 30) {
            var v = SCHEDULE[i - 16]
            if (i - 2 >= 14) v += if (i == 16) (sigma1(w[i - 2]) xor SIGMA1_ADJ) else sigma1(w[i - 2])
            if (i - 7 >= 14) v += w[i - 7]
            if (i - 15 >= 14) v += if (i == 29) (sigma0(w[i - 15]) xor SIGMA0_ADJ) else sigma0(w[i - 15])
            w[i] = v and M
        }
        w[30] = (sigma1(w[28]) + w[23] + sigma0(w[15]) + w[14] + ADJUSTMENT - K[14]) and M
        expand(w, 31)
        val working = rounds(STATE, w, 14)
        return LongArray(8) { (INITIAL[it] + working[it]) and M }
    }

    fun sha256(data: ByteArray): ByteArray {
        require(data.size >= 7) { "Belirteç verisi çok kısa" }
        val length = 57 + data.size
        val tail = data.size - 7
        val zeros = Math.floorMod(55 - length, 64)
        val padded = ByteArray(tail + 1 + zeros + 8)
        System.arraycopy(data, 7, padded, 0, tail)
        padded[tail] = 0x80.toByte()
        val bits = length.toLong() * 8
        for (i in 0 until 8) padded[padded.size - 1 - i] = (bits ushr (8 * i)).toByte()
        var state = shaInit(data.copyOfRange(0, 7))
        var off = 0
        while (off < padded.size) {
            val w = LongArray(64)
            for (i in 0 until 16) w[i] = be(padded, off + i * 4, 4)
            expand(w, 16)
            val working = rounds(state, w, 0)
            val s = state
            state = LongArray(8) { (s[it] + working[it]) and M }
            off += 64
        }
        val out = ByteArray(32)
        for (i in 0 until 8) for (j in 0 until 4) out[i * 4 + j] = (state[i] ushr (24 - 8 * j)).toByte()
        return out
    }

    private fun le(data: ByteArray, from: Int): Long {
        var v = 0L
        for (i in 3 downTo 0) v = (v shl 8) or (data[from + i].toLong() and 0xFF)
        return v
    }

    private fun wordsLe4Even(data: ByteArray): LongArray {
        val padLen = data.size + Math.floorMod(-data.size, 4)
        if (padLen == 0) return LongArray(0)
        val padded = data.copyOf(padLen)
        val n = padLen / 4
        val count = if (n % 2 == 1) n + 1 else n
        val out = LongArray(count)
        for (i in 0 until n) out[i] = le(padded, i * 4)
        return out
    }

    fun cs64(data: ByteArray, digest: ByteArray): LongArray {
        val keyA = le(digest, 0) or 1L
        val keyB = le(digest, 4) or 1L
        val factors = longArrayOf((keyA * CS64_FACTOR) and M, (keyB * CS64_FACTOR) and M)
        val terms = ArrayList<Long>()
        val offsets = ArrayList<Long>()
        for (i in CS64.indices) {
            terms.add((CS64[i][0] * factors[i % 2]) and M)
            offsets.add(CS64[i][1])
        }
        val words = wordsLe4Even(byteArrayOf(0) + data)
        for (i in words.indices) {
            val word = words[i]
            var term = word * (if (i % 2 == 0) keyA else keyB)
            var offset = rol16(word) * (if (i % 2 == 0) 0xEF0569FBL else 0xC31713DBL)
            if (i == 0) {
                term += CS64_ADJ0 * factors[0]
                offset += CS64_ADJ1
            }
            terms.add(term and M)
            offsets.add(offset and M)
        }
        var swapAcc = 0L
        var swapLast = 0L
        var state = 0L
        var acc = 0L
        var i = 0
        while (i < terms.size) {
            val x = terms[i]
            val y = terms[i + 1]
            var t = rol16((offsets[i] + x) and M)
            var q = (t * 0x76F50F34L) and M
            swapAcc += (rol16(q) * 0xC3EFEA97L + q) and M
            t = rol16((offsets[i + 1] + y) and M)
            q = (t * 0x1881CE3CL) and M
            swapLast = (rol16(q) * 0x35BD1EC9L + q) and M
            swapAcc = (swapAcc + swapLast) and M

            state = (state * keyA + x) and M
            state = (rol16(state) * 0xCF98B111L) and M
            state = (rol16(state) * 0x87085B9FL) and M
            state = (rol16(state) * 0x12CEB96DL) and M
            state = (rol16(state) * 0x257E1D83L) and M
            acc += state
            t = (state * keyB + y) and M
            t = (rol16(t) * 0xA27416F5L) and M
            t = (rol16(t) * 0xD38396FFL) and M
            state = (rol16((rol16(t) * 0x7C932B89L) and M) * 0xBFA49F69L) and M
            acc = (acc + state) and M
            i += 2
        }
        return longArrayOf(swapLast xor state, swapAcc xor acc)
    }

    private fun hex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) sb.append(String.format("%02X", x.toInt() and 0xFF))
        return sb.toString()
    }

    /** unixSeconds: saat farkı düzeltilmiş şimdiki zaman (saniye). */
    fun generate(unixSeconds: Long): String {
        var secs = unixSeconds + WIN_EPOCH
        secs -= secs % 300
        val ticks = secs * 10_000_000L
        val data = (ticks.toString() + TRUSTED_CLIENT_TOKEN).toByteArray(Charsets.US_ASCII)
        val digest = sha256(data)
        val c = cs64(data, digest)
        val cs = ByteArray(8)
        for (k in 0 until 2) for (j in 0 until 4) cs[k * 4 + j] = (c[k] ushr (8 * j)).toByte()
        return hex(cs) + "-" + hex(digest)
    }
}

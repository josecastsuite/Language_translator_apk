package com.yusuf.ceviri

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.text.Normalizer

/**
 * NLLB-200 BPE tokenizer'ı. tools/prepare_nllb_tokenizer.py'nin ürettiği dosyaları okur.
 * Masaüstünde HuggingFace tokenizer'ıyla birebir aynı çıktı verdiği doğrulandı.
 */
class NllbTokenizer(dir: File) {
    private val vocab: Array<String>
    private val tok2id: HashMap<String, Int>
    private val mergeKeys: LongArray
    private val mergeRanks: IntArray
    private val mask: Int

    init {
        val list = ArrayList<String>(256_300)
        File(dir, "nllb_vocab.txt").bufferedReader(Charsets.UTF_8).use { r ->
            while (true) list.add(r.readLine() ?: break)
        }
        check(list.size > 256_000) { "NLLB sözlüğü eksik: ${list.size}" }
        vocab = list.toTypedArray()
        tok2id = HashMap(vocab.size * 2)
        for (i in vocab.indices) tok2id[vocab[i]] = i

        var keys = LongArray(600_000)
        var n = 0
        File(dir, "nllb_merges.txt").bufferedReader(Charsets.UTF_8).use { r ->
            while (true) {
                val line = r.readLine() ?: break
                if (line.isEmpty()) continue
                val sp = line.indexOf(' ')
                val a = line.substring(0, sp).toInt()
                val b = line.substring(sp + 1).toInt()
                if (n == keys.size) keys = keys.copyOf(n * 2)
                keys[n++] = pairKey(a, b)
            }
        }
        var cap = 1
        while (cap < n * 2) cap = cap shl 1
        mask = cap - 1
        mergeKeys = LongArray(cap) { EMPTY }
        mergeRanks = IntArray(cap)
        for (rank in 0 until n) put(keys[rank], rank)
    }

    private fun pairKey(a: Int, b: Int): Long = (a.toLong() shl 32) or (b.toLong() and 0xffffffffL)

    private fun slot(key: Long): Int {
        var h = key * -0x61c8864680b583ebL
        h = h xor (h ushr 29)
        return h.toInt() and mask
    }

    private fun put(key: Long, rank: Int) {
        var i = slot(key)
        while (true) {
            val k = mergeKeys[i]
            if (k == EMPTY) {
                mergeKeys[i] = key
                mergeRanks[i] = rank
                return
            }
            if (k == key) return
            i = (i + 1) and mask
        }
    }

    private fun rank(a: Int, b: Int): Int {
        val key = pairKey(a, b)
        var i = slot(key)
        while (true) {
            val k = mergeKeys[i]
            if (k == EMPTY) return -1
            if (k == key) return mergeRanks[i]
            i = (i + 1) and mask
        }
    }

    fun id(token: String): Int = tok2id[token] ?: throw IllegalStateException("Sözlükte yok: $token")

    private fun encodeWord(word: String, out: MutableList<Int>) {
        val syms = ArrayList<Int>(word.length)
        var i = 0
        while (i < word.length) {
            val cp = word.codePointAt(i)
            val id = tok2id[String(Character.toChars(cp))] ?: UNK
            // fuse_unk: art arda bilinmeyen karakterler tek <unk> olur
            if (!(id == UNK && syms.isNotEmpty() && syms[syms.size - 1] == UNK)) syms.add(id)
            i += Character.charCount(cp)
        }
        while (syms.size > 1) {
            var best = Int.MAX_VALUE
            var at = -1
            for (k in 0 until syms.size - 1) {
                val r = rank(syms[k], syms[k + 1])
                if (r in 0 until best) {
                    best = r
                    at = k
                }
            }
            if (at < 0) break
            val merged = tok2id[vocab[syms[at]] + vocab[syms[at + 1]]]
                ?: throw IllegalStateException("Birleşim sözlükte yok")
            syms[at] = merged
            syms.removeAt(at + 1)
        }
        out.addAll(syms)
    }

    fun encode(text: String): IntArray {
        var t = Normalizer.normalize(text, Normalizer.Form.NFKC)
        t = MULTI_SPACE.replace(t, " ")
        t = META + t.replace(' ', META)
        val out = ArrayList<Int>()
        var start = 0
        for (i in 1..t.length) {
            if (i == t.length || t[i] == META) {
                encodeWord(t.substring(start, i), out)
                start = i
            }
        }
        return out.toIntArray()
    }

    fun decode(ids: List<Int>): String {
        val sb = StringBuilder()
        for (i in ids) sb.append(vocab[i])
        val s = sb.toString().replace(META, ' ')
        return if (s.startsWith(" ")) s.substring(1) else s
    }

    companion object {
        const val UNK = 3
        private const val EMPTY = -1L
        private const val META = '▁'
        private val MULTI_SPACE = Regex(" {2,}")
    }
}

/**
 * NLLB-200 (600M, 8 bit) ile metin çevirisi. Decoder önbellekli (past key/value) çalışır;
 * çıktı katmanı tools/prune_nllb_lm_head.py ile Latin alfabeli tokenlara indirilmiştir.
 */
class NllbTranslator(dir: File, threads: Int) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val tok = NllbTokenizer(dir)
    private val logitMap: IntArray =
        File(dir, "nllb_logit_map.txt").readLines().filter { it.isNotBlank() }.map { it.trim().toInt() }.toIntArray()
    private val encoder: OrtSession
    private val decoder: OrtSession

    init {
        val opts = OrtSession.SessionOptions()
        opts.setIntraOpNumThreads(threads)
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        encoder = env.createSession(File(dir, "encoder.onnx").absolutePath, opts)
        decoder = env.createSession(File(dir, "decoder.onnx").absolutePath, opts)
    }

    @Synchronized
    fun translate(text: String, src: Lang, tgt: Lang): String {
        val clean = text.trim()
        if (src == tgt || clean.isEmpty()) return clean
        // NLLB cümle düzeyinde eğitildi; birden çok cümle tek seferde verilirse sonrakileri atlayabiliyor.
        return SENTENCE_END.split(clean).map { it.trim() }.filter { it.isNotEmpty() }
            .joinToString(" ") { translateSentence(it, src, tgt) }.trim()
    }

    private fun translateSentence(sentence: String, src: Lang, tgt: Lang): String {
        val body = tok.encode(sentence)
        val clipped = if (body.size > MAX_INPUT) body.copyOf(MAX_INPUT) else body
        val ids = LongArray(clipped.size + 2)
        ids[0] = tok.id(src.nllb).toLong()
        for (i in clipped.indices) ids[i + 1] = clipped[i].toLong()
        ids[ids.size - 1] = EOS.toLong()
        val shape = longArrayOf(1, ids.size.toLong())

        val created = ArrayList<OnnxTensor>()
        fun keep(t: OnnxTensor): OnnxTensor {
            created.add(t)
            return t
        }

        var encOut: OrtSession.Result? = null
        var first: OrtSession.Result? = null
        var prev: OrtSession.Result? = null
        try {
            val inIds = keep(OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape))
            val inMask = keep(OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size) { 1L }), shape))
            val enc = encoder.run(mapOf("input_ids" to inIds, "attention_mask" to inMask))
            encOut = enc
            val hidden = enc.get("last_hidden_state").get() as OnnxTensor

            // İlk adımda önbellek dalı kapalı; önbellek girdileri yok sayılır ama verilmeleri gerekir.
            val dummy = keep(
                OnnxTensor.createTensor(
                    env, FloatBuffer.wrap(FloatArray(HEADS * HEAD_DIM)),
                    longArrayOf(1, HEADS.toLong(), 1, HEAD_DIM.toLong())
                )
            )
            val feeds = HashMap<String, OnnxTensor>()
            feeds["encoder_attention_mask"] = inMask
            feeds["encoder_hidden_states"] = hidden
            for (l in 0 until LAYERS) {
                for (kind in arrayOf("decoder", "encoder")) {
                    feeds["past_key_values.$l.$kind.key"] = dummy
                    feeds["past_key_values.$l.$kind.value"] = dummy
                }
            }
            feeds["use_cache_branch"] = keep(OnnxTensor.createTensor(env, booleanArrayOf(false)))
            feeds["input_ids"] = keep(
                OnnxTensor.createTensor(
                    env, LongBuffer.wrap(longArrayOf(EOS.toLong(), tok.id(tgt.nllb).toLong())), longArrayOf(1, 2)
                )
            )
            val useCache = keep(OnnxTensor.createTensor(env, booleanArrayOf(true)))

            val result = ArrayList<Int>()
            val maxSteps = ids.size * 2 + 10
            for (step in 0 until maxSteps) {
                val out = decoder.run(feeds)
                val p = prev
                if (p != null && p !== first) p.close()
                val firstRes = first ?: out
                first = firstRes
                prev = out

                val next = argmaxLast(out.get("logits").get() as OnnxTensor)
                if (next == EOS) break
                result.add(next)

                for (l in 0 until LAYERS) {
                    feeds["past_key_values.$l.decoder.key"] = out.get("present.$l.decoder.key").get() as OnnxTensor
                    feeds["past_key_values.$l.decoder.value"] = out.get("present.$l.decoder.value").get() as OnnxTensor
                    feeds["past_key_values.$l.encoder.key"] = firstRes.get("present.$l.encoder.key").get() as OnnxTensor
                    feeds["past_key_values.$l.encoder.value"] = firstRes.get("present.$l.encoder.value").get() as OnnxTensor
                }
                feeds["use_cache_branch"] = useCache
                feeds["input_ids"] = keep(
                    OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(next.toLong())), longArrayOf(1, 1))
                )
            }
            return tok.decode(result)
        } finally {
            val p = prev
            if (p != null && p !== first) p.close()
            first?.close()
            encOut?.close()
            created.forEach { it.close() }
        }
    }

    private fun argmaxLast(logits: OnnxTensor): Int {
        val shape = logits.info.shape
        val k = shape[2].toInt()
        val seq = shape[1].toInt()
        check(k == logitMap.size) { "Çıktı boyutu uyumsuz: $k / ${logitMap.size}" }
        val fb = logits.floatBuffer
        val base = (seq - 1) * k
        var best = 0
        var bestValue = Float.NEGATIVE_INFINITY
        for (j in 0 until k) {
            val v = fb.get(base + j)
            if (v > bestValue) {
                bestValue = v
                best = j
            }
        }
        return logitMap[best]
    }

    override fun close() {
        encoder.close()
        decoder.close()
    }

    companion object {
        private const val EOS = 2
        private const val LAYERS = 12
        private const val HEADS = 16
        private const val HEAD_DIM = 64
        private const val MAX_INPUT = 256
        private val SENTENCE_END = Regex("(?<=[.!?…])\\s+")
    }
}

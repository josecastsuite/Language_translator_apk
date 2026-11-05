package com.yusuf.ceviri

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Çalınacak/gönderilecek ses: tek kanal, [-1, 1] aralığında örnekler. */
class Speech(val samples: FloatArray, val rate: Int)

fun newVad(models: ModelInstaller): Vad = Vad(
    null,
    VadModelConfig(
        sileroVadModelConfig = SileroVadModelConfig(
            model = models.path("vad/silero_vad.onnx"),
            threshold = 0.5f,
            minSilenceDuration = 0.6f,
            minSpeechDuration = 0.3f,
            windowSize = 512,
            maxSpeechDuration = 20f,
        ),
        sampleRate = RATE,
        numThreads = 1,
    ),
)

/** Whisper small (8 bit) ile konuşmayı yazıya çevirir. Her iki modda da telefonda çalışır. */
class Recognizer(models: ModelInstaller, threads: Int) {
    private val base = OfflineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = RATE, featureDim = 80),
        modelConfig = OfflineModelConfig(
            whisper = OfflineWhisperModelConfig(
                encoder = models.path("whisper/small-encoder.int8.onnx"),
                decoder = models.path("whisper/small-decoder.int8.onnx"),
                language = "",
                task = "transcribe",
            ),
            tokens = models.path("whisper/small-tokens.txt"),
            modelType = "whisper",
            numThreads = threads,
        ),
    )
    private val recognizer = OfflineRecognizer(null, base)

    /** language: "" = otomatik algıla. Döner: (metin, Whisper'ın kullandığı dil kodu). */
    @Synchronized
    fun recognize(samples: FloatArray, language: String): Pair<String, String> {
        recognizer.setConfig(
            base.copy(modelConfig = base.modelConfig.copy(whisper = base.modelConfig.whisper.copy(language = language)))
        )
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, RATE)
            recognizer.decode(stream)
            val r = recognizer.getResult(stream)
            return r.text.trim() to r.lang
        } finally {
            stream.release()
        }
    }
}

/** Piper sesleri (internetsiz mod); aynı anda en fazla iki ses bellekte tutulur. */
class TtsBank(private val models: ModelInstaller) {
    private val cache = LinkedHashMap<String, OfflineTts>()

    @Synchronized
    fun synth(voice: Voice, text: String, speed: Float): Speech {
        var tts = cache[voice.id]
        if (tts == null) {
            if (cache.size >= 2) {
                val oldest = cache.keys.first()
                cache.remove(oldest)?.release()
            }
            val dir = "tts/${voice.id}"
            tts = OfflineTts(
                null,
                OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        vits = OfflineTtsVitsModelConfig(
                            model = models.path("$dir/${voice.id}.onnx"),
                            tokens = models.path("$dir/tokens.txt"),
                            dataDir = models.path("espeak-ng-data"),
                        ),
                        numThreads = 2,
                    ),
                ),
            )
            cache[voice.id] = tts
        }
        val audio = tts.generate(text, 0, speed)
        return Speech(audio.samples, audio.sampleRate)
    }
}

class Engine(val models: ModelInstaller) {
    val recognizer = Recognizer(models, 4)
    private val piper = TtsBank(models)
    private var nllb: NllbTranslator? = null

    /** NLLB yalnız internetsiz mod ilk kez kullanıldığında belleğe alınır. */
    @Synchronized
    private fun offlineTranslator(): NllbTranslator =
        nllb ?: NllbTranslator(File(models.root, "nllb"), 4).also { nllb = it }

    fun translate(text: String, src: Lang, tgt: Lang, online: Boolean): String =
        if (online) GoogleTranslate.translate(text, src, tgt) else offlineTranslator().translate(text, src, tgt)

    /** voiceIndex: seçili modun ses listesindeki sıra. */
    fun speak(text: String, lang: Lang, online: Boolean, voiceIndex: Int, speed: Float): Speech =
        if (online) {
            val voices = EdgeVoices.forLang(lang)
            val mp3 = EdgeTts.synth(text, voices[voiceIndex.coerceIn(0, voices.size - 1)], speed)
            AudioDecoder.decodeBytes(mp3)
        } else {
            val voices = Voices.forLang(lang)
            piper.synth(voices[voiceIndex.coerceIn(0, voices.size - 1)], text, speed)
        }

    sealed class Outcome {
        data class Done(
            val src: Lang, val srcText: String, val tgt: Lang, val tgtText: String,
            val asrMs: Long, val mtMs: Long,
        ) : Outcome()

        data class Unknown(val code: String, val text: String) : Outcome()
        object Empty : Outcome()
    }

    /**
     * Bir konuşma bölümünü tanır ve çevirir.
     * fixed != null ise dil sabittir; değilse Whisper algılar ve [allowed] içine eşlenir.
     */
    fun understand(
        samples: FloatArray, fixed: Lang?, allowed: List<Lang>, online: Boolean, target: (Lang) -> Lang,
    ): Outcome {
        val t0 = System.currentTimeMillis()
        val first = recognizer.recognize(samples, fixed?.whisper ?: "")
        var text = first.first
        val src = fixed ?: Lang.fromWhisper(first.second, allowed)
        if (src == null) return if (isNoise(text)) Outcome.Empty else Outcome.Unknown(Lang.clean(first.second), text)
        if (fixed == null && Lang.clean(first.second) != src.whisper) {
            // Örn. Malayca algılandı → Endonezce olarak yeniden yaz
            text = recognizer.recognize(samples, src.whisper).first
        }
        if (isNoise(text)) return Outcome.Empty
        val t1 = System.currentTimeMillis()
        val tgt = target(src)
        val out = translate(text, src, tgt, online)
        return Outcome.Done(src, text, tgt, out, t1 - t0, System.currentTimeMillis() - t1)
    }

    /** Uzun kaydı konuşma bölümlerine ayırır (Whisper en fazla 30 sn alır). */
    fun splitBySpeech(samples: FloatArray): List<FloatArray> {
        val vad = newVad(models)
        val out = ArrayList<FloatArray>()
        try {
            var i = 0
            while (i < samples.size) {
                val end = minOf(i + 512, samples.size)
                vad.acceptWaveform(samples.copyOfRange(i, end))
                i = end
                while (!vad.empty()) {
                    out.add(vad.front().samples)
                    vad.pop()
                }
            }
            vad.flush()
            while (!vad.empty()) {
                out.add(vad.front().samples)
                vad.pop()
            }
        } finally {
            vad.release()
        }
        return out
    }

    companion object {
        /**
         * Whisper sessizlikte/gürültüde eğitim verisindeki altyazı kalıplarını uydurabiliyor.
         * Yalnız cümlenin TAMAMI bu kalıplardan biriyse atılır.
         */
        private val HALLUCINATIONS = setOf(
            "altyazı m.k",
            "izlediğiniz için teşekkürler",
            "izlediğiniz için teşekkür ederim",
            "abone olmayı unutmayın",
            "thank you for watching",
            "thanks for watching",
            "terima kasih telah menonton",
        )

        fun isNoise(text: String): Boolean {
            val t = text.trim().trim('.', '!', '?', ',', '…', ' ').lowercase().replace("i̇", "i")
            return t.isEmpty() || t.none { it.isLetterOrDigit() } || t in HALLUCINATIONS
        }
    }
}

object Core {
    @Volatile
    var engine: Engine? = null

    @Volatile
    var loading = false

    /** Bu açılışta İnternetli/İnternetsiz seçimi yapıldı mı (uygulama kapanınca yeniden sorulur). */
    @Volatile
    var modeChosen = false

    /** Tanıma/çeviri/seslendirme sırayla tek iş parçacığında yapılır. */
    val worker: ExecutorService = Executors.newSingleThreadExecutor()
}

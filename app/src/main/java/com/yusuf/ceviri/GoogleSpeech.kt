package com.yusuf.ceviri

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * İnternetli mod ses tanıması: Google'ın konuşma tanıması. Android 14+ dil değiştirme
 * özelliğiyle izin verilen diller arasında konuşulan dili kendisi bulur.
 *
 * Kesintisiz dinleme (Android 13+ "segmented session"): Google bir kez açılır, her cümle
 * onSegmentResults ile gelir, dinleme kapanmaz; bu yüzden her cümlede bip çalmaz.
 * Tanıyıcı bunu desteklemezse her cümleden sonra yeniden başlatılır (durum satırında yazar).
 *
 * Önce telefondaki (cihaz üstü) Google tanıyıcısı denenir; mikrofonu açamazsa Google'ın
 * standart tanıyıcısına geçilir. Tüm çağrılar ana iş parçacığından yapılmalıdır;
 * yalnız [pause] ve [resume] her iş parçacığından çağrılabilir.
 */
class GoogleSpeech(
    private val ctx: Context,
    private val allowed: List<Lang>,
    private val onPartial: (String) -> Unit,
    /** text boşsa konuşma yakalanmadı demektir; lang null ise tanıyıcı dil bildirmedi. */
    private val onFinal: (text: String, lang: Lang?, ms: Long) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var onDevice = true
    private var status: (String) -> Unit = {}
    private var detected: Lang? = null
    private var endAt = 0L
    private var readySeen = false
    private var downloadTried = false
    private var released = false
    private var finishing = false
    private var listenGeneration = 0

    /** Kesintisiz dinleme isteniyor mu (tanıyıcı desteklemediği görülürse kapanır). */
    private var segmentedWanted = Build.VERSION.SDK_INT >= 33

    /** Şu anki oturum kesintisiz mi? */
    private var segmented = false

    /** Çeviri hoparlörden okunurken gelen ses yok sayılır (telefon kendi sesini çevirmesin). */
    @Volatile
    private var ignoring = false

    @Volatile
    private var ignoreUntil = 0L

    private fun intent(languages: List<Lang>, segmentedSession: Boolean): Intent {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, languages[0].bcp47)
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        if (languages.size > 1 && Build.VERSION.SDK_INT >= 34) {
            val tags = ArrayList(languages.map { it.bcp47 })
            i.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
            i.putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, tags)
            i.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_QUICK_RESPONSE)
            i.putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, tags)
        }
        if (segmentedSession && Build.VERSION.SDK_INT >= 33) {
            i.putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS)
            i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, SESSION_MS)
        }
        return i
    }

    private fun engineName(): String {
        val e = if (onDevice) "Google (telefonda)" else "Google (standart)"
        return if (segmented) "$e · kesintisiz" else e
    }

    /**
     * Destek denetimi; eksik dil paketlerini Google'dan indirtir, hazır olunca [ready] çağrılır.
     * Google 4 saniyede yanıt vermezse beklemeden dinlemeye geçilir.
     */
    fun prepare(status: (String) -> Unit, ready: () -> Unit) {
        this.status = status
        if (Build.VERSION.SDK_INT < 34) {
            onError("Otomatik dil algılama için Android 14 veya üstü gerekli")
            return
        }
        onDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
        if (!onDevice && !SpeechRecognizer.isRecognitionAvailable(ctx)) {
            onError("Bu telefonda Google konuşma tanıması yok (Google uygulamasını güncelleyin)")
            return
        }
        val rec = create()
        var done = false
        fun proceed() {
            if (done || released) return
            done = true
            ready()
        }
        if (!onDevice) {
            proceed()
            return
        }
        main.postDelayed({ proceed() }, 4000)
        try {
            rec.checkRecognitionSupport(intent(allowed, false), ctx.mainExecutor, object : RecognitionSupportCallback {
                override fun onSupportResult(support: RecognitionSupport) {
                    Log.i(TAG, "destek: kurulu=${support.installedOnDeviceLanguages} desteklenen=${support.supportedOnDeviceLanguages.size} bekleyen=${support.pendingOnDeviceLanguages} online=${support.onlineLanguages.size}")
                    if (released || done) return
                    val installed = support.installedOnDeviceLanguages
                    val missing = allowed.filter { l -> installed.none { l.matches(it) } }
                    val downloadable = missing.filter { l ->
                        support.supportedOnDeviceLanguages.any { l.matches(it) } ||
                            support.pendingOnDeviceLanguages.any { l.matches(it) }
                    }
                    if (downloadable.isEmpty()) {
                        proceed()
                    } else {
                        done = true
                        download(downloadable, ready)
                    }
                }

                override fun onError(error: Int) {
                    Log.i(TAG, "destek sorgusu hata $error")
                    proceed()
                }
            })
        } catch (e: Exception) {
            proceed()
        }
    }

    private fun create(): SpeechRecognizer {
        recognizer?.destroy()
        val rec = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
        else SpeechRecognizer.createSpeechRecognizer(ctx)
        recognizer = rec
        Log.i(TAG, "tanıyıcı oluşturuldu onDevice=$onDevice")
        return rec
    }

    private fun download(missing: List<Lang>, then: () -> Unit) {
        val lang = missing.first()
        val rec = recognizer ?: return
        status("Google ${lang.label} konuşma paketi indiriliyor…")
        try {
            rec.triggerModelDownload(intent(listOf(lang), false), ctx.mainExecutor, object : ModelDownloadListener {
                override fun onProgress(completedPercent: Int) {
                    if (!released) status("Google ${lang.label} konuşma paketi indiriliyor %$completedPercent")
                }

                override fun onSuccess() {
                    if (released) return
                    val rest = missing.drop(1)
                    if (rest.isEmpty()) then() else download(rest, then)
                }

                override fun onScheduled() {
                    if (!released) {
                        status("Google ${lang.label} paketi sıraya alındı; Wi-Fi'da inecek.")
                        then()
                    }
                }

                override fun onError(error: Int) {
                    if (!released) {
                        status("Google ${lang.label} paketi indirilemedi (kod $error)")
                        then()
                    }
                }
            })
        } catch (e: Exception) {
            then()
        }
    }

    /** Cihaz üstü tanıyıcı çalışmazsa Google'ın standart tanıyıcısına geçer. */
    private fun switchToStandard(reason: String) {
        if (!onDevice || released) return
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            onError("Google konuşma tanıması başlatılamadı ($reason)")
            return
        }
        onDevice = false
        Log.i(TAG, "standart tanıyıcıya geçiliyor: $reason")
        create()
        status("Telefondaki tanıyıcı açılmadı ($reason); Google (standart) kullanılıyor.")
        listen()
    }

    private fun ignored(): Boolean = ignoring || System.currentTimeMillis() < ignoreUntil

    /** Çeviri okunmaya başlarken çağrılır: bu sırada duyulan ses çevrilmez. */
    fun pause() {
        ignoring = true
    }

    /**
     * Okuma bitince (ya da boş sonuçtan sonra) çağrılır. Kesintisiz oturumda yalnız yok sayma
     * kalkar; tek seferlik oturumda dinleme yeniden başlatılır.
     */
    fun resume() {
        Log.i(TAG, "devam (segmented=$segmented)")
        if (ignoring) ignoreUntil = System.currentTimeMillis() + ECHO_TAIL_MS
        ignoring = false
        main.post {
            if (!released && !finishing) {
                if (segmented) status("Dinleniyor… konuşabilirsiniz · ${engineName()}") else listen()
            }
        }
    }

    /** Dinlemeyi başlatır. Kesintisiz modda her cümle onFinal ile gelir, dinleme sürer. */
    fun listen() {
        val rec = recognizer ?: return
        detected = null
        endAt = 0L
        readySeen = false
        segmented = segmentedWanted
        val gen = ++listenGeneration

        fun deliver(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            val reported = results?.getString(SpeechRecognizer.DETECTED_LANGUAGE)?.let { fromTag(it) }
            val lang = if (allowed.size == 1) allowed[0] else reported ?: detected
            val ms = if (endAt > 0) System.currentTimeMillis() - endAt else 0L
            detected = null
            endAt = 0L
            Log.i(TAG, "teslim: '$text' dil=$lang yoksay=${ignored()}")
            if (ignored() && !finishing) {
                // Kendi okuduğumuz çeviri duyuldu; at.
                if (!segmented) onFinal("", null, 0L)
                return
            }
            onFinal(text.trim(), lang, ms)
        }

        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.i(TAG, "hazır (gen=$gen segmented=$segmented)")
                if (released || gen != listenGeneration) return
                readySeen = true
                status("Dinleniyor… konuşabilirsiniz · ${engineName()}")
            }

            override fun onBeginningOfSpeech() {
                Log.i(TAG, "konuşma başladı")
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.i(TAG, "konuşma bitti")
                endAt = System.currentTimeMillis()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (released || gen != listenGeneration || ignored()) return
                val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!t.isNullOrBlank()) onPartial(t)
            }

            override fun onLanguageDetection(results: Bundle) {
                Log.i(TAG, "dil algılandı: ${results.getString(SpeechRecognizer.DETECTED_LANGUAGE)} güven=${results.getInt(SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL)} geçiş=${results.getInt(SpeechRecognizer.LANGUAGE_SWITCH_RESULT)}")
                results.getString(SpeechRecognizer.DETECTED_LANGUAGE)?.let { tag -> fromTag(tag)?.let { detected = it } }
            }

            override fun onSegmentResults(segmentResults: Bundle) {
                Log.i(TAG, "bölüm sonucu: ${segmentResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)} dil=${segmentResults.getString(SpeechRecognizer.DETECTED_LANGUAGE)}")
                if (released || gen != listenGeneration) return
                endAt = if (endAt > 0) endAt else System.currentTimeMillis()
                deliver(segmentResults)
            }

            override fun onEndOfSegmentedSession() {
                Log.i(TAG, "kesintisiz oturum bitti")
                if (released || gen != listenGeneration) return
                if (finishing) onFinal("", null, 0L) else main.postDelayed({ if (!released && gen == listenGeneration) listen() }, 200)
            }

            override fun onResults(results: Bundle?) {
                Log.i(TAG, "sonuç: ${results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)} dil=${results?.getString(SpeechRecognizer.DETECTED_LANGUAGE)} segmented=$segmented")
                if (released || gen != listenGeneration) return
                if (segmented) {
                    // Tanıyıcı kesintisiz modu desteklemiyor: bundan sonra tek seferlik oturumlar.
                    segmentedWanted = false
                    segmented = false
                }
                deliver(results)
            }

            override fun onError(error: Int) {
                Log.i(TAG, "hata kodu $error (gen=$gen segmented=$segmented ready=$readySeen)")
                if (released || gen != listenGeneration) return
                if (finishing) {
                    onFinal("", null, 0L)
                    return
                }
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        if (segmented) main.postDelayed({ if (!released && gen == listenGeneration) listen() }, 800)
                        else onFinal("", null, 0L)
                    }
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> main.postDelayed({ if (!released && gen == listenGeneration) listen() }, 500)
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> this@GoogleSpeech.onError("Mikrofon izni yok")
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                        if (onDevice && !downloadTried) {
                            downloadTried = true
                            download(allowed) { listen() }
                        } else if (onDevice) {
                            switchToStandard("dil paketi yok, kod $error")
                        } else {
                            this@GoogleSpeech.onError("Google bu dili tanıyamıyor (kod $error)")
                        }
                    }
                    else -> {
                        if (segmented && readySeen && error == SpeechRecognizer.ERROR_CLIENT) {
                            // Kesintisiz oturum bu tanıyıcıda kurulamadı: tek seferliğe geç.
                            segmentedWanted = false
                            main.postDelayed({ if (!released && gen == listenGeneration) listen() }, 300)
                        } else if (onDevice && !readySeen) {
                            switchToStandard("kod $error")
                        } else {
                            this@GoogleSpeech.onError("Google konuşma tanıma hatası (kod $error)")
                        }
                    }
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        Log.i(TAG, "dinleme başlıyor gen=$gen segmented=$segmented diller=${allowed.map { it.bcp47 }}")
        rec.startListening(intent(allowed, segmented))
        // Mikrofon 3 saniyede açılmazsa (hiç yanıt yoksa) standart tanıyıcıya geç.
        main.postDelayed({
            if (!released && gen == listenGeneration && !readySeen && onDevice) switchToStandard("yanıt yok")
        }, 3000)
    }

    /** Konuşmayı bitirir; elde olan son sonuç ve ardından boş bir onFinal gelir. */
    fun finish() {
        finishing = true
        recognizer?.stopListening()
    }

    fun release() {
        released = true
        main.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
    }

    private fun fromTag(tag: String): Lang? = Lang.entries.firstOrNull { it.matches(tag) }?.takeIf { it in allowed }

    companion object {
        /** Kesintisiz oturum süresi; dolunca sessizce yeniden açılır. */
        private const val SESSION_MS = 30L * 60L * 1000L

        /** Okuma bittikten sonra hoparlör yankısı için kısa bekleme. */
        private const val ECHO_TAIL_MS = 500L
        const val TAG = "Cevirmen"
    }
}

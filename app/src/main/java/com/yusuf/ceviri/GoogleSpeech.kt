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

/**
 * İnternetli mod ses tanıması: Google'ın konuşma tanıması. Android 14+ dil değiştirme
 * özelliğiyle izin verilen diller arasında konuşulan dili kendisi bulur.
 * Önce telefondaki (cihaz üstü) Google tanıyıcısı denenir; mikrofonu açamazsa Google'ın
 * standart tanıyıcısına geçilir ve durum satırında hangisinin kullanıldığı yazılır.
 * Tüm çağrılar ana iş parçacığından yapılmalıdır.
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
    private var listenGeneration = 0

    private fun intent(languages: List<Lang>): Intent {
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
        return i
    }

    private fun engineName() = if (onDevice) "Google (telefonda)" else "Google (standart)"

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
            rec.checkRecognitionSupport(intent(allowed), ctx.mainExecutor, object : RecognitionSupportCallback {
                override fun onSupportResult(support: RecognitionSupport) {
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
        return rec
    }

    private fun download(missing: List<Lang>, then: () -> Unit) {
        val lang = missing.first()
        val rec = recognizer ?: return
        status("Google ${lang.label} konuşma paketi indiriliyor…")
        try {
            rec.triggerModelDownload(intent(listOf(lang)), ctx.mainExecutor, object : ModelDownloadListener {
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
        create()
        status("Telefondaki tanıyıcı açılmadı ($reason); ${engineName()} kullanılıyor.")
        listen()
    }

    /** Bir konuşma dinler; konuşma bitince onFinal bir kez çağrılır. */
    fun listen() {
        val rec = recognizer ?: return
        detected = null
        endAt = 0L
        readySeen = false
        val gen = ++listenGeneration
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (released || gen != listenGeneration) return
                readySeen = true
                status("Dinleniyor… konuşabilirsiniz · ${engineName()}")
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                endAt = System.currentTimeMillis()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!released && !t.isNullOrBlank()) onPartial(t)
            }

            override fun onLanguageDetection(results: Bundle) {
                results.getString(SpeechRecognizer.DETECTED_LANGUAGE)?.let { tag -> fromTag(tag)?.let { detected = it } }
            }

            override fun onResults(results: Bundle?) {
                if (released || gen != listenGeneration) return
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                val reported = results?.getString(SpeechRecognizer.DETECTED_LANGUAGE)?.let { fromTag(it) }
                val lang = if (allowed.size == 1) allowed[0] else reported ?: detected
                val ms = if (endAt > 0) System.currentTimeMillis() - endAt else 0L
                onFinal(text.trim(), lang, ms)
            }

            override fun onError(error: Int) {
                if (released || gen != listenGeneration) return
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> onFinal("", null, 0L)
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> main.postDelayed({ if (!released && gen == listenGeneration) listen() }, 300)
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
                        if (onDevice && !readySeen) switchToStandard("kod $error")
                        else this@GoogleSpeech.onError("Google konuşma tanıma hatası (kod $error)")
                    }
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        rec.startListening(intent(allowed))
        // Mikrofon 3 saniyede açılmazsa (hiç yanıt yoksa) standart tanıyıcıya geç.
        main.postDelayed({
            if (!released && gen == listenGeneration && !readySeen && onDevice) switchToStandard("yanıt yok")
        }, 3000)
    }

    /** Konuşmayı hemen bitirir; elde olan sonuç onFinal ile gelir. */
    fun finish() {
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
}

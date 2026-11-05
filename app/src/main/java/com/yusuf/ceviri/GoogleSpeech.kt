package com.yusuf.ceviri

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * İnternetli mod ses tanıması: Google'ın telefondaki konuşma tanıması
 * (Live Caption / klavye sesli yazma ile aynı motor). Android 14+ dil değiştirme
 * özelliğiyle izin verilen diller arasında konuşulan dili kendisi bulur.
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
    private var recognizer: SpeechRecognizer? = null
    private var detected: Lang? = null
    private var endAt = 0L
    private var released = false

    private fun intent(languages: List<Lang>): Intent {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, languages[0].bcp47)
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        if (languages.size > 1 && Build.VERSION.SDK_INT >= 34) {
            val tags = ArrayList(languages.map { it.bcp47 })
            i.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
            i.putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, tags)
            i.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_QUICK_RESPONSE)
            i.putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, tags)
        }
        return i
    }

    /** Destek denetimi; eksik dil paketlerini Google'dan indirtir, hazır olunca [ready] çağrılır. */
    fun prepare(status: (String) -> Unit, ready: () -> Unit) {
        if (Build.VERSION.SDK_INT < 34) {
            onError("Otomatik dil algılama için Android 14 veya üstü gerekli")
            return
        }
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) {
            onError("Bu telefonda Google konuşma tanıması yok (Google uygulamasını güncelleyin)")
            return
        }
        val rec = SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
        recognizer = rec
        rec.checkRecognitionSupport(intent(allowed), ctx.mainExecutor, object : RecognitionSupportCallback {
            override fun onSupportResult(support: RecognitionSupport) {
                if (released) return
                val installed = support.installedOnDeviceLanguages
                val missing = allowed.filter { l -> installed.none { l.matches(it) } }
                if (missing.isEmpty()) {
                    ready()
                    return
                }
                val unsupported = missing.filter { l ->
                    support.supportedOnDeviceLanguages.none { l.matches(it) } &&
                        support.pendingOnDeviceLanguages.none { l.matches(it) }
                }
                if (unsupported.isNotEmpty()) {
                    this@GoogleSpeech.onError("Google konuşma tanıması bu dili desteklemiyor: " + unsupported.joinToString { it.label })
                    return
                }
                download(missing, status, ready)
            }

            override fun onError(error: Int) {
                if (!released) this@GoogleSpeech.onError("Google konuşma tanıması sorgulanamadı (kod $error)")
            }
        })
    }

    private fun download(missing: List<Lang>, status: (String) -> Unit, ready: () -> Unit) {
        val lang = missing.first()
        val rec = recognizer ?: return
        status("Google ${lang.label} konuşma paketi indiriliyor…")
        rec.triggerModelDownload(intent(listOf(lang)), ctx.mainExecutor, object : ModelDownloadListener {
            override fun onProgress(completedPercent: Int) {
                if (!released) status("Google ${lang.label} konuşma paketi indiriliyor %$completedPercent")
            }

            override fun onSuccess() {
                if (released) return
                val rest = missing.drop(1)
                if (rest.isEmpty()) ready() else download(rest, status, ready)
            }

            override fun onScheduled() {
                if (!released) this@GoogleSpeech.onError("Google ${lang.label} paketini sonra indirecek (Wi-Fi'a bağlanıp tekrar deneyin)")
            }

            override fun onError(error: Int) {
                if (!released) this@GoogleSpeech.onError("Google ${lang.label} paketi indirilemedi (kod $error)")
            }
        })
    }

    /** Bir konuşma dinler; konuşma bitince onFinal bir kez çağrılır. */
    fun listen() {
        val rec = recognizer ?: return
        detected = null
        endAt = 0L
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
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
                if (released) return
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                val reported = results?.getString(SpeechRecognizer.DETECTED_LANGUAGE)?.let { fromTag(it) }
                val lang = if (allowed.size == 1) allowed[0] else reported ?: detected
                val ms = if (endAt > 0) System.currentTimeMillis() - endAt else 0L
                onFinal(text.trim(), lang, ms)
            }

            override fun onError(error: Int) {
                if (released) return
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> onFinal("", null, 0L)
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> this@GoogleSpeech.onError("Mikrofon izni yok")
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> this@GoogleSpeech.onError("Google konuşma paketi hazır değil (kod $error)")
                    else -> this@GoogleSpeech.onError("Google konuşma tanıma hatası (kod $error)")
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        rec.startListening(intent(allowed))
    }

    /** Konuşmayı hemen bitirir; elde olan sonuç onFinal ile gelir. */
    fun finish() {
        recognizer?.stopListening()
    }

    fun release() {
        released = true
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
    }

    private fun fromTag(tag: String): Lang? = Lang.entries.firstOrNull { it.matches(tag) }?.takeIf { it in allowed }
}

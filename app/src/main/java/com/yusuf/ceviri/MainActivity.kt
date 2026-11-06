package com.yusuf.ceviri

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences

    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var body: LinearLayout
    private lateinit var modeGroup: RadioGroup
    private lateinit var logBox: LinearLayout
    private lateinit var logScroll: ScrollView
    private val modeIds = IntArray(3) { View.generateViewId() }
    private val panels = ArrayList<View>()

    private lateinit var listenSrc: Spinner
    private lateinit var listenTgt: Spinner
    private lateinit var listenBtn: Button
    private lateinit var convA: Spinner
    private lateinit var convB: Spinner
    private lateinit var convBtn: Button
    private lateinit var inTgt: Spinner
    private lateinit var outMine: Spinner
    private lateinit var outTheirs: Spinner
    private lateinit var recBtn: Button
    private lateinit var speedLabel: TextView
    private lateinit var voiceBox: LinearLayout
    private lateinit var chooser: LinearLayout
    private lateinit var modeLabel: TextView

    private var mic: MicListener? = null
    private var google: GoogleSpeech? = null
    private var googleRec: GoogleSpeech? = null
    private val recParts = ArrayList<String>()
    private var recMs = 0L
    private var recStopping = false

    /** Son okunan çeviri ve okumanın bittiği an: hoparlör yankısını ayırt etmek için. */
    @Volatile
    private var lastSpokenText = ""

    @Volatile
    private var lastSpokenEnd = 0L
    private var activeButton: Button? = null
    private var activeButtonText = ""
    private var recorder: MicRecorder? = null
    private val session = AtomicInteger(0)
    private var pendingUri: Uri? = null

    @Volatile
    private var lastIncoming: Pair<Lang, String>? = null

    @Volatile
    private var outgoingAudio: Speech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("ayarlar", MODE_PRIVATE)
        BeepGuard.stop(this)
        setContentView(buildUi())
        handleIntent(intent)
        startLoading()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        stopAll()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- yükleme

    private fun startLoading() {
        if (Core.engine != null) {
            onReady()
            return
        }
        if (Core.loading) return
        Core.loading = true
        Thread({
            try {
                val installer = ModelInstaller(applicationContext)
                if (!installer.isInstalled()) {
                    post {
                        progress.visibility = View.VISIBLE
                        setStatus("İlk kurulum: modeller hazırlanıyor (yalnız bir kez)…")
                    }
                    var last = 0L
                    installer.install { done, total ->
                        val now = System.currentTimeMillis()
                        if (now - last > 300 || done == total) {
                            last = now
                            val pct = (done * 100 / total).toInt()
                            post {
                                progress.progress = pct
                                setStatus("İlk kurulum: modeller hazırlanıyor %$pct (${done / MB} / ${total / MB} MB)")
                            }
                        }
                    }
                }
                post {
                    progress.visibility = View.GONE
                    setStatus("Modeller belleğe yükleniyor…")
                }
                val t0 = System.currentTimeMillis()
                Core.engine = Engine(installer)
                val sec = (System.currentTimeMillis() - t0) / 1000
                post {
                    setStatus("Hazır (yükleme $sec sn).")
                    onReady()
                }
            } catch (t: Throwable) {
                post { showError("Modeller yüklenemedi", t) }
            } finally {
                Core.loading = false
            }
        }, "yukleme").start()
    }

    private fun onReady() {
        if (!Core.modeChosen) {
            setStatus("Hazır. Nasıl çalışacağını seçin.")
            return
        }
        body.visibility = View.VISIBLE
        val uri = pendingUri
        if (uri != null) {
            pendingUri = null
            translateIncoming(uri)
        }
    }

    // ---------------------------------------------------------------- arayüz

    private fun buildUi(): View {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(12), dp(12), dp(12), dp(8))
        root.fitsSystemWindows = true
        root.setBackgroundColor(Color.WHITE)

        root.addView(text("Çevirmen · Türkçe · Endonezce · İngilizce", 18f, bold = true))
        status = text("Başlatılıyor…", 13f)
        status.setTextColor(Color.DKGRAY)
        root.addView(status)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progress.max = 100
        progress.visibility = View.GONE
        root.addView(progress)

        chooser = buildChooser()
        root.addView(chooser)

        body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.visibility = View.GONE
        root.addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f))

        modeGroup = RadioGroup(this)
        modeGroup.orientation = RadioGroup.HORIZONTAL
        MODE_NAMES.forEachIndexed { i, name ->
            val rb = RadioButton(this)
            rb.id = modeIds[i]
            rb.text = name
            rb.textSize = 13f
            modeGroup.addView(rb, RadioGroup.LayoutParams(0, WRAP, 1f))
        }
        val modeRow = LinearLayout(this)
        modeRow.orientation = LinearLayout.HORIZONTAL
        modeLabel = text("", 12f)
        modeLabel.setTextColor(Color.DKGRAY)
        modeRow.addView(modeLabel, LinearLayout.LayoutParams(0, WRAP, 1f))
        modeRow.addView(button("Modu değiştir") { askMode() })
        body.addView(modeRow)
        updateModeLabel()
        body.addView(modeGroup)

        val controls = LinearLayout(this)
        controls.orientation = LinearLayout.VERTICAL
        val controlsScroll = ScrollView(this)
        controlsScroll.addView(controls)
        val savedSplit = prefs.getInt("split", 0)
        body.addView(
            controlsScroll,
            if (savedSplit > 0) LinearLayout.LayoutParams(MATCH, savedSplit) else LinearLayout.LayoutParams(MATCH, 0, 1f),
        )
        panels.add(buildListenPanel())
        panels.add(buildConversationPanel())
        panels.add(buildVoiceMessagePanel())
        for (p in panels) controls.addView(p)
        controls.addView(buildVoiceSettings())
        body.addView(buildSplitHandle(controlsScroll))

        val logHeader = LinearLayout(this)
        logHeader.orientation = LinearLayout.HORIZONTAL
        logHeader.addView(text("Konuşma geçmişi", 14f, bold = true), LinearLayout.LayoutParams(0, WRAP, 1f))
        logHeader.addView(button("Temizle") { logBox.removeAllViews() })
        body.addView(logHeader)
        logBox = LinearLayout(this)
        logBox.orientation = LinearLayout.VERTICAL
        logScroll = ScrollView(this)
        logScroll.setBackgroundColor(Color.rgb(244, 244, 244))
        logScroll.addView(logBox)
        body.addView(logScroll, LinearLayout.LayoutParams(MATCH, 0, 1.2f))

        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            val idx = modeIds.indexOf(checkedId)
            if (idx >= 0) selectMode(idx)
        }
        modeGroup.check(modeIds[prefs.getInt("mode", 0).coerceIn(0, 2)])
        return root
    }

    private fun buildListenPanel(): View {
        val p = panel()
        p.addView(text("Mikrofondan dinler, çeviriyi hoparlörden söyler.", 13f))
        listenSrc = spinner(listOf("Otomatik algıla") + LANG_LABELS, "listen_src", 0)
        listenTgt = spinner(LANG_LABELS, "listen_tgt", 0)
        p.addView(row(labeled("Konuşulan dil", listenSrc), labeled("Çeviri dili", listenTgt)))
        listenBtn = button("Dinlemeyi başlat") { toggleListen() }
        p.addView(listenBtn)
        return p
    }

    private fun buildConversationPanel(): View {
        val p = panel()
        p.addView(text("Telefonu ortaya koyun. Kim hangi dilde konuşursa diğer dile çevrilip söylenir. Sırayla konuşun.", 13f))
        convA = spinner(LANG_LABELS, "conv_a", 0)
        convB = spinner(LANG_LABELS, "conv_b", 1)
        p.addView(row(labeled("1. dil", convA), labeled("2. dil", convB)))
        convBtn = button("Konuşmayı başlat") { toggleConversation() }
        p.addView(convBtn)
        return p
    }

    private fun buildVoiceMessagePanel(): View {
        val p = panel()
        p.addView(text("Gelen sesli mesaj", 15f, bold = true))
        p.addView(text("WhatsApp'ta sesli mesajı seçin → Paylaş → Çevirmen. Ya da dosyadan seçin.", 13f))
        inTgt = spinner(LANG_LABELS, "in_tgt", 0)
        p.addView(labeled("Çeviri dili", inTgt))
        p.addView(row(button("Dosyadan seç") { pickFile() }, button("Çeviriyi seslendir") { speakIncoming() }))

        p.addView(text("Giden sesli mesaj", 15f, bold = true))
        outMine = spinner(LANG_LABELS, "out_mine", 0)
        outTheirs = spinner(LANG_LABELS, "out_theirs", 1)
        p.addView(row(labeled("Benim dilim", outMine), labeled("Karşı tarafın dili", outTheirs)))
        recBtn = button("Kaydı başlat") { toggleRecord() }
        p.addView(recBtn)
        p.addView(row(button("Dinle") { playOutgoing() }, button("WhatsApp'a gönder") { shareOutgoing() }))
        return p
    }

    /** Açılışta sorulur: İnternetli mi, İnternetsiz mi? */
    private fun buildChooser(): LinearLayout {
        val p = panel()
        p.addView(text("Nasıl çalışsın?", 17f, bold = true))
        p.addView(text("Ses tanıma her iki durumda da telefonda yapılır.", 13f))
        p.addView(button("İnternetli (hızlı) · çeviri Google, ses Edge") { chooseMode(true) })
        p.addView(button("İnternetsiz · her şey telefonda") { chooseMode(false) })
        p.visibility = if (Core.modeChosen) View.GONE else View.VISIBLE
        return p
    }

    private fun askMode() {
        stopAll()
        body.visibility = View.GONE
        chooser.visibility = View.VISIBLE
    }

    private fun chooseMode(online: Boolean) {
        prefs.edit().putBoolean("online", online).apply()
        Core.modeChosen = true
        chooser.visibility = View.GONE
        refreshVoices()
        updateModeLabel()
        if (Core.engine != null) onReady() else setStatus("Modeller yükleniyor, lütfen bekleyin…")
    }

    private fun updateModeLabel() {
        modeLabel.text = if (isOnline()) "Mod: İnternetli (çeviri Google, ses Edge)" else "Mod: İnternetsiz (her şey telefonda)"
    }

    /** Ayarlar ile konuşma geçmişi arasındaki tutamak: sürükleyerek alanları büyüt/küçült. */
    private fun buildSplitHandle(top: View): View {
        val h = TextView(this)
        h.text = "▲  sürükleyerek büyüt / küçült  ▼"
        h.gravity = Gravity.CENTER
        h.textSize = 11f
        h.setTextColor(Color.DKGRAY)
        h.setBackgroundColor(Color.rgb(222, 222, 222))
        h.setPadding(0, dp(8), 0, dp(8))
        var startY = 0f
        var startH = 0
        h.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = e.rawY
                    startH = top.height
                }
                MotionEvent.ACTION_MOVE -> {
                    val min = dp(60)
                    val max = maxOf(min, body.height - dp(170))
                    val lp = top.layoutParams as LinearLayout.LayoutParams
                    lp.height = (startH + (e.rawY - startY)).toInt().coerceIn(min, max)
                    lp.weight = 0f
                    top.layoutParams = lp
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    prefs.edit().putInt("split", top.layoutParams.height).apply()
                    v.performClick()
                }
            }
            true
        }
        return h
    }

    private fun refreshVoices() {
        voiceBox.removeAllViews()
        for (lang in Lang.entries) {
            voiceBox.addView(labeled("${lang.label} sesi", spinner(voiceLabels(lang), voiceKey(lang), 0)))
        }
    }

    private fun buildVoiceSettings(): View {
        val p = panel()
        p.addView(text("Ses ayarları", 15f, bold = true))
        voiceBox = LinearLayout(this)
        voiceBox.orientation = LinearLayout.VERTICAL
        p.addView(voiceBox)
        refreshVoices()
        speedLabel = text("", 13f)
        p.addView(speedLabel)
        val bar = SeekBar(this)
        bar.max = 80
        bar.progress = prefs.getInt("speed", 40).coerceIn(0, 80)
        updateSpeedLabel(bar.progress)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                prefs.edit().putInt("speed", value).apply()
                updateSpeedLabel(value)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        p.addView(bar)
        return p
    }

    private fun updateSpeedLabel(value: Int) {
        speedLabel.text = String.format(Locale.US, "Konuşma hızı: %.1fx", 0.6f + value / 100f)
    }

    private fun selectMode(idx: Int) {
        stopAll()
        prefs.edit().putInt("mode", idx).apply()
        panels.forEachIndexed { i, v -> v.visibility = if (i == idx) View.VISIBLE else View.GONE }
    }

    // ---------------------------------------------------------------- mod 1 ve 2: canlı dinleme

    private fun toggleListen() {
        if (mic != null || google != null) {
            stopAll()
            return
        }
        if (!ensureMic()) return
        val pos = listenSrc.selectedItemPosition
        val fixed = if (pos <= 0) null else Lang.entries[pos - 1]
        val tgt = Lang.entries[listenTgt.selectedItemPosition]
        if (isOnline()) {
            startGoogle(listenBtn, "Dinlemeyi durdur", if (fixed != null) listOf(fixed) else Lang.entries.toList()) { tgt }
            return
        }
        startMic(listenBtn, "Dinlemeyi durdur", fixed, Lang.entries.toList()) { tgt }
    }

    private fun toggleConversation() {
        if (mic != null || google != null) {
            stopAll()
            return
        }
        if (!ensureMic()) return
        val a = Lang.entries[convA.selectedItemPosition]
        val b = Lang.entries[convB.selectedItemPosition]
        if (a == b) {
            setStatus("İki farklı dil seçin.", error = true)
            return
        }
        if (isOnline()) {
            startGoogle(convBtn, "Konuşmayı durdur", listOf(a, b)) { src -> if (src == a) b else a }
            return
        }
        startMic(convBtn, "Konuşmayı durdur", null, listOf(a, b)) { src -> if (src == a) b else a }
    }

    // ---------------------------------------------------------------- İnternetli canlı dinleme (Google)

    private fun startGoogle(btn: Button, stopText: String, allowed: List<Lang>, target: (Lang) -> Lang) {
        val engine = Core.engine ?: return
        val token = session.incrementAndGet()
        lateinit var g: GoogleSpeech
        g = GoogleSpeech(
            this, allowed,
            onPartial = { t -> if (session.get() == token) setStatus("🎤 $t") },
            onFinal = { text, lang, ms -> if (session.get() == token) onGoogleFinal(token, g, engine, text, lang, ms, allowed, target) },
            onError = { m ->
                if (session.get() == token) {
                    showError("Ses tanıma", IllegalStateException(m))
                    stopAll()
                }
            },
        )
        google = g
        BeepGuard.start(this)
        activeButton = btn
        activeButtonText = btn.text.toString()
        btn.text = stopText
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setStatus("Google konuşma tanıması hazırlanıyor…")
        g.prepare({ msg -> if (session.get() == token) setStatus(msg) }) {
            if (session.get() == token) {
                setStatus("Dinleniyor…")
                g.listen()
            }
        }
    }

    private fun onGoogleFinal(
        token: Int, g: GoogleSpeech, engine: Engine, text: String, lang: Lang?, ms: Long,
        allowed: List<Lang>, target: (Lang) -> Lang,
    ) {
        if (text.isBlank() || Engine.isNoise(text) || isEcho(text)) {
            g.resume()
            return
        }
        setStatus("Çevriliyor…")
        Core.worker.execute {
            try {
                // Tanıyıcı dili bildirmediyse metnin dilini Google Çeviri belirler.
                val src = lang ?: GoogleTranslate.detect(text, allowed)
                if (src == null) {
                    post { addNote("Dil anlaşılamadı. Lütfen tekrar söyleyin.", text) }
                } else {
                    val tgt = target(src)
                    val t0 = System.currentTimeMillis()
                    val out = engine.translate(text, src, tgt, true)
                    val r = Engine.Outcome.Done(src, text, tgt, out, ms, System.currentTimeMillis() - t0)
                    post { addTurn(r, null) }
                    if (src != tgt && session.get() == token) {
                        val audio = engine.speak(out, tgt, true, voiceIndex(tgt), speed())
                        lastSpokenText = out
                        g.pause()
                        try {
                            Player.play(audio.samples, audio.rate, alarm = true) { session.get() != token }
                        } finally {
                            lastSpokenEnd = System.currentTimeMillis()
                        }
                    }
                }
            } catch (t: Throwable) {
                post { showError("Çeviri hatası", t) }
            } finally {
                if (session.get() == token) g.resume()
            }
        }
    }

    /**
     * Okuma bittikten sonraki birkaç saniyede gelen ve az önce okunan çeviriyle büyük ölçüde
     * aynı kelimelerden oluşan cümle, telefonun kendi sesinin yankısıdır; çevrilmez.
     */
    private fun isEcho(text: String): Boolean {
        val spoken = lastSpokenText
        if (spoken.isEmpty() || System.currentTimeMillis() - lastSpokenEnd > ECHO_WINDOW_MS) return false
        val a = words(text)
        val b = words(spoken)
        if (a.isEmpty() || b.isEmpty()) return false
        val common = a.count { it in b }
        return common.toFloat() / minOf(a.size, b.size) >= 0.6f
    }

    private fun words(s: String): Set<String> =
        s.lowercase().replace("i\u0307", "i").split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.toSet()

    private fun startMic(btn: Button, stopText: String, fixed: Lang?, allowed: List<Lang>, target: (Lang) -> Lang) {
        val engine = Core.engine ?: return
        val token = session.incrementAndGet()
        val online = isOnline()
        val listener = MicListener(
            engine.models,
            onSegment = { l, samples -> Core.worker.execute { handleSegment(token, l, engine, samples, fixed, allowed, online, target) } },
            onSpeech = { speaking -> post { if (session.get() == token) setStatus(if (speaking) "Konuşma algılandı…" else "Dinleniyor…") } },
            onError = { t -> post { showError("Mikrofon hatası", t); stopAll() } },
        )
        mic = listener
        activeButton = btn
        activeButtonText = btn.text.toString()
        btn.text = stopText
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        listener.start()
        setStatus("Dinleniyor…")
    }

    private fun handleSegment(
        token: Int, listener: MicListener, engine: Engine, samples: FloatArray,
        fixed: Lang?, allowed: List<Lang>, online: Boolean, target: (Lang) -> Lang,
    ) {
        if (session.get() != token) return
        post { setStatus("Çevriliyor…") }
        try {
            when (val r = engine.understand(samples, fixed, allowed, online, target)) {
                is Engine.Outcome.Empty -> {}
                is Engine.Outcome.Unknown -> post {
                    addNote("Dil anlaşılamadı (${r.code}). Lütfen tekrar söyleyin.", r.text)
                }
                is Engine.Outcome.Done -> {
                    post { addTurn(r, null) }
                    if (r.src != r.tgt && session.get() == token) {
                        val audio = engine.speak(r.tgtText, r.tgt, online, voiceIndex(r.tgt), speed())
                        listener.muted = true
                        try {
                            Player.play(audio.samples, audio.rate) { session.get() != token }
                        } finally {
                            listener.muted = false
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            post { showError("Çeviri hatası", t) }
        } finally {
            post { if (session.get() == token) setStatus("Dinleniyor…") }
        }
    }

    private fun stopAll() {
        session.incrementAndGet()
        mic?.stop()
        mic = null
        google?.release()
        google = null
        googleRec?.release()
        googleRec = null
        recStopping = false
        BeepGuard.stop(this)
        activeButton?.text = activeButtonText
        activeButton = null
        recorder?.stop()
        recorder = null
        if (::recBtn.isInitialized) recBtn.text = "Kaydı başlat"
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ---------------------------------------------------------------- mod 3: sesli mesaj

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        val uri = when (i.action) {
            Intent.ACTION_SEND -> streamUri(i)
            Intent.ACTION_VIEW -> i.data
            else -> null
        } ?: return
        modeGroup.check(modeIds[2])
        if (Core.engine != null && Core.modeChosen) {
            translateIncoming(uri)
        } else {
            pendingUri = uri
            setStatus("Sesli mesaj alındı; modeller yüklenince çevrilecek.")
        }
    }

    @Suppress("DEPRECATION")
    private fun streamUri(i: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)

    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "audio/*"
        startActivityForResult(i, REQ_PICK)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            if (Core.engine != null) translateIncoming(uri)
        }
    }

    private fun translateIncoming(uri: Uri) {
        val engine = Core.engine ?: return
        val tgt = Lang.entries[inTgt.selectedItemPosition]
        val online = isOnline()
        setStatus("Sesli mesaj okunuyor…")
        Core.worker.execute {
            try {
                val samples = AudioDecoder.decodeToMono16k(applicationContext, uri)
                val segments = engine.splitBySpeech(samples)
                if (segments.isEmpty()) {
                    post { setStatus("Mesajda konuşma bulunamadı.", error = true) }
                    return@execute
                }
                val src = ArrayList<String>()
                val dst = ArrayList<String>()
                var srcLang: Lang? = null
                var asr = 0L
                var mt = 0L
                segments.forEachIndexed { idx, seg ->
                    post { setStatus("Sesli mesaj çevriliyor ${idx + 1}/${segments.size}…") }
                    when (val r = engine.understand(seg, null, Lang.entries.toList(), online) { tgt }) {
                        is Engine.Outcome.Done -> {
                            src.add(r.srcText)
                            dst.add(r.tgtText)
                            srcLang = r.src
                            asr += r.asrMs
                            mt += r.mtMs
                        }
                        is Engine.Outcome.Unknown -> post { addNote("Bir bölümün dili anlaşılamadı (${r.code}).", r.text) }
                        is Engine.Outcome.Empty -> {}
                    }
                }
                val lang = srcLang
                if (lang == null) {
                    post { setStatus("Mesaj çevrilemedi.", error = true) }
                    return@execute
                }
                val res = Engine.Outcome.Done(lang, src.joinToString(" "), tgt, dst.joinToString(" "), asr, mt)
                lastIncoming = tgt to res.tgtText
                post {
                    addTurn(res, "Gelen mesaj")
                    setStatus("Sesli mesaj çevrildi.")
                }
            } catch (t: Throwable) {
                post { showError("Sesli mesaj çevrilemedi", t) }
            }
        }
    }

    private fun speakIncoming() {
        val engine = Core.engine ?: return
        val (lang, text) = lastIncoming ?: run {
            setStatus("Önce bir sesli mesaj çevirin.", error = true)
            return
        }
        val token = session.incrementAndGet()
        val online = isOnline()
        Core.worker.execute {
            try {
                val audio = engine.speak(text, lang, online, voiceIndex(lang), speed())
                Player.play(audio.samples, audio.rate) { session.get() != token }
            } catch (t: Throwable) {
                post { showError("Seslendirme hatası", t) }
            }
        }
    }

    private fun toggleRecord() {
        if (googleRec != null) {
            if (!recStopping) {
                recStopping = true
                recBtn.text = "Kaydı başlat"
                setStatus("Kayıt bitiriliyor…")
                googleRec?.finish()
            }
            return
        }
        if (recorder == null && isOnline()) {
            startOnlineRecord()
            return
        }
        val r = recorder
        if (r != null) {
            recorder = null
            recBtn.text = "Kaydı başlat"
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            processOutgoing(r.stop())
            return
        }
        if (Core.engine == null || !ensureMic()) return
        val rec = MicRecorder { t -> post { showError("Kayıt hatası", t) } }
        recorder = rec
        rec.start()
        recBtn.text = "Kaydı bitir ve çevir"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setStatus("Kaydediliyor… konuşun, bitince düğmeye basın.")
    }

    private fun startOnlineRecord() {
        val engine = Core.engine ?: return
        if (!ensureMic()) return
        val mine = Lang.entries[outMine.selectedItemPosition]
        val theirs = Lang.entries[outTheirs.selectedItemPosition]
        if (mine == theirs) {
            setStatus("İki farklı dil seçin.", error = true)
            return
        }
        val token = session.incrementAndGet()
        recParts.clear()
        recMs = 0L
        recStopping = false
        outgoingAudio = null
        lateinit var g: GoogleSpeech
        g = GoogleSpeech(
            this, listOf(mine),
            onPartial = { t -> if (session.get() == token) setStatus("🎤 $t") },
            onFinal = { text, _, ms ->
                if (session.get() == token) {
                    if (text.isNotBlank() && !Engine.isNoise(text)) {
                        recParts.add(text)
                        recMs += ms
                    }
                    if (recStopping) completeOnlineRecord(token, engine, mine, theirs) else g.resume()
                }
            },
            onError = { m ->
                if (session.get() == token) {
                    showError("Ses tanıma", IllegalStateException(m))
                    stopAll()
                }
            },
        )
        googleRec = g
        BeepGuard.start(this)
        recBtn.text = "Kaydı bitir ve çevir"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setStatus("Google konuşma tanıması hazırlanıyor…")
        g.prepare({ msg -> if (session.get() == token) setStatus(msg) }) {
            if (session.get() == token) {
                setStatus("Kaydediliyor… konuşun, bitince düğmeye basın.")
                g.listen()
            }
        }
    }

    private fun completeOnlineRecord(token: Int, engine: Engine, mine: Lang, theirs: Lang) {
        googleRec?.release()
        googleRec = null
        BeepGuard.stop(this)
        recStopping = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val text = recParts.joinToString(" ").trim()
        val asr = recMs
        if (text.isEmpty()) {
            setStatus("Kayıtta konuşma bulunamadı.", error = true)
            return
        }
        setStatus("Kayıt çevriliyor…")
        Core.worker.execute {
            try {
                val t0 = System.currentTimeMillis()
                val out = engine.translate(text, mine, theirs, true)
                val res = Engine.Outcome.Done(mine, text, theirs, out, asr, System.currentTimeMillis() - t0)
                val audio = engine.speak(out, theirs, true, voiceIndex(theirs), speed())
                if (session.get() == token) {
                    outgoingAudio = audio
                    post {
                        addTurn(res, "Giden mesaj")
                        setStatus("Hazır: \"Dinle\" ile kontrol edin, \"WhatsApp'a gönder\" ile paylaşın.")
                    }
                }
            } catch (t: Throwable) {
                post { showError("Kayıt çevrilemedi", t) }
            }
        }
    }

    private fun processOutgoing(samples: FloatArray) {
        val engine = Core.engine ?: return
        val mine = Lang.entries[outMine.selectedItemPosition]
        val theirs = Lang.entries[outTheirs.selectedItemPosition]
        if (mine == theirs) {
            setStatus("İki farklı dil seçin.", error = true)
            return
        }
        outgoingAudio = null
        val online = isOnline()
        setStatus("Kayıt çevriliyor…")
        Core.worker.execute {
            try {
                val segments = engine.splitBySpeech(samples)
                val src = ArrayList<String>()
                val dst = ArrayList<String>()
                var asr = 0L
                var mt = 0L
                for (seg in segments) {
                    val r = engine.understand(seg, mine, listOf(mine), online) { theirs }
                    if (r is Engine.Outcome.Done) {
                        src.add(r.srcText)
                        dst.add(r.tgtText)
                        asr += r.asrMs
                        mt += r.mtMs
                    }
                }
                if (dst.isEmpty()) {
                    post { setStatus("Kayıtta konuşma bulunamadı.", error = true) }
                    return@execute
                }
                val res = Engine.Outcome.Done(mine, src.joinToString(" "), theirs, dst.joinToString(" "), asr, mt)
                outgoingAudio = engine.speak(res.tgtText, theirs, online, voiceIndex(theirs), speed())
                post {
                    addTurn(res, "Giden mesaj")
                    setStatus("Hazır: \"Dinle\" ile kontrol edin, \"WhatsApp'a gönder\" ile paylaşın.")
                }
            } catch (t: Throwable) {
                post { showError("Kayıt çevrilemedi", t) }
            }
        }
    }

    private fun playOutgoing() {
        val audio = outgoingAudio ?: run {
            setStatus("Önce bir kayıt yapın.", error = true)
            return
        }
        val token = session.incrementAndGet()
        Core.worker.execute {
            try {
                Player.play(audio.samples, audio.rate) { session.get() != token }
            } catch (t: Throwable) {
                post { showError("Çalma hatası", t) }
            }
        }
    }

    private fun shareOutgoing() {
        val audio = outgoingAudio ?: run {
            setStatus("Önce bir kayıt yapın.", error = true)
            return
        }
        Core.worker.execute {
            try {
                val dir = File(cacheDir, "shared")
                dir.mkdirs()
                dir.listFiles()?.forEach { it.delete() }
                val file = File(dir, "ceviri_${System.currentTimeMillis()}.m4a")
                AacWriter.write(audio.samples, audio.rate, file)
                val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
                val send = Intent(Intent.ACTION_SEND)
                send.type = "audio/mp4"
                send.putExtra(Intent.EXTRA_STREAM, uri)
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                post { startActivity(Intent.createChooser(send, "Sesli mesajı gönder")) }
            } catch (t: Throwable) {
                post { showError("Paylaşılamadı", t) }
            }
        }
    }

    // ---------------------------------------------------------------- yardımcılar

    private fun ensureMic(): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return true
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        setStatus("Mikrofon izni verin, sonra düğmeye tekrar basın.")
        return false
    }

    private fun isOnline(): Boolean = prefs.getBoolean("online", true)

    /** İnternetli ve internetsiz modun ses listeleri farklı; seçimler ayrı saklanır. */
    private fun voiceKey(lang: Lang) = if (isOnline()) "voice_on_${lang.name}" else "voice_${lang.name}"

    private fun voiceLabels(lang: Lang): List<String> =
        if (isOnline()) EdgeVoices.forLang(lang).map { it.label } else Voices.forLang(lang).map { it.label }

    private fun voiceIndex(lang: Lang): Int = prefs.getInt(voiceKey(lang), 0)

    private fun speed(): Float = 0.6f + prefs.getInt("speed", 40).coerceIn(0, 80) / 100f

    private fun addTurn(r: Engine.Outcome.Done, title: String?) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(8), dp(6), dp(8), dp(6))
        val head = StringBuilder()
        if (title != null) head.append(title).append(" · ")
        head.append(r.src.label).append(" → ").append(r.tgt.label)
        if (r.asrMs > 0) {
            head.append(String.format(Locale.US, "   (tanıma %.1f sn, çeviri %.1f sn)", r.asrMs / 1000.0, r.mtMs / 1000.0))
        }
        val h = text(head.toString(), 11f)
        h.setTextColor(Color.GRAY)
        box.addView(h)
        val s = text(r.srcText, 14f)
        s.setTextColor(Color.DKGRAY)
        box.addView(s)
        if (r.src != r.tgt) {
            val t = text(r.tgtText, 17f, bold = true)
            t.setTextColor(Color.BLACK)
            box.addView(t)
        } else {
            val t = text("(zaten ${r.tgt.label})", 12f)
            t.setTextColor(Color.GRAY)
            box.addView(t)
        }
        appendLog(box)
    }

    private fun addNote(message: String, detail: String) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(8), dp(6), dp(8), dp(6))
        val m = text(message, 13f)
        m.setTextColor(Color.rgb(180, 90, 0))
        box.addView(m)
        if (detail.isNotBlank()) {
            val d = text(detail, 12f)
            d.setTextColor(Color.GRAY)
            box.addView(d)
        }
        appendLog(box)
    }

    private fun appendLog(v: View) {
        logBox.addView(v)
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun setStatus(msg: String, error: Boolean = false) {
        status.text = msg
        status.setTextColor(if (error) Color.rgb(190, 0, 0) else Color.DKGRAY)
    }

    private fun showError(prefix: String, t: Throwable) {
        val msg = "$prefix: ${t.message ?: t.javaClass.simpleName}"
        setStatus(msg, error = true)
        if (::logBox.isInitialized) addNote(msg, "")
    }

    private fun post(f: () -> Unit) {
        main.post { f() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, size: Float, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = size
        if (bold) t.setTypeface(t.typeface, Typeface.BOLD)
        t.setPadding(0, dp(4), 0, dp(2))
        return t
    }

    private fun button(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        return b
    }

    private fun panel(): LinearLayout {
        val p = LinearLayout(this)
        p.orientation = LinearLayout.VERTICAL
        p.setPadding(0, dp(4), 0, dp(8))
        return p
    }

    private fun labeled(title: String, v: View): View {
        val p = LinearLayout(this)
        p.orientation = LinearLayout.VERTICAL
        val t = text(title, 12f)
        t.setTextColor(Color.GRAY)
        p.addView(t)
        p.addView(v)
        return p
    }

    private fun row(vararg views: View): View {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        for (v in views) r.addView(v, LinearLayout.LayoutParams(0, WRAP, 1f))
        return r
    }

    private fun spinner(items: List<String>, key: String, default: Int): Spinner {
        val s = Spinner(this)
        s.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
        s.setSelection(prefs.getInt(key, default).coerceIn(0, items.size - 1))
        s.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.edit().putInt(key, position).apply()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        return s
    }

    companion object {
        private const val REQ_MIC = 1
        private const val REQ_PICK = 2
        private const val MB = 1024L * 1024L
        private const val ECHO_WINDOW_MS = 6000L
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private val MODE_NAMES = listOf("Dinle-Çevir", "Karşılıklı", "Sesli mesaj")
        private val LANG_LABELS = Lang.entries.map { it.label }
    }
}

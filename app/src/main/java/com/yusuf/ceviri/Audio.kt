package com.yusuf.ceviri

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.net.Uri
import java.io.File
import java.nio.ByteOrder

const val RATE = 16000
private const val WINDOW = 512

class FloatList(capacity: Int = RATE) {
    private var data = FloatArray(capacity)
    var size = 0
        private set

    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    fun toArray(): FloatArray = data.copyOf(size)
}

@SuppressLint("MissingPermission")
private fun openMic(): AudioRecord {
    val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val rec = AudioRecord(
        MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, RATE * 2)
    )
    if (rec.state != AudioRecord.STATE_INITIALIZED) {
        rec.release()
        throw IllegalStateException("Mikrofon açılamadı")
    }
    return rec
}

/** Mikrofonu sürekli dinler; konuşma bölümlerini (VAD) tek tek bildirir. */
class MicListener(
    private val models: ModelInstaller,
    private val onSegment: (MicListener, FloatArray) -> Unit,
    private val onSpeech: (Boolean) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    /** Çeviri hoparlörden çalarken true: telefon kendi sesini yeniden çevirmesin. */
    @Volatile
    var muted = false

    @Volatile
    private var running = false

    fun start() {
        running = true
        Thread({ loop() }, "mikrofon").start()
    }

    fun stop() {
        running = false
    }

    private fun loop() {
        var rec: AudioRecord? = null
        var vad: com.k2fsa.sherpa.onnx.Vad? = null
        try {
            val v = newVad(models)
            vad = v
            val r = openMic()
            rec = r
            r.startRecording()
            val pcm = ShortArray(WINDOW)
            var wasMuted = false
            var speaking = false
            while (running) {
                val n = r.read(pcm, 0, WINDOW)
                if (n < 0) throw IllegalStateException("Mikrofon okuma hatası ($n)")
                if (n == 0) continue
                if (muted) {
                    wasMuted = true
                    continue
                }
                if (wasMuted) {
                    v.reset()
                    wasMuted = false
                    if (speaking) {
                        speaking = false
                        onSpeech(false)
                    }
                }
                val f = FloatArray(n)
                for (i in 0 until n) f[i] = pcm[i] / 32768f
                v.acceptWaveform(f)
                val sp = v.isSpeechDetected()
                if (sp != speaking) {
                    speaking = sp
                    onSpeech(sp)
                }
                while (!v.empty()) {
                    val seg = v.front().samples
                    v.pop()
                    if (seg.size >= MIN_SEGMENT) onSegment(this, seg)
                }
            }
        } catch (t: Throwable) {
            if (running) onError(t)
        } finally {
            try {
                rec?.stop()
            } catch (e: Throwable) {
                // kayıt hiç başlamadıysa stop hata verir; kapatmaya devam
            }
            rec?.release()
            vad?.release()
        }
    }

    companion object {
        private const val MIN_SEGMENT = RATE * 3 / 10
    }
}

/** Basılı kayıt: başlat → bitir arasındaki sesi olduğu gibi toplar (en çok 5 dakika). */
class MicRecorder(private val onError: (Throwable) -> Unit) {
    @Volatile
    private var running = false
    private val data = FloatList(RATE * 10)
    private var thread: Thread? = null

    fun start() {
        running = true
        thread = Thread({ loop() }, "kayit").also { it.start() }
    }

    fun stop(): FloatArray {
        running = false
        thread?.join(2000)
        synchronized(data) { return data.toArray() }
    }

    private fun loop() {
        var rec: AudioRecord? = null
        try {
            val r = openMic()
            rec = r
            r.startRecording()
            val pcm = ShortArray(WINDOW)
            while (running && data.size < RATE * 300) {
                val n = r.read(pcm, 0, WINDOW)
                if (n < 0) throw IllegalStateException("Mikrofon okuma hatası ($n)")
                synchronized(data) {
                    for (i in 0 until n) data.add(pcm[i] / 32768f)
                }
            }
        } catch (t: Throwable) {
            onError(t)
        } finally {
            try {
                rec?.stop()
            } catch (e: Throwable) {
                // kayıt hiç başlamadıysa stop hata verir; kapatmaya devam
            }
            rec?.release()
        }
    }
}

object Player {
    /** Sesi hoparlörden çalar ve bitene kadar bekler. */
    fun play(samples: FloatArray, rate: Int, alarm: Boolean = false, stop: () -> Boolean) {
        if (samples.isEmpty()) return
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(if (alarm) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, rate * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            track.play()
            var off = 0
            while (off < samples.size && !stop()) {
                val n = track.write(samples, off, minOf(4096, samples.size - off), AudioTrack.WRITE_BLOCKING)
                if (n < 0) throw IllegalStateException("Ses çalınamadı ($n)")
                off += n
            }
            val deadline = System.currentTimeMillis() + samples.size * 1000L / rate + 1500
            while (track.playbackHeadPosition < off && System.currentTimeMillis() < deadline && !stop()) {
                Thread.sleep(20)
            }
        } finally {
            track.stop()
            track.release()
        }
    }
}

object AudioDecoder {
    /** Ses dosyasını (WhatsApp .opus, m4a, mp3, wav…) 16 kHz tek kanala çevirir. */
    fun decodeToMono16k(ctx: Context, uri: Uri): FloatArray {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, uri, null)
            val s = decode(ex)
            return resample(s.samples, s.rate, RATE)
        } finally {
            ex.release()
        }
    }

    /** Bellekteki ses verisini (örn. Edge'den gelen MP3) kendi örnekleme hızında çözer. */
    fun decodeBytes(bytes: ByteArray): Speech {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(object : MediaDataSource() {
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                    if (position >= bytes.size) return -1
                    val n = minOf(size.toLong(), bytes.size - position).toInt()
                    System.arraycopy(bytes, position.toInt(), buffer, offset, n)
                    return n
                }

                override fun getSize(): Long = bytes.size.toLong()
                override fun close() {}
            })
            return decode(ex)
        } finally {
            ex.release()
        }
    }

    private fun decode(ex: MediaExtractor): Speech {
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i
                    format = f
                    break
                }
            }
            if (track < 0 || format == null) throw IllegalStateException("Dosyada ses bulunamadı")
            ex.selectTrack(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IllegalStateException("Ses türü okunamadı")
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            val mono = FloatList()
            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                while (!outputDone) {
                    if (!inputDone) {
                        val ii = codec.dequeueInputBuffer(10_000)
                        if (ii >= 0) {
                            val buf = codec.getInputBuffer(ii) ?: throw IllegalStateException("Çözücü tamponu yok")
                            val n = ex.readSampleData(buf, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }
                    val oi = codec.dequeueOutputBuffer(info, 10_000)
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val of = codec.outputFormat
                        rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    } else if (oi >= 0) {
                        val ob = codec.getOutputBuffer(oi) ?: throw IllegalStateException("Çözücü çıktısı yok")
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        ob.order(ByteOrder.nativeOrder())
                        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            val fb = ob.asFloatBuffer()
                            val frames = fb.remaining() / channels
                            for (fr in 0 until frames) {
                                var s = 0f
                                for (c in 0 until channels) s += fb.get(fr * channels + c)
                                mono.add(s / channels)
                            }
                        } else if (encoding == AudioFormat.ENCODING_PCM_16BIT) {
                            val sb = ob.asShortBuffer()
                            val frames = sb.remaining() / channels
                            for (fr in 0 until frames) {
                                var s = 0f
                                for (c in 0 until channels) s += sb.get(fr * channels + c) / 32768f
                                mono.add(s / channels)
                            }
                        } else {
                            throw IllegalStateException("Desteklenmeyen ses biçimi ($encoding)")
                        }
                        codec.releaseOutputBuffer(oi, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                    }
                }
            } finally {
                try {
                    codec.stop()
                } catch (e: Throwable) {
                    // başlamamış çözücü; serbest bırakmaya devam
                }
                codec.release()
            }
            return Speech(mono.toArray(), rate)
    }

    /** Doğrusal ara değerleme; küçültmede önce kutu süzgeciyle örtüşme (aliasing) azaltılır. */
    fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || x.isEmpty()) return x
        var src = x
        if (from > to) {
            val w = Math.round(from.toDouble() / to).toInt()
            if (w > 1) {
                val y = FloatArray(x.size)
                var acc = 0f
                for (i in x.indices) {
                    acc += x[i]
                    if (i >= w) acc -= x[i - w]
                    y[i] = acc / minOf(i + 1, w)
                }
                src = y
            }
        }
        val n = (src.size.toLong() * to / from).toInt()
        val out = FloatArray(n)
        val ratio = from.toDouble() / to
        for (i in 0 until n) {
            val p = i * ratio
            val j = p.toInt()
            val f = (p - j).toFloat()
            val a = src[j]
            val b = if (j + 1 < src.size) src[j + 1] else a
            out[i] = a + (b - a) * f
        }
        return out
    }
}

object AacWriter {
    /** Sesi AAC (.m4a) dosyası olarak yazar; WhatsApp'a ses dosyası olarak gönderilir. */
    fun write(samples: FloatArray, rate: Int, out: File) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        val pcm = ShortArray(samples.size) { (samples[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxStarted = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            var track = -1
            var pos = 0
            var inputDone = false
            var outputDone = false
            val info = MediaCodec.BufferInfo()
            while (!outputDone) {
                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii) ?: throw IllegalStateException("Kodlayıcı tamponu yok")
                        buf.clear()
                        val n = minOf(buf.remaining() / 2, pcm.size - pos)
                        val pts = pos * 1_000_000L / rate
                        if (n <= 0) {
                            codec.queueInputBuffer(ii, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buf.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm, pos, n)
                            codec.queueInputBuffer(ii, 0, n * 2, pts, 0)
                            pos += n
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxStarted = true
                } else if (oi >= 0) {
                    val ob = codec.getOutputBuffer(oi) ?: throw IllegalStateException("Kodlayıcı çıktısı yok")
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                    if (info.size > 0) {
                        check(muxStarted) { "Kodlayıcı biçimi gelmeden veri geldi" }
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        muxer.writeSampleData(track, ob, info)
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                }
            }
        } finally {
            try {
                codec.stop()
            } catch (e: Throwable) {
                // başlamamış kodlayıcı; serbest bırakmaya devam
            }
            codec.release()
            if (muxStarted) muxer.stop()
            muxer.release()
        }
    }
}

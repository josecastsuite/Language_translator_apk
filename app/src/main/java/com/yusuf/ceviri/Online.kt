package com.yusuf.ceviri

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Google Çeviri'nin anahtarsız adresi (translate.googleapis.com, client=gtx). */
object GoogleTranslate {
    fun translate(text: String, src: Lang, tgt: Lang): String {
        val clean = text.trim()
        if (src == tgt || clean.isEmpty()) return clean
        val url = URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=${src.whisper}&tl=${tgt.whisper}&dt=t")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = 8000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=utf-8")
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            val body = "q=" + URLEncoder.encode(clean, "UTF-8")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code != 200) throw IOException("Google Çeviri yanıt vermedi (HTTP $code)")
            val json = conn.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
            val parts = JSONArray(json).getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val seg = parts.optJSONArray(i) ?: continue
                if (!seg.isNull(0)) sb.append(seg.getString(0))
            }
            val out = sb.toString().trim()
            if (out.isEmpty()) throw IOException("Google Çeviri boş sonuç döndürdü")
            return out
        } catch (e: java.net.UnknownHostException) {
            throw IOException("İnternet bağlantısı yok (Google Çeviri)")
        } catch (e: java.net.SocketTimeoutException) {
            throw IOException("Google Çeviri zaman aşımı")
        } finally {
            conn.disconnect()
        }
    }
}

/** Tanıyıcı dil bildirmediğinde metnin dilini Google Çeviri'ye sorar; izinli değilse null. */
fun GoogleTranslate.detect(text: String, allowed: List<Lang>): Lang? {
    val url = URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=en&dt=t")
    val conn = url.openConnection() as HttpURLConnection
    try {
        conn.requestMethod = "POST"
        conn.connectTimeout = 5000
        conn.readTimeout = 8000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=utf-8")
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        conn.outputStream.use { it.write(("q=" + URLEncoder.encode(text, "UTF-8")).toByteArray(Charsets.UTF_8)) }
        if (conn.responseCode != 200) throw IOException("Google Çeviri yanıt vermedi (HTTP ${conn.responseCode})")
        val json = conn.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
        val code = JSONArray(json).optString(2, "")
        return Lang.entries.firstOrNull { it.matches(code) }?.takeIf { it in allowed }
    } catch (e: java.net.UnknownHostException) {
        throw IOException("İnternet bağlantısı yok (Google Çeviri)")
    } finally {
        conn.disconnect()
    }
}

data class EdgeVoice(val name: String, val lang: Lang, val label: String)

object EdgeVoices {
    val all = listOf(
        EdgeVoice("tr-TR-EmelNeural", Lang.TR, "Emel (kadın)"),
        EdgeVoice("tr-TR-AhmetNeural", Lang.TR, "Ahmet (erkek)"),
        EdgeVoice("id-ID-GadisNeural", Lang.ID, "Gadis (kadın)"),
        EdgeVoice("id-ID-ArdiNeural", Lang.ID, "Ardi (erkek)"),
        EdgeVoice("en-US-JennyNeural", Lang.EN, "Jenny (kadın)"),
        EdgeVoice("en-US-GuyNeural", Lang.EN, "Guy (erkek)"),
    )

    fun forLang(lang: Lang): List<EdgeVoice> = all.filter { it.lang == lang }
}

/**
 * Microsoft Edge "sesli oku" servisi (edge-tts 7.3.1 ile aynı protokol). MP3 döndürür.
 */
object EdgeTts {
    private const val CHROMIUM_FULL = "143.0.3650.75"
    private const val CHROMIUM_MAJOR = "143"
    private const val WSS =
        "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1?TrustedClientToken=${EdgeToken.TRUSTED_CLIENT_TOKEN}"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$CHROMIUM_MAJOR.0.0.0 Safari/537.36 Edg/$CHROMIUM_MAJOR.0.0.0"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Telefon saati sunucudan farklıysa belirteç reddedilir; sunucunun saatine göre düzeltilir. */
    @Volatile
    private var clockSkewSeconds = 0L

    private class Failure(msg: String, val response: Response?) : IOException(msg)

    fun synth(text: String, voice: EdgeVoice, speed: Float): ByteArray {
        return try {
            synthOnce(text, voice, speed)
        } catch (e: Failure) {
            val date = e.response?.header("Date")
            if (e.response?.code == 403 && date != null) {
                val server = parseHttpDate(date)
                clockSkewSeconds = server - System.currentTimeMillis() / 1000
                synthOnce(text, voice, speed)
            } else {
                synthOnce(text, voice, speed)
            }
        } catch (e: IOException) {
            // Servis ara sıra boş yanıt döndürüyor; aynı istek bir kez daha denenir.
            synthOnce(text, voice, speed)
        }
    }

    private fun synthOnce(text: String, voice: EdgeVoice, speed: Float): ByteArray {
        val now = System.currentTimeMillis() / 1000 + clockSkewSeconds
        val url = WSS + "&ConnectionId=" + id() +
            "&Sec-MS-GEC=" + EdgeToken.generate(now) +
            "&Sec-MS-GEC-Version=2-$CHROMIUM_FULL"
        val request = Request.Builder()
            .url(url)
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header("User-Agent", UA)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", "muid=" + id().uppercase(Locale.US) + ";")
            .build()

        val audio = ByteArrayOutputStream()
        val done = CountDownLatch(1)
        var error: IOException? = null
        val rate = Math.round((speed - 1f) * 100f)
        val rateText = (if (rate >= 0) "+" else "") + rate + "%"
        val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
            "<voice name='${voice.name}'><prosody pitch='+0Hz' rate='$rateText' volume='+0%'>" +
            escape(clean(text)) + "</prosody></voice></speak>"

        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(
                    "X-Timestamp:${date()}\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n" +
                        "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{" +
                        "\"sentenceBoundaryEnabled\":\"true\",\"wordBoundaryEnabled\":\"false\"}," +
                        "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n"
                )
                webSocket.send(
                    "X-RequestId:${id()}\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:${date()}Z\r\nPath:ssml\r\n\r\n$ssml"
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("Path:turn.end")) {
                    webSocket.close(1000, null)
                    done.countDown()
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                if (data.size < 2) return
                val headerLen = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                if (headerLen + 2 > data.size) return
                val header = String(data, 2, headerLen, Charsets.UTF_8)
                if (header.contains("Path:audio") && header.contains("Content-Type:audio/mpeg")) {
                    synchronized(audio) { audio.write(data, 2 + headerLen, data.size - 2 - headerLen) }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                error = when {
                    response != null -> Failure("Edge ses servisi reddetti (HTTP ${response.code})", response)
                    t is java.net.UnknownHostException -> IOException("İnternet bağlantısı yok (Edge ses)")
                    else -> IOException("Edge ses servisi hatası: ${t.message ?: t.javaClass.simpleName}")
                }
                done.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                done.countDown()
            }
        })

        if (!done.await(20, TimeUnit.SECONDS)) {
            ws.cancel()
            throw IOException("Edge ses servisi zaman aşımı")
        }
        error?.let { throw it }
        val bytes = synchronized(audio) { audio.toByteArray() }
        if (bytes.isEmpty()) throw IOException("Edge ses servisi ses göndermedi")
        return bytes
    }

    private fun id(): String = UUID.randomUUID().toString().replace("-", "")

    private fun date(): String {
        val f = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date())
    }

    private fun parseHttpDate(s: String): Long {
        val f = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        f.timeZone = TimeZone.getTimeZone("GMT")
        return (f.parse(s) ?: throw IOException("Sunucu saati okunamadı")).time / 1000
    }

    /** Servisin kabul etmediği kontrol karakterlerini boşlukla değiştirir. */
    private fun clean(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            val c = ch.code
            if (c in 0..8 || c in 11..12 || c in 14..31) sb.append(' ') else sb.append(ch)
        }
        return sb.toString()
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

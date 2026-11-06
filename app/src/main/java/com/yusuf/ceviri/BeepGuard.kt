package com.yusuf.ceviri

import android.content.Context
import android.media.AudioManager

/**
 * Google konuşma tanıması her dinleme başlangıcında medya sesinden bip çalar ve bunu
 * kapatmanın resmî bir yolu yoktur. İnternetli canlı dinleme sürerken medya (ve bildirim/sistem)
 * sesi kısılır; çeviri alarm kanalından, medya sesiyle aynı seviyede okunur.
 * Dinleme bitince her şey eski hâline döner; uygulama çökerse bir sonraki açılışta geri alınır.
 */
object BeepGuard {
    private const val PREFS = "bip"
    private val MUTED_STREAMS = intArrayOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM)

    @Synchronized
    fun start(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean("active", false)) return
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        val musicMax = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val musicNow = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val alarmMax = am.getStreamMaxVolume(AudioManager.STREAM_ALARM).coerceAtLeast(1)
        val alarmNow = am.getStreamVolume(AudioManager.STREAM_ALARM)
        prefs.edit().putBoolean("active", true).putInt("alarm", alarmNow).apply()
        val target = Math.round(musicNow.toFloat() / musicMax * alarmMax).coerceIn(1, alarmMax)
        try {
            am.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
        } catch (e: SecurityException) {
            // Rahatsız Etmeyin açıkken izin verilmeyebilir; alarm sesi olduğu gibi kalır.
        }
        for (s in MUTED_STREAMS) {
            try {
                am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, 0)
            } catch (e: SecurityException) {
                // Rahatsız Etmeyin açıkken bildirim sesi değiştirilemeyebilir.
            }
        }
    }

    @Synchronized
    fun stop(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("active", false)) return
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        for (s in MUTED_STREAMS) {
            try {
                am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0)
            } catch (e: SecurityException) {
                // Rahatsız Etmeyin açıkken bildirim sesi değiştirilemeyebilir.
            }
        }
        try {
            am.setStreamVolume(AudioManager.STREAM_ALARM, prefs.getInt("alarm", am.getStreamVolume(AudioManager.STREAM_ALARM)), 0)
        } catch (e: SecurityException) {
            // alarm sesi olduğu gibi kalır
        }
        prefs.edit().putBoolean("active", false).apply()
    }
}

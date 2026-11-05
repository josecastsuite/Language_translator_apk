package com.yusuf.ceviri

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Modeller APK'nın assets/models klasöründe gelir. Yerel kütüphaneler dosya yolu
 * istediği için ilk açılışta bir kez uygulamanın kendi klasörüne kopyalanır.
 * models/manifest.txt: her satır "göreli_yol<TAB>bayt".
 */
class ModelInstaller(private val ctx: Context) {
    val root: File = File(ctx.filesDir, "models")
    private val marker = File(ctx.filesDir, "models.installed")

    private fun manifestBytes(): ByteArray = ctx.assets.open("models/manifest.txt").use { it.readBytes() }

    private fun manifest(): List<Pair<String, Long>> =
        String(manifestBytes(), Charsets.UTF_8).split('\n').filter { it.isNotBlank() }.map { line ->
            val tab = line.lastIndexOf('\t')
            require(tab > 0) { "Bozuk manifest satırı: $line" }
            line.substring(0, tab) to line.substring(tab + 1).trim().toLong()
        }

    private fun digest(): String =
        MessageDigest.getInstance("SHA-256").digest(manifestBytes()).joinToString("") { "%02x".format(it) }

    fun isInstalled(): Boolean {
        if (!marker.isFile || marker.readText() != digest()) return false
        return manifest().all { (path, size) -> File(root, path).let { it.isFile && it.length() == size } }
    }

    fun install(progress: (done: Long, total: Long) -> Unit) {
        val items = manifest()
        val total = items.sumOf { it.second }
        val existing = if (root.exists()) root.walkBottomUp().filter { it.isFile }.sumOf { it.length() } else 0L
        val available = ctx.filesDir.usableSpace + existing
        val margin = 200L * 1024 * 1024
        check(available >= total + margin) {
            "Yetersiz depolama: ${(total + margin) / MB} MB gerekli, ${available / MB} MB boş."
        }
        marker.delete()
        root.deleteRecursively()
        val buf = ByteArray(1 shl 20)
        var done = 0L
        for ((path, size) in items) {
            val out = File(root, path)
            out.parentFile?.mkdirs()
            ctx.assets.open("models/$path").use { input ->
                out.outputStream().use { output ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        progress(done, total)
                    }
                }
            }
            check(out.length() == size) { "Kopyalama eksik: $path (${out.length()} / $size bayt)" }
        }
        marker.writeText(digest())
    }

    fun path(relative: String): String = File(root, relative).absolutePath

    companion object {
        private const val MB = 1024L * 1024L
    }
}

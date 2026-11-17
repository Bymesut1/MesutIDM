package com.bymesut1.idm

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class Item(
    val id: Int,
    val url: String,
    var name: String,
    val ua: String,
    val cookie: String,
    val referer: String
) {
    @Volatile var total = -1L
    val done = AtomicLong(0)
    @Volatile var state = "Bekliyor"
    @Volatile var error = ""
    @Volatile var uri: Uri? = null
    @Volatile var cancelled = false
    @Volatile var hls = false
    @Volatile var lastBytes = 0L
    @Volatile var lastTime = 0L
    @Volatile var speed = 0L
}

object Dm {
    lateinit var app: Context
    val items = CopyOnWriteArrayList<Item>()
    private val pool = Executors.newCachedThreadPool()
    private var nextId = 1
    private const val PARTS = 8

    fun active() = items.any { it.state == "Bekliyor" || it.state == "İndiriliyor" }

    fun start(ctx: Context, url: String, name: String, ua: String, cookie: String, ref: String): Item {
        val d = Item(nextId++, url, name, ua, cookie, ref)
        d.hls = url.substringBefore('?').lowercase().endsWith(".m3u8")
        if (d.hls) d.name = d.name.substringBeforeLast('.') + ".ts"
        items.add(d)
        ctx.startForegroundService(Intent(ctx, DownloadService::class.java))
        pool.execute { run(d) }
        return d
    }

    private fun run(d: Item) {
        val tmp = File(app.getExternalFilesDir(null), "tmp_${d.id}")
        try {
            d.state = "İndiriliyor"
            if (d.hls) hls(d, tmp) else http(d, tmp)
            if (d.cancelled) {
                tmp.delete(); d.state = "İptal"; return
            }
            d.uri = publish(d, tmp)
            tmp.delete()
            d.state = "Bitti"
        } catch (e: Exception) {
            tmp.delete()
            if (d.cancelled) d.state = "İptal"
            else {
                d.state = "Hata"; d.error = e.message ?: e.toString()
            }
        }
    }

    private fun open(d: Item, url: String, range: String? = null): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", d.ua)
        if (d.cookie.isNotEmpty()) c.setRequestProperty("Cookie", d.cookie)
        if (d.referer.isNotEmpty()) c.setRequestProperty("Referer", d.referer)
        if (range != null) c.setRequestProperty("Range", range)
        return c
    }

    private fun clean(n: String) = n.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "dosya" }

    private fun nameFrom(d: Item, c: HttpURLConnection) {
        val cd = c.getHeaderField("Content-Disposition") ?: return
        val m = Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(cd) ?: return
        try {
            d.name = clean(URLDecoder.decode(m.groupValues[1], "UTF-8"))
        } catch (_: Exception) {
        }
    }

    // ---------- Normal dosya: çok parçalı (IDM tarzı) ----------
    private fun http(d: Item, tmp: File) {
        val probe = open(d, d.url, "bytes=0-0")
        val code = probe.responseCode
        var ranges = false
        if (code == 206) {
            d.total = probe.getHeaderField("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull() ?: -1
            ranges = d.total > 0
        } else if (code == 200) {
            d.total = probe.contentLengthLong
        } else throw Exception("HTTP $code")
        nameFrom(d, probe)
        probe.disconnect()

        if (ranges && d.total > 1_000_000) multi(d, tmp) else single(d, tmp)
    }

    private fun single(d: Item, tmp: File) {
        val c = open(d, d.url)
        if (c.responseCode != 200) throw Exception("HTTP ${c.responseCode}")
        if (d.total <= 0) d.total = c.contentLengthLong
        c.inputStream.use { ins ->
            FileOutputStream(tmp).use { out ->
                val buf = ByteArray(65536)
                while (!d.cancelled) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    d.done.addAndGet(n.toLong())
                }
            }
        }
    }

    private fun multi(d: Item, tmp: File) {
        val size = d.total
        RandomAccessFile(tmp, "rw").use { it.setLength(size) }
        val chunk = size / PARTS
        val failure = AtomicReference<Exception?>(null)
        val threads = (0 until PARTS).map { i ->
            val start = i * chunk
            val end = if (i == PARTS - 1) size - 1 else start + chunk - 1
            Thread {
                try {
                    range(d, tmp, AtomicLong(start), end)
                } catch (e: Exception) {
                    failure.set(e)
                    d.cancelled = true
                }
            }.also { it.start() }
        }
        threads.forEach { it.join() }
        failure.get()?.let { d.cancelled = false; throw it }
    }

    private fun range(d: Item, tmp: File, pos: AtomicLong, end: Long) {
        var tries = 0
        while (pos.get() <= end && !d.cancelled) {
            try {
                val c = open(d, d.url, "bytes=${pos.get()}-$end")
                if (c.responseCode != 206) throw Exception("HTTP ${c.responseCode}")
                val ins = c.inputStream
                val raf = RandomAccessFile(tmp, "rw")
                try {
                    raf.seek(pos.get())
                    val buf = ByteArray(65536)
                    while (!d.cancelled) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        raf.write(buf, 0, n)
                        pos.addAndGet(n.toLong())
                        d.done.addAndGet(n.toLong())
                    }
                } finally {
                    raf.close(); ins.close()
                }
                if (pos.get() <= end && !d.cancelled) throw Exception("kısa veri")
            } catch (e: Exception) {
                if (d.cancelled) return
                if (++tries > 6) throw e
                Thread.sleep(1500)
            }
        }
    }

    // ---------- HLS (.m3u8) -> .ts ----------
    private fun text(d: Item, url: String): String {
        val c = open(d, url)
        if (c.responseCode != 200) throw Exception("HTTP ${c.responseCode}")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun resolve(base: String, rel: String) = URL(URL(base), rel).toString()

    private fun hls(d: Item, tmp: File) {
        var base = d.url
        var pl = text(d, base)
        if (pl.contains("#EXT-X-STREAM-INF")) {
            val lines = pl.lines()
            var best = -1L
            var bestUrl = ""
            for (i in lines.indices) {
                if (lines[i].startsWith("#EXT-X-STREAM-INF")) {
                    val bw = Regex("BANDWIDTH=(\\d+)").find(lines[i])?.groupValues?.get(1)?.toLong() ?: 0L
                    val u = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                    if (u != null && bw > best) {
                        best = bw; bestUrl = resolve(base, u.trim())
                    }
                }
            }
            base = bestUrl
            pl = text(d, base)
        }
        if (pl.contains("#EXT-X-KEY") && !pl.contains("METHOD=NONE")) throw Exception("Şifreli HLS desteklenmiyor")
        val segs = pl.lines().filter { it.isNotBlank() && !it.startsWith("#") }.map { resolve(base, it.trim()) }
        if (segs.isEmpty()) throw Exception("Parça bulunamadı")
        d.total = segs.size.toLong()
        FileOutputStream(tmp).use { out ->
            for (s in segs) {
                if (d.cancelled) break
                var ok = false
                var tries = 0
                while (!ok && !d.cancelled) {
                    try {
                        val c = open(d, s)
                        if (c.responseCode != 200) throw Exception("HTTP ${c.responseCode}")
                        val bytes = c.inputStream.use { it.readBytes() }
                        out.write(bytes)
                        ok = true
                    } catch (e: Exception) {
                        if (++tries > 4) throw e
                        Thread.sleep(1000)
                    }
                }
                d.done.incrementAndGet()
            }
        }
    }

    // ---------- Genel Downloads/IDM klasörüne kaydet ----------
    private fun publish(d: Item, tmp: File): Uri {
        val name = clean(d.name)
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = if (ext == "ts") "video/mp2t"
        else MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        val cv = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/IDM")
        }
        val uri = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
            ?: throw Exception("Dosya oluşturulamadı")
        app.contentResolver.openOutputStream(uri)!!.use { o ->
            tmp.inputStream().use { it.copyTo(o) }
        }
        return uri
    }
}

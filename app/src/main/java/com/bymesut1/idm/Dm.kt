package com.bymesut1.idm

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URLDecoder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Bulunan / indirilecek öğe. PAIR = ayrı video + ayrı ses (indirip birleştirilir). */
class Found(
    val url: String,
    var name: String,
    val referer: String,
    val kind: Int = FILE,
    val audioUrl: String? = null,
    val label: String = "",
    var size: Long = -1,
    val ua: String? = null,
    val pri: Int = 0
) {
    var mime: String = ""
    @Volatile var data: String? = null
    @Volatile var dataBytes: ByteArray? = null

    companion object {
        const val FILE = 0
        const val HLS = 1
        const val DASH = 2
        const val PAIR = 3
        const val SUBHLS = 4
    }
}

class Item(val id: Int, val f: Found, val ua: String, val cookie: String) {
    @Volatile var name: String = f.name
    @Volatile var total = -1L
    val done = AtomicLong(0)
    @Volatile var state = "Bekliyor"
    @Volatile var error = ""
    @Volatile var note = ""
    @Volatile var uri: Uri? = null
    @Volatile var cancelled = false
    @Volatile var segments = false
    @Volatile var lastBytes = 0L
    @Volatile var lastTime = 0L
    @Volatile var speed = 0L
}

class LimitEx(msg: String) : Exception(msg)

private class Probe(
    val total: Long,
    val ranges: Boolean,
    val finalUrl: String,
    val ct: String,
    val fname: String?
)

object Dm {
    lateinit var app: Context
    val items = CopyOnWriteArrayList<Item>()
    private val pool = Executors.newCachedThreadPool()
    private var nextId = 1
    private const val PARTS = 8
    private const val SEG_THREADS = 8
    private const val SEG_WINDOW = 16
    private val SUB_EXT = setOf("srt", "vtt", "ass", "ssa", "ttml", "dfxp", "sbv")
    private val BIN_EXT = setOf(
        "mp4", "mkv", "webm", "mp3", "m4a", "flv", "avi", "mov", "zip", "rar", "7z",
        "apk", "pdf", "iso", "ogg", "wav", "aac", "opus"
    )

    fun setup(ctx: Context) {
        app = ctx.applicationContext
        if (items.isEmpty()) {
            try {
                workDir().listFiles()?.forEach { if (it.name.startsWith("tmp_")) it.delete() }
            } catch (e: Exception) {
            }
        }
    }

    private fun workDir(): File = app.getExternalFilesDir(null) ?: app.cacheDir

    fun active() = items.any {
        it.state == "Bekliyor" || it.state == "İndiriliyor" || it.state == "Birleştiriliyor" || it.state == "Kaydediliyor"
    }

    fun start(ctx: Context, f: Found, ua: String, cookie: String): Item {
        val d = Item(nextId++, f, f.ua ?: ua, cookie)
        items.add(d)
        ctx.startForegroundService(Intent(ctx, DownloadService::class.java))
        pool.execute { run(d) }
        return d
    }

    fun retry(ctx: Context, d: Item): Item {
        items.remove(d)
        return start(ctx, d.f, d.ua, Net.cookieFor(d.f.url, d.cookie))
    }

    private class Job(val dir: File, val id: Int) {
        val files = ArrayList<File>()
        fun tmp(tag: String): File {
            val f = File(dir, "tmp_${id}_$tag")
            files.add(f)
            return f
        }

        fun cleanup() {
            for (f in files) {
                try {
                    f.delete()
                } catch (e: Exception) {
                }
            }
        }
    }

    private fun alive(d: Item) {
        if (d.cancelled) throw Exception("İptal")
    }

    private fun friendly(e: Exception): String {
        val m = e.message ?: e.toString()
        return when {
            e is java.net.UnknownHostException -> "Sunucuya ulaşılamadı (internet/DNS)"
            e is java.net.SocketTimeoutException -> "Zaman aşımı"
            e is javax.net.ssl.SSLException -> "Güvenli bağlantı hatası: $m"
            m.startsWith("HTTP 403") || m.startsWith("HTTP 401") -> "$m (erişim reddedildi: link süresi dolmuş / oturum gerekli olabilir; sayfayı yenileyip videoyu tekrar oynat)"
            m.startsWith("HTTP 404") || m.startsWith("HTTP 410") -> "$m (link artık yok / süresi dolmuş)"
            else -> m
        }
    }

    private fun run(d: Item) {
        val j = Job(workDir(), d.id)
        try {
            d.state = "İndiriliyor"
            when (d.f.kind) {
                Found.HLS -> doHls(d, j, d.f.url)
                Found.DASH -> doDash(d, j)
                Found.PAIR -> doPair(d, j)
                Found.SUBHLS -> doSubHls(d, j)
                else -> doFile(d, j)
            }
            alive(d)
            d.state = "Bitti"
        } catch (e: Exception) {
            if (d.cancelled) {
                d.state = "İptal"
            } else {
                d.error = friendly(e)
                d.state = "Hata"
            }
        } finally {
            j.cleanup()
        }
    }

    private fun <T> retry(n: Int, f: () -> T): T {
        var last: Exception? = null
        for (i in 0 until n) {
            try {
                return f()
            } catch (e: Exception) {
                last = e
                Thread.sleep(600L * (i + 1))
            }
        }
        throw last ?: Exception("Hata")
    }

    private fun text(d: Item, u: String): String = retry(3) { Net.text(u, d.ua, d.f.referer, d.cookie) }
    private fun bytesOf(d: Item, u: String): ByteArray = retry(3) { Net.bytes(u, d.ua, d.f.referer, d.cookie) }

    private fun checkSpace(d: Item) {
        if (d.total > 0 && !d.segments) {
            val need = (d.total * 2.1).toLong()
            val free = workDir().usableSpace
            if (free < need) throw Exception("Yetersiz depolama: ${U.fmt(need)} boş alan gerekli, ${U.fmt(free)} var")
        }
    }

    // ---------------------------------------------------------------- Normal dosya
    private fun doFile(d: Item, j: Job) {
        val inl = d.f.data
        val inb = d.f.dataBytes
        if (inl != null || inb != null) {
            // Sayfadan / önceden yakalanan altyazı: yeniden indirmeden doğrudan kaydet (bayt varsa özgün kodlama korunur)
            val tf = j.tmp("f")
            if (inb != null) tf.writeBytes(inb) else tf.writeText(inl ?: "", Charsets.UTF_8)
            d.segments = false
            d.total = tf.length()
            d.done.set(tf.length())
            d.state = "Kaydediliyor"
            d.uri = publish(tf, d.name)
            return
        }
        val p = probe(d, d.f.url)
        if (U.extOf(d.name) !in SUB_EXT && looksPlaylist(d, p)) {
            doHls(d, j, d.f.url)
            return
        }
        if (p.ct.contains("dash+xml")) {
            doDash(d, j)
            return
        }
        if (p.ct.startsWith("text/html") && U.extOf(d.name) in BIN_EXT) {
            throw Exception("Sunucu dosya yerine web sayfası döndürdü (link süresi dolmuş veya giriş gerekiyor olabilir)")
        }
        d.segments = false
        d.total = p.total
        d.name = fixName(d.name, p)
        checkSpace(d)
        val t = j.tmp("f")
        download(d, p, t)
        alive(d)
        if (t.length() == 0L) throw Exception("Sunucu boş dosya döndürdü (link süresi dolmuş / izin gerekli olabilir)")
        d.state = "Kaydediliyor"
        d.uri = publish(t, d.name)
    }

    // HLS altyazı kanalı: vtt parçalarını indirip tek .vtt dosyasında birleştirir.
    private fun doSubHls(d: Item, j: Job) {
        d.segments = true
        val pl = text(d, d.f.url)
        val m = Hls.media(d.f.url, pl) { u -> bytesOf(d, u) }
        d.total = m.segs.size.toLong()
        d.done.set(0)
        val sb = StringBuilder()
        var first = true
        for (s in m.segs) {
            alive(d)
            var t = String(segment(d, s, false), Charsets.UTF_8).replace("\r\n", "\n")
            if (!first && t.trimStart('\uFEFF').startsWith("WEBVTT")) {
                val idx = t.indexOf("\n\n")
                t = if (idx >= 0) t.substring(idx + 2) else ""
            }
            if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append("\n")
            sb.append(t)
            first = false
            d.done.incrementAndGet()
        }
        val f = j.tmp("s")
        f.writeText(sb.toString(), Charsets.UTF_8)
        alive(d)
        d.state = "Kaydediliyor"
        d.name = U.safe(d.name)
        d.uri = publish(f, d.name)
    }

    private fun looksPlaylist(d: Item, p: Probe): Boolean {
        if (p.ct.contains("mpegurl")) return true
        if (p.ct.startsWith("video/") || p.ct.startsWith("audio/") || p.ct.startsWith("application/zip")) return false
        val small = (p.total in 1..2_000_000L) || (p.total <= 0 && p.ct.contains("text"))
        if (small && (p.ct.contains("text") || p.ct.contains("octet-stream") || p.ct.isEmpty())) {
            return try {
                text(d, d.f.url).trimStart().startsWith("#EXTM3U")
            } catch (e: Exception) {
                false
            }
        }
        return false
    }

    private fun fixName(name: String, p: Probe): String {
        var n = name
        val fn = p.fname
        if (!fn.isNullOrEmpty()) n = U.safe(fn)
        val ext = U.extOf(n)
        val want = U.extForMime(p.ct)
        if (want != null && (ext.isEmpty() || ext == "bin" || ext == "html" ||
                (ext != want && U.mimeForExt(ext) == "application/octet-stream"))
        ) {
            n = U.baseName(n) + "." + want
        }
        return n
    }

    private fun disp(c: HttpURLConnection): String? {
        val cd = c.getHeaderField("Content-Disposition") ?: return null
        val m = Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(cd) ?: return null
        return try {
            U.safe(URLDecoder.decode(m.groupValues[1].trim(), "UTF-8"))
        } catch (e: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------- Ses + video (ayrı) -> birleştir
    private fun doPair(d: Item, j: Job) {
        val au = d.f.audioUrl ?: throw Exception("Ses adresi yok")
        val pv = probe(d, d.f.url)
        val pa = probe(d, au)
        if (pv.ct.startsWith("text/html") || pa.ct.startsWith("text/html")) {
            throw Exception("Sunucu video yerine web sayfası döndürdü (link süresi dolmuş olabilir)")
        }
        d.segments = false
        d.total = if (pv.total > 0 && pa.total > 0) pv.total + pa.total else -1L
        d.done.set(0)
        checkSpace(d)
        val vf = j.tmp("v")
        download(d, pv, vf)
        alive(d)
        val af = j.tmp("a")
        download(d, pa, af)
        alive(d)
        val web = d.f.name.endsWith(".webm", true)
        finishStreams(d, j, vf, af, if (web) "webm" else "mp4", if (web) "webm" else "m4a", false)
    }

    // ---------------------------------------------------------------- HLS
    private fun doHls(d: Item, j: Job, url: String) {
        d.segments = true
        val plan = Hls.plan(url, { u -> text(d, u) }, { u -> bytesOf(d, u) })
        alive(d)
        val audio = plan.audio
        d.total = (plan.video.segs.size + (audio?.segs?.size ?: 0)).toLong()
        d.done.set(0)
        val vf = j.tmp("v")
        fetchSegs(d, plan.video, vf)
        var af: File? = null
        if (audio != null) {
            af = j.tmp("a")
            fetchSegs(d, audio, af)
        }
        alive(d)
        if (plan.video.fmp4) finishStreams(d, j, vf, af, "mp4", "m4a", false)
        else finishStreams(d, j, vf, af, "ts", "aac", true)
    }

    // ---------------------------------------------------------------- DASH
    private fun doDash(d: Item, j: Job) {
        val xml = text(d, d.f.url)
        val plan = Dash.plan(d.f.url, xml)
        d.segments = true
        var units = plan.video.media?.segs?.size ?: 1
        val at = plan.audio
        if (at != null) units += at.media?.segs?.size ?: 1
        d.total = units.toLong()
        d.done.set(0)
        val vf = j.tmp("v")
        dashTrack(d, plan.video, vf)
        var af: File? = null
        if (at != null) {
            af = j.tmp("a")
            dashTrack(d, at, af)
        }
        alive(d)
        finishStreams(d, j, vf, af, "mp4", "m4a", false)
    }

    private fun dashTrack(d: Item, t: DashTrack, f: File) {
        val m = t.media
        if (m != null) {
            fetchSegs(d, m, f)
        } else {
            val p = probe(d, t.single ?: throw Exception("DASH adresi yok"))
            download(d, p, f)
            d.done.incrementAndGet()
        }
    }

    // ---------------------------------------------------------------- Birleştir + kaydet
    private fun muxWith(d: Item, ins: List<File>, out: File): MuxOut {
        d.segments = false
        var tot = 0L
        for (f in ins) tot += f.length()
        d.total = tot
        d.done.set(0)
        return Mux.merge(ins, out, { n -> d.done.addAndGet(n) }, { d.cancelled })
    }

    private fun finishStreams(d: Item, j: Job, vf: File, af: File?, vExt: String, aExt: String, remuxSingle: Boolean) {
        val base = U.baseName(d.name)
        if (af != null) {
            d.state = "Birleştiriliyor"
            val o = j.tmp("o")
            val r = muxWith(d, listOf(vf, af), o)
            alive(d)
            d.state = "Kaydediliyor"
            if (r.ok) {
                d.name = "$base.${r.ext}"
                d.uri = publish(o, d.name)
            } else {
                d.uri = publish(vf, "$base (video).$vExt")
                publish(af, "$base (ses).$aExt")
                d.note = "Birleştirilemedi (${r.msg}); video ve ses ayrı iki dosya olarak kaydedildi"
            }
        } else if (remuxSingle) {
            d.state = "Birleştiriliyor"
            val o = j.tmp("o")
            val r = muxWith(d, listOf(vf), o)
            alive(d)
            d.state = "Kaydediliyor"
            if (r.ok) {
                d.name = "$base.${r.ext}"
                d.uri = publish(o, d.name)
            } else {
                d.name = "$base.$vExt"
                d.uri = publish(vf, d.name)
            }
        } else {
            d.state = "Kaydediliyor"
            d.name = "$base.$vExt"
            d.uri = publish(vf, d.name)
        }
    }

    private fun publish(f: File, name: String): Uri {
        val n = U.safe(name)
        val ext = n.substringAfterLast('.', "").lowercase()
        val res = app.contentResolver
        val cv = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, n)
            put(MediaStore.Downloads.MIME_TYPE, U.mimeForExt(ext))
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/IDM")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = res.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: throw Exception("Dosya oluşturulamadı")
        try {
            val o = res.openOutputStream(uri) ?: throw Exception("Dosya açılamadı")
            o.use { out -> f.inputStream().use { it.copyTo(out, 262144) } }
            val done = ContentValues()
            done.put(MediaStore.Downloads.IS_PENDING, 0)
            res.update(uri, done, null, null)
        } catch (e: Exception) {
            try {
                res.delete(uri, null, null)
            } catch (x: Exception) {
            }
            throw e
        }
        return uri
    }

    // ---------------------------------------------------------------- HTTP: probe
    private fun probe(d: Item, url: String): Probe {
        var last: Exception? = null
        for (attempt in 0 until 2) {
            try {
                val rng = if (attempt == 0) "bytes=0-0" else null
                val k = Net.open(url, d.ua, d.f.referer, d.cookie, rng)
                try {
                    val code = k.code
                    val ct = (k.c.contentType ?: "").lowercase()
                    if (code == 206) {
                        val tot = k.c.getHeaderField("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull() ?: -1L
                        return Probe(tot, tot > 0, k.url, ct, disp(k.c))
                    }
                    if (code == 200) return Probe(k.c.contentLengthLong, false, k.url, ct, disp(k.c))
                    last = Exception("HTTP $code")
                } finally {
                    k.c.disconnect()
                }
            } catch (e: Exception) {
                last = e
                if (d.cancelled) throw e
            }
        }
        throw last ?: Exception("Bağlantı kurulamadı")
    }

    private fun download(d: Item, p: Probe, tmp: File) {
        val start = d.done.get()
        if (p.ranges && p.total > 2_000_000L) {
            try {
                multi(d, p, tmp)
                return
            } catch (e: Exception) {
                if (d.cancelled) throw e
                d.done.set(start)
            }
        }
        single(d, p, tmp)
    }

    // Çok parçalı (IDM tarzı): dosya küçük parçalara bölünür, 8 bağlantı sırayla parça çeker.
    private fun multi(d: Item, p: Probe, tmp: File) {
        val size = p.total
        RandomAccessFile(tmp, "rw").use { it.setLength(size) }
        val chunk = (size / 40).coerceIn(1L shl 20, 8L shl 20)
        val n = ((size + chunk - 1) / chunk).toInt()
        val queue = ConcurrentLinkedQueue<Int>()
        for (i in 0 until n) queue.add(i)
        val failure = AtomicReference<Exception?>(null)
        val workers = if (size < 8_000_000L) 4 else minOf(PARTS, n)
        val alive = AtomicInteger(workers)
        val limits = AtomicInteger(0)
        val threads = (0 until workers).map {
            Thread {
                var raf: RandomAccessFile? = null
                try {
                    val r = RandomAccessFile(tmp, "rw")
                    raf = r
                    while (!d.cancelled && failure.get() == null) {
                        val i = queue.poll() ?: break
                        val s = i.toLong() * chunk
                        val e = minOf(size - 1, s + chunk - 1)
                        try {
                            fetchChunk(d, p, r, s, e)
                        } catch (ex: LimitEx) {
                            queue.add(i)
                            if (limits.incrementAndGet() > 40) {
                                failure.compareAndSet(null, ex)
                            } else if (alive.get() > 1) {
                                alive.decrementAndGet()
                                break
                            } else {
                                Thread.sleep(2000)
                            }
                        } catch (ex: Exception) {
                            failure.compareAndSet(null, ex)
                        }
                    }
                } catch (ex: Exception) {
                    failure.compareAndSet(null, ex)
                } finally {
                    try {
                        raf?.close()
                    } catch (x: Exception) {
                    }
                }
            }.also { it.start() }
        }
        threads.forEach { it.join() }
        val f = failure.get()
        if (f != null) throw f
        if (d.cancelled) throw Exception("İptal")
    }

    private fun fetchChunk(d: Item, p: Probe, raf: RandomAccessFile, start: Long, end: Long) {
        var pos = start
        var tries = 0
        while (pos <= end && !d.cancelled) {
            try {
                val u = if (tries % 2 == 0) p.finalUrl else d.f.url
                val k = Net.open(u, d.ua, d.f.referer, d.cookie, "bytes=$pos-$end")
                try {
                    val code = k.code
                    if (code == 429 || code == 503) throw LimitEx("HTTP $code")
                    if (code != 206) throw Exception("HTTP $code")
                    val ins = k.c.inputStream
                    raf.seek(pos)
                    val buf = ByteArray(131072)
                    while (!d.cancelled) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        val remaining = end - pos + 1
                        val w = if (n > remaining) remaining.toInt() else n
                        raf.write(buf, 0, w)
                        pos += w
                        d.done.addAndGet(w.toLong())
                        if (pos > end) break
                    }
                    try {
                        ins.close()
                    } catch (x: Exception) {
                    }
                } finally {
                    k.c.disconnect()
                }
                if (pos <= end && !d.cancelled) throw Exception("Bağlantı kesildi")
            } catch (e: LimitEx) {
                throw e
            } catch (e: Exception) {
                if (d.cancelled) return
                tries++
                if (tries > 8) throw e
                Thread.sleep(minOf(4000L, 700L * tries))
            }
        }
    }

    // Tek bağlantı: kopunca kaldığı yerden devam eder (sunucu destekliyorsa).
    private fun single(d: Item, p: Probe, tmp: File) {
        val base = d.done.get()
        var pos = 0L
        var tries = 0
        var finished = false
        RandomAccessFile(tmp, "rw").use { raf ->
            raf.setLength(0)
            while (!d.cancelled && !finished) {
                try {
                    val range = if (pos > 0) "bytes=$pos-" else null
                    val u = if (tries % 2 == 0) p.finalUrl else d.f.url
                    val k = Net.open(u, d.ua, d.f.referer, d.cookie, range)
                    try {
                        val code = k.code
                        if (pos > 0 && code == 200) {
                            pos = 0L
                            d.done.set(base)
                            raf.setLength(0)
                        } else if (code != 200 && code != 206) {
                            throw Exception("HTTP $code")
                        }
                        val ins = k.c.inputStream
                        raf.seek(pos)
                        val buf = ByteArray(131072)
                        while (!d.cancelled) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            raf.write(buf, 0, n)
                            pos += n
                            d.done.addAndGet(n.toLong())
                        }
                        if (!d.cancelled) {
                            if (p.total > 0 && pos < p.total) throw Exception("Eksik veri")
                            finished = true
                        }
                    } finally {
                        k.c.disconnect()
                    }
                } catch (e: Exception) {
                    if (d.cancelled) break
                    tries++
                    if (tries > 6) throw e
                    Thread.sleep(minOf(4000L, 800L * tries))
                }
            }
            raf.setLength(pos)
        }
    }

    // ---------------------------------------------------------------- Parçalı akış (HLS/DASH)
    private fun fetchSegs(d: Item, m: HlsMedia, f: File) {
        FileOutputStream(f).use { out ->
            val ini = m.initSeg
            if (ini != null) out.write(segment(d, ini, false))
            pump(d, m.segs, out, !m.fmp4)
        }
    }

    // Parçalar paralel iner, sırayla dosyaya yazılır (birleştirme).
    private fun pump(d: Item, segs: List<Seg>, out: OutputStream, ts: Boolean) {
        val n = segs.size
        val next = AtomicInteger(0)
        val written = AtomicInteger(0)
        val failure = AtomicReference<Exception?>(null)
        val lock = ReentrantLock()
        val cond = lock.newCondition()
        val threads = (0 until minOf(SEG_THREADS, n)).map {
            Thread {
                try {
                    while (d.cancelled.not() && failure.get() == null) {
                        val i = next.getAndIncrement()
                        if (i >= n) break
                        lock.lock()
                        try {
                            while (i - written.get() >= SEG_WINDOW && !d.cancelled && failure.get() == null) {
                                cond.await(300, TimeUnit.MILLISECONDS)
                            }
                        } finally {
                            lock.unlock()
                        }
                        if (d.cancelled || failure.get() != null) break
                        val data = segment(d, segs[i], ts)
                        lock.lock()
                        try {
                            while (written.get() != i && !d.cancelled && failure.get() == null) {
                                cond.await(300, TimeUnit.MILLISECONDS)
                            }
                            if (!d.cancelled && failure.get() == null) {
                                out.write(data)
                                written.incrementAndGet()
                                d.done.incrementAndGet()
                            }
                            cond.signalAll()
                        } finally {
                            lock.unlock()
                        }
                    }
                } catch (e: Exception) {
                    failure.compareAndSet(null, e)
                    lock.lock()
                    try {
                        cond.signalAll()
                    } finally {
                        lock.unlock()
                    }
                }
            }.also { it.start() }
        }
        threads.forEach { it.join() }
        val f = failure.get()
        if (f != null) throw f
    }

    private fun seqIv(seq: Long): ByteArray {
        val iv = ByteArray(16)
        var s = seq
        for (i in 15 downTo 8) {
            iv[i] = (s and 0xffL).toByte()
            s = s shr 8
        }
        return iv
    }

    private fun dec(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return c.doFinal(data)
    }

    // Bazı siteler TS parçasının başına sahte resim başlığı koyar: ilk TS senkron baytına kadar kes.
    private fun stripFake(b: ByteArray): ByteArray {
        if (b.size < 1500 || b[0] == 0x47.toByte()) return b
        val lim = minOf(8192, b.size - 400)
        for (i in 1 until lim) {
            if (b[i] == 0x47.toByte() && b[i + 188] == 0x47.toByte() && b[i + 376] == 0x47.toByte()) {
                return b.copyOfRange(i, b.size)
            }
        }
        return b
    }

    private fun segment(d: Item, s: Seg, ts: Boolean): ByteArray {
        var tries = 0
        while (true) {
            if (d.cancelled) throw Exception("İptal")
            try {
                val k = Net.open(s.url, d.ua, d.f.referer, d.cookie, s.range)
                try {
                    val code = k.code
                    if (code != 200 && code != 206) throw Exception("HTTP $code")
                    var b = k.c.inputStream.use { it.readBytes() }
                    val key = s.key
                    if (key != null) b = dec(b, key, s.iv ?: seqIv(s.seq))
                    if (ts) b = stripFake(b)
                    return b
                } finally {
                    k.c.disconnect()
                }
            } catch (e: Exception) {
                if (d.cancelled) throw e
                tries++
                if (tries > 5) throw e
                Thread.sleep(800L * tries)
            }
        }
    }
}

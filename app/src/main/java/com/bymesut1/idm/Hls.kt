package com.bymesut1.idm

import java.net.URL

class Seg(val url: String, val key: ByteArray?, val iv: ByteArray?, val seq: Long, val range: String?)

class HlsMedia(val segs: List<Seg>, val initSeg: Seg?) {
    val fmp4: Boolean get() = initSeg != null
}

class HlsPlan(val video: HlsMedia, val audio: HlsMedia?)

object Hls {
    private val attrRe = Regex("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)")

    fun resolve(base: String, rel: String): String = URL(URL(base), rel).toString()

    fun attrs(line: String): Map<String, String> {
        val m = HashMap<String, String>()
        val body = line.substringAfter(':')
        for (r in attrRe.findAll(body)) m[r.groupValues[1]] = r.groupValues[2].trim('"')
        return m
    }

    private fun hexToBytes(hex: String): ByteArray {
        val h = hex.padStart(32, '0')
        return ByteArray(16) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /** Master playlist ise en yüksek kaliteyi + (ayrı ses varsa) sesini seçer. */
    fun plan(url: String, text: (String) -> String, bytes: (String) -> ByteArray): HlsPlan {
        var pl = text(url)
        if (!pl.trimStart().startsWith("#EXTM3U")) throw Exception("Geçersiz playlist")
        var videoUrl = url
        var audioUrl: String? = null
        if (pl.contains("#EXT-X-STREAM-INF")) {
            val ls = pl.lines().map { it.trim() }
            val audios = ls.filter { it.startsWith("#EXT-X-MEDIA:") }.map { attrs(it) }
                .filter { it["TYPE"] == "AUDIO" && !it["URI"].isNullOrEmpty() }
            var best = -1L
            var bestGroup: String? = null
            for (i in ls.indices) {
                if (!ls[i].startsWith("#EXT-X-STREAM-INF")) continue
                val a = attrs(ls[i])
                val bw = a["BANDWIDTH"]?.toLongOrNull() ?: a["AVERAGE-BANDWIDTH"]?.toLongOrNull() ?: 0L
                val u = ls.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: continue
                if (bw > best) {
                    best = bw
                    videoUrl = resolve(url, u)
                    bestGroup = a["AUDIO"]
                }
            }
            if (best < 0) throw Exception("Kalite listesi boş")
            if (bestGroup != null) {
                val cand = audios.filter { it["GROUP-ID"] == bestGroup }
                val pick = cand.firstOrNull { it["DEFAULT"] == "YES" } ?: cand.firstOrNull()
                val au = pick?.get("URI")
                if (au != null) audioUrl = resolve(url, au)
            }
            pl = text(videoUrl)
        }
        val v = media(videoUrl, pl, bytes)
        var a: HlsMedia? = null
        if (audioUrl != null && audioUrl != videoUrl) a = media(audioUrl, text(audioUrl), bytes)
        return HlsPlan(v, a)
    }

    fun media(base: String, pl: String, bytes: (String) -> ByteArray): HlsMedia {
        val lines = pl.lines().map { it.trim() }
        var seq = lines.firstOrNull { it.startsWith("#EXT-X-MEDIA-SEQUENCE:") }
            ?.substringAfter(':')?.trim()?.toLongOrNull() ?: 0L
        var key: ByteArray? = null
        var ivAttr: ByteArray? = null
        var initSeg: Seg? = null
        var pending: String? = null
        val lastEnd = HashMap<String, Long>()
        val keyCache = HashMap<String, ByteArray>()
        val segs = ArrayList<Seg>()
        for (ln in lines) {
            if (ln.startsWith("#EXT-X-KEY:")) {
                val a = attrs(ln)
                val method = a["METHOD"] ?: "NONE"
                if (method == "AES-128") {
                    val ku = a["URI"] ?: throw Exception("Anahtar adresi yok")
                    val full = resolve(base, ku)
                    var kb = keyCache[full]
                    if (kb == null) {
                        kb = bytes(full)
                        keyCache[full] = kb
                    }
                    key = kb
                    val ivs = a["IV"]
                    ivAttr = if (ivs != null) hexToBytes(ivs.removePrefix("0x").removePrefix("0X")) else null
                } else if (method == "NONE") {
                    key = null
                    ivAttr = null
                } else {
                    throw Exception("Desteklenmeyen şifreleme: $method (DRM korumalı olabilir)")
                }
            } else if (ln.startsWith("#EXT-X-MAP:")) {
                val a = attrs(ln)
                val u = a["URI"]
                if (u != null) {
                    var r: String? = null
                    val br = a["BYTERANGE"]
                    if (br != null && br.contains('@')) {
                        val len = br.substringBefore('@').toLong()
                        val off = br.substringAfter('@').toLong()
                        r = "bytes=$off-${off + len - 1}"
                    }
                    initSeg = Seg(resolve(base, u), null, null, 0L, r)
                }
            } else if (ln.startsWith("#EXT-X-BYTERANGE:")) {
                pending = ln.substringAfter(':').trim()
            } else if (ln.isNotEmpty() && !ln.startsWith("#")) {
                val su = resolve(base, ln)
                var r: String? = null
                val p = pending
                if (p != null) {
                    val len = p.substringBefore('@').toLong()
                    val off = if (p.contains('@')) p.substringAfter('@').toLong() else (lastEnd[su] ?: 0L)
                    r = "bytes=$off-${off + len - 1}"
                    lastEnd[su] = off + len
                    pending = null
                }
                segs.add(Seg(su, key, ivAttr, seq, r))
                seq++
            }
        }
        if (segs.isEmpty()) throw Exception("Parça bulunamadı")
        return HlsMedia(segs, initSeg)
    }
}

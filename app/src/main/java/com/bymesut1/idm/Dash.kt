package com.bymesut1.idm

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

class DashTrack(val media: HlsMedia?, val single: String?)
class DashPlan(val video: DashTrack, val audio: DashTrack?)

object Dash {
    private class Cand(val score: Long, val aset: Element, val rep: Element, val base: String)

    private val numRe = Regex("\\\$Number(?:%0(\\d+)d)?\\\$")
    private val timeRe = Regex("\\\$Time(?:%0(\\d+)d)?\\\$")

    private fun local(n: String) = n.substringAfter(':')

    private fun kids(e: Element, name: String): List<Element> {
        val out = ArrayList<Element>()
        var n: Node? = e.firstChild
        while (n != null) {
            if (n.nodeType == Node.ELEMENT_NODE && local(n.nodeName) == name) out.add(n as Element)
            n = n.nextSibling
        }
        return out
    }

    private fun Element.a(name: String): String = getAttribute(name) ?: ""

    private fun baseText(e: Element): String? = kids(e, "BaseURL").firstOrNull()?.textContent?.trim()

    private fun join(base: String, rel: String?): String =
        if (rel.isNullOrEmpty()) base else URL(URL(base), rel).toString()

    private fun dur(s: String): Double {
        if (s.isEmpty()) return 0.0
        val m = Regex("P(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:([\\d.]+)S)?)?").matchEntire(s) ?: return 0.0
        val d = m.groupValues[1].toDoubleOrNull() ?: 0.0
        val h = m.groupValues[2].toDoubleOrNull() ?: 0.0
        val mi = m.groupValues[3].toDoubleOrNull() ?: 0.0
        val sec = m.groupValues[4].toDoubleOrNull() ?: 0.0
        return d * 86400 + h * 3600 + mi * 60 + sec
    }

    private fun pad(v: Long, w: String): String {
        val n = w.toIntOrNull() ?: return v.toString()
        return v.toString().padStart(n, '0')
    }

    private fun fill(t: String, id: String, bw: String, num: Long, time: Long): String {
        var s = t.replace("\$RepresentationID\$", id).replace("\$Bandwidth\$", bw)
        s = numRe.replace(s) { m -> pad(num, m.groupValues[1]) }
        s = timeRe.replace(s) { m -> pad(time, m.groupValues[1]) }
        return s.replace("\$\$", "\$")
    }

    /** MPD içindeki tek dosyalık altyazı kanalları: (dil, adres). */
    fun subtitles(mpdUrl: String, xml: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        try {
            val dbf = DocumentBuilderFactory.newInstance()
            dbf.isNamespaceAware = false
            val doc = dbf.newDocumentBuilder().parse(InputSource(StringReader(xml)))
            val mpd = doc.documentElement
            val baseM = join(mpdUrl, baseText(mpd))
            for (period in kids(mpd, "Period")) {
                val baseP = join(baseM, baseText(period))
                for (aset in kids(period, "AdaptationSet")) {
                    val mime = aset.a("mimeType")
                    val ct = aset.a("contentType")
                    if (!(ct == "text" || mime.startsWith("text/") || mime.contains("ttml"))) continue
                    val baseA = join(baseP, baseText(aset))
                    val lang = aset.a("lang")
                    for (rep in kids(aset, "Representation")) {
                        val bt = baseText(rep) ?: continue
                        out.add(Pair(lang.ifEmpty { rep.a("id") }, join(baseA, bt)))
                    }
                }
            }
        } catch (e: Exception) {
        }
        return out
    }

    fun plan(mpdUrl: String, xml: String): DashPlan {
        val dbf = DocumentBuilderFactory.newInstance()
        dbf.isNamespaceAware = false
        val doc = dbf.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val mpd = doc.documentElement
        if (mpd.a("type") == "dynamic") throw Exception("Canlı (dynamic) DASH yayınları desteklenmiyor")
        if (doc.getElementsByTagName("ContentProtection").length > 0) {
            throw Exception("DRM korumalı akış (ContentProtection): indirilemez")
        }
        val total = dur(mpd.a("mediaPresentationDuration"))
        val period = kids(mpd, "Period").firstOrNull() ?: throw Exception("Period bulunamadı")
        val pdur = dur(period.a("duration")).let { if (it > 0) it else total }
        val baseM = join(mpdUrl, baseText(mpd))
        val baseP = join(baseM, baseText(period))

        var bv: Cand? = null
        var ba: Cand? = null
        for (aset in kids(period, "AdaptationSet")) {
            val baseA = join(baseP, baseText(aset))
            for (rep in kids(aset, "Representation")) {
                val mime = rep.a("mimeType").ifEmpty { aset.a("mimeType") }
                val ctype = aset.a("contentType")
                val codecs = rep.a("codecs").ifEmpty { aset.a("codecs") }
                val bw = rep.a("bandwidth").toLongOrNull() ?: 0L
                val isV = mime.startsWith("video") || ctype == "video"
                val isA = mime.startsWith("audio") || ctype == "audio"
                if (!isV && !isA) continue
                val pref = if (isV) {
                    codecs.startsWith("avc1") || codecs.startsWith("hvc1") || codecs.startsWith("hev1")
                } else {
                    codecs.startsWith("mp4a")
                }
                val score = (if (pref) 1_000_000_000_000L else 0L) + bw
                val c = Cand(score, aset, rep, baseA)
                if (isV) {
                    val cur = bv
                    if (cur == null || c.score > cur.score) bv = c
                } else {
                    val cur = ba
                    if (cur == null || c.score > cur.score) ba = c
                }
            }
        }
        val bvv = bv
        val baa = ba
        if (bvv == null && baa == null) throw Exception("Video/ses bulunamadı")
        val vt = if (bvv != null) track(bvv, pdur) else null
        val at = if (baa != null) track(baa, pdur) else null
        if (vt != null) return DashPlan(vt, at)
        return DashPlan(at!!, null)
    }

    private fun track(c: Cand, durSec: Double): DashTrack {
        val rep = c.rep
        val aset = c.aset
        val base = join(c.base, baseText(rep))
        val tpl = kids(rep, "SegmentTemplate").firstOrNull() ?: kids(aset, "SegmentTemplate").firstOrNull()
        val sl = kids(rep, "SegmentList").firstOrNull() ?: kids(aset, "SegmentList").firstOrNull()
        val id = rep.a("id")
        val bw = rep.a("bandwidth")
        val segs = ArrayList<Seg>()
        var initSeg: Seg? = null

        if (tpl != null) {
            val initT = tpl.a("initialization")
            val mediaT = tpl.a("media")
            val timescale = tpl.a("timescale").toLongOrNull() ?: 1L
            val startNumber = tpl.a("startNumber").toLongOrNull() ?: 1L
            val dur = tpl.a("duration").toLongOrNull()
            if (initT.isNotEmpty()) initSeg = Seg(join(base, fill(initT, id, bw, 0, 0)), null, null, 0L, null)
            val tl = kids(tpl, "SegmentTimeline").firstOrNull()
            if (tl != null) {
                var t = 0L
                var n = startNumber
                for (s in kids(tl, "S")) {
                    val st = s.a("t").toLongOrNull()
                    if (st != null) t = st
                    val d = s.a("d").toLongOrNull() ?: continue
                    val r = s.a("r").toIntOrNull() ?: 0
                    val count = if (r >= 0) r + 1 else {
                        if (durSec <= 0 || d <= 0) 1 else Math.ceil((durSec * timescale - t) / d).toInt()
                    }
                    for (i in 0 until count) {
                        segs.add(Seg(join(base, fill(mediaT, id, bw, n, t)), null, null, n, null))
                        t += d
                        n++
                    }
                }
            } else if (dur != null && dur > 0) {
                if (durSec <= 0) throw Exception("Yayın süresi bilinmiyor")
                val segDur = dur.toDouble() / timescale
                val count = Math.ceil(durSec / segDur).toInt()
                for (i in 0 until count) {
                    segs.add(Seg(join(base, fill(mediaT, id, bw, startNumber + i, i.toLong() * dur)), null, null, startNumber + i, null))
                }
            }
            if (segs.isEmpty()) throw Exception("DASH parça listesi çıkarılamadı")
            return DashTrack(HlsMedia(segs, initSeg), null)
        }

        if (sl != null) {
            val ini = kids(sl, "Initialization").firstOrNull()
            val iu = ini?.a("sourceURL") ?: ""
            if (iu.isNotEmpty()) initSeg = Seg(join(base, iu), null, null, 0L, null)
            var n = 0L
            for (su in kids(sl, "SegmentURL")) {
                val mu = su.a("media")
                if (mu.isEmpty()) continue
                val mr = su.a("mediaRange")
                segs.add(Seg(join(base, mu), null, null, n, if (mr.isNotEmpty()) "bytes=$mr" else null))
                n++
            }
            if (segs.isEmpty()) throw Exception("DASH parça listesi boş")
            return DashTrack(HlsMedia(segs, initSeg), null)
        }

        // SegmentBase / düz BaseURL: tek parça dosya
        if (base == c.base && kids(rep, "BaseURL").isEmpty()) throw Exception("DASH parça bilgisi bulunamadı")
        return DashTrack(null, base)
    }
}

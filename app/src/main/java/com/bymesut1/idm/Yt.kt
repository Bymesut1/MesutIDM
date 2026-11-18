package com.bymesut1.idm

import org.json.JSONObject

class YFmt(
    val itag: Int, val url: String, val mime: String, val codecs: String,
    val w: Int, val h: Int, val label: String, val bitrate: Long, val size: Long
)

class YtResult(val title: String, val entries: List<Found>, val source: String)

/**
 * YouTube: önce uygulama istemcisi (ANDROID_VR) ile doğrudan akış adresleri alınır,
 * olmazsa Invidious. Video + ses ayrı gelir; Found.PAIR olarak indirilip birleştirilir.
 * NOT: YouTube bu yöntemleri sık değiştirir. Çalışmazsa VR_VERSION / VR_UA değerlerini güncelle.
 */
object Yt {
    const val VR_VERSION = "1.65.10"
    const val VR_UA = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"

    // Invidious adresleri (sırayla denenir). İstersen kendi adresini en başa yaz.
    private val invidious = listOf(
        "https://yt.chocolatemoo53.com",
        "https://yewtu.be",
        "https://inv.nadeko.net"
    )

    fun videoId(url: String): String? =
        Regex("(?:[?&]v=|youtu\\.be/|/shorts/|/embed/|/live/)([A-Za-z0-9_-]{11})").find(url)?.groupValues?.get(1)

    fun resolve(id: String): YtResult? {
        try {
            val r = viaInnertube(id)
            if (r != null && r.entries.isNotEmpty()) return r
        } catch (e: Exception) {
        }
        try {
            val r = viaInvidious(id)
            if (r != null && r.entries.isNotEmpty()) return r
        } catch (e: Exception) {
        }
        return null
    }

    private fun codecsOf(t: String): String =
        Regex("codecs=\"([^\"]*)\"").find(t)?.groupValues?.get(1) ?: ""

    private fun viaInnertube(id: String): YtResult? {
        val client = JSONObject()
            .put("clientName", "ANDROID_VR")
            .put("clientVersion", VR_VERSION)
            .put("deviceMake", "Oculus")
            .put("deviceModel", "Quest 3")
            .put("osName", "Android")
            .put("osVersion", "12L")
            .put("androidSdkVersion", 32)
            .put("userAgent", VR_UA)
            .put("hl", "en")
            .put("timeZone", "UTC")
            .put("utcOffsetMinutes", 0)
        val body = JSONObject()
            .put("context", JSONObject().put("client", client))
            .put("videoId", id)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
            .put(
                "playbackContext",
                JSONObject().put("contentPlaybackContext", JSONObject().put("html5Preference", "HTML5_PREF_WANTS"))
            )
        val hdr = mapOf(
            "Content-Type" to "application/json",
            "X-YouTube-Client-Name" to "28",
            "X-YouTube-Client-Version" to VR_VERSION,
            "Origin" to "https://www.youtube.com"
        )
        val k = Net.open(
            "https://www.youtube.com/youtubei/v1/player?prettyPrint=false", VR_UA, "", "",
            null, body.toString().toByteArray(), hdr, false
        )
        var txt = ""
        try {
            if (k.code != 200) return null
            txt = k.c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            k.c.disconnect()
        }
        val j = JSONObject(txt)
        if (j.optJSONObject("playabilityStatus")?.optString("status") != "OK") return null
        val title = j.optJSONObject("videoDetails")?.optString("title") ?: "YouTube"
        val sd = j.optJSONObject("streamingData") ?: return null
        val list = ArrayList<YFmt>()
        for (key in arrayOf("formats", "adaptiveFormats")) {
            val arr = sd.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val f = fmtOf(arr.getJSONObject(i))
                if (f != null) list.add(f)
            }
        }
        if (list.isEmpty()) return null
        return YtResult(title, build(title, list, VR_UA), "android_vr")
    }

    private fun fmtOf(o: JSONObject): YFmt? {
        val u = o.optString("url")
        if (u.isEmpty()) return null
        val mt = o.optString("mimeType")
        val at = o.optJSONObject("audioTrack")
        if (at != null && !at.optBoolean("audioIsDefault", true)) return null
        val h = o.optInt("height", 0)
        return YFmt(
            o.optInt("itag"), u, mt.substringBefore(';').trim(), codecsOf(mt),
            o.optInt("width", 0), h,
            o.optString("qualityLabel", if (h > 0) "${h}p" else ""),
            o.optLong("bitrate", 0L),
            o.optString("contentLength", "0").toLongOrNull() ?: 0L
        )
    }

    private fun viaInvidious(id: String): YtResult? {
        for (inst in invidious) {
            try {
                val c = java.net.URL("$inst/api/v1/videos/$id?local=true").openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 10000
                c.readTimeout = 15000
                if (c.responseCode != 200) continue
                val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                val title = j.optString("title", "YouTube")
                val list = ArrayList<YFmt>()
                for (key in arrayOf("formatStreams", "adaptiveFormats")) {
                    val arr = j.optJSONArray(key) ?: continue
                    for (i in 0 until arr.length()) {
                        val f = fmtOfInv(inst, arr.getJSONObject(i))
                        if (f != null) list.add(f)
                    }
                }
                if (list.isEmpty()) continue
                return YtResult(title, build(title, list, null), "invidious")
            } catch (e: Exception) {
            }
        }
        return null
    }

    private fun fmtOfInv(inst: String, o: JSONObject): YFmt? {
        var u = o.optString("url")
        if (u.isEmpty()) return null
        if (u.startsWith("/")) u = inst + u
        val type = o.optString("type")
        val res = o.optString("resolution")
        val h = Regex("(\\d+)").find(res)?.groupValues?.get(1)?.toIntOrNull()
            ?: (o.optString("size").substringAfter('x', "").toIntOrNull() ?: 0)
        return YFmt(
            o.optInt("itag"), u, type.substringBefore(';').trim(), codecsOf(type), 0, h,
            o.optString("qualityLabel", if (h > 0) "${h}p" else ""),
            o.optString("bitrate", "0").toLongOrNull() ?: 0L,
            o.optString("clen", "0").toLongOrNull() ?: 0L
        )
    }

    private fun lab(f: YFmt): String = f.label.ifEmpty { if (f.h > 0) "${f.h}p" else "?" }

    private fun sz(n: Long): String = if (n > 0) " • ~" + U.fmt(n) else ""

    private fun pair(base: String, v: YFmt, a: YFmt, ext: String, ua: String?): Found {
        val size = if (v.size > 0 && a.size > 0) v.size + a.size else -1L
        val l = lab(v)
        return Found(
            v.url, "$base [$l].$ext", "", Found.PAIR, a.url,
            "$l • ${ext.uppercase()} • video+ses birleşik" + sz(size), size, ua, 1
        )
    }

    fun build(title: String, all: List<YFmt>, ua: String?): List<Found> {
        val out = ArrayList<Found>()
        val base = U.safe(title).take(70)
        val audioMp4 = all.filter { it.mime == "audio/mp4" }.maxByOrNull { it.bitrate }
        val audioWebm = all.filter { it.mime == "audio/webm" }.maxByOrNull { it.bitrate }
        val videos = all.filter { it.mime.startsWith("video/") }
        val adaptive = videos.filter { !it.codecs.contains(",") && it.h > 0 }
        val muxed = videos.filter { it.codecs.contains(",") }
        val heights = adaptive.map { it.h }.distinct().sortedDescending()
        for (h in heights) {
            val c = adaptive.filter { it.h == h }
            val mp4 = c.filter { it.mime == "video/mp4" && it.codecs.startsWith("avc1") }.maxByOrNull { it.bitrate }
            val webm = c.filter {
                it.mime == "video/webm" && (it.codecs.startsWith("vp9") || it.codecs.startsWith("vp09") || it.codecs.startsWith("vp8"))
            }.maxByOrNull { it.bitrate }
            if (mp4 != null && audioMp4 != null) out.add(pair(base, mp4, audioMp4, "mp4", ua))
            else if (webm != null && audioWebm != null) out.add(pair(base, webm, audioWebm, "webm", ua))
        }
        for (m in muxed.sortedByDescending { it.h }) {
            out.add(
                Found(
                    m.url, "$base [${lab(m)}].mp4", "", Found.FILE, null,
                    "${lab(m)} • ses+video hazır (MP4)" + sz(m.size), m.size, ua, 1
                )
            )
        }
        if (audioMp4 != null) {
            out.add(
                Found(
                    audioMp4.url, "$base [ses].m4a", "", Found.FILE, null,
                    "Sadece ses (M4A)" + sz(audioMp4.size), audioMp4.size, ua, 1
                )
            )
        }
        return out
    }
}

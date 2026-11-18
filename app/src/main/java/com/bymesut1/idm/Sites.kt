package com.bymesut1.idm

import org.json.JSONObject
import java.net.URLDecoder

/**
 * Siteye özel çözücüler: Dailymotion, OK.ru, VK (vk.com / vkvideo.ru), Mail.ru.
 * Bu siteler yöntemlerini sık değiştirir; çalışmazsa sayfada videoyu oynatınca genel yakalayıcı devreye girer.
 */
object Sites {
    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    fun key(url: String): String? {
        val h = U.host(url).lowercase()
        if (h.endsWith("dailymotion.com") || h == "dai.ly") {
            val m = Regex("(?:/video/|dai\\.ly/)([A-Za-z0-9]+)").find(url) ?: return null
            return "dm:" + m.groupValues[1]
        }
        if (h.endsWith("ok.ru") || h.endsWith("odnoklassniki.ru")) {
            val m = Regex("/(?:video|videoembed|live)/(\\d+(?:-\\d+)?)").find(url) ?: return null
            return "ok:" + m.groupValues[1]
        }
        if (h.endsWith("vk.com") || h.endsWith("vkvideo.ru") || h.endsWith("vk.ru")) {
            val m = Regex("(?:video|clip)(-?\\d+)_(\\d+)").find(url) ?: return null
            return "vk:" + m.groupValues[1] + "_" + m.groupValues[2]
        }
        if (h.endsWith("mail.ru") && (url.contains("/video") || h.startsWith("video."))) {
            return "mr:" + url.substringBefore('#')
        }
        return null
    }

    fun resolve(url: String): YtResult? {
        val k = key(url) ?: return null
        return try {
            when {
                k.startsWith("dm:") -> dailymotion(k.substring(3))
                k.startsWith("ok:") -> okru(k.substring(3))
                k.startsWith("vk:") -> {
                    val p = k.substring(3).split("_")
                    vk(p[0], p[1], url)
                }
                k.startsWith("mr:") -> mailru(url)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun unesc(s: String): String = s
        .replace("\\/", "/").replace("\\u0026", "&").replace("\\\"", "\"")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    private fun fix(u: String): String = if (u.startsWith("//")) "https:$u" else u

    // ---------------------------------------------------------------- Dailymotion
    private fun dailymotion(id: String): YtResult? {
        val ref = "https://www.dailymotion.com/"
        val txt = Net.text("https://www.dailymotion.com/player/metadata/video/$id", UA, ref, "")
        val j = JSONObject(txt)
        val title = j.optString("title", "Dailymotion")
        val base = U.safe(title).take(70)
        val out = ArrayList<Found>()
        val q = j.optJSONObject("qualities")
        if (q != null) {
            val keys = ArrayList<String>()
            val itr = q.keys()
            while (itr.hasNext()) keys.add(itr.next())
            keys.sortWith(compareByDescending<String> { it == "auto" }.thenByDescending { it.toIntOrNull() ?: 0 })
            for (k in keys) {
                val arr = q.optJSONArray(k) ?: continue
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val u = o.optString("url")
                    if (u.isEmpty()) continue
                    val type = o.optString("type")
                    val ql = if (k == "auto") "otomatik" else "${k}p"
                    if (type.contains("mpegURL", true)) {
                        out.add(Found(u, "$base [$ql].m3u8", ref, Found.HLS, null, "Dailymotion • HLS ($ql) • video+ses birleşik", -1L, null, 1))
                    } else if (type.contains("mp4")) {
                        out.add(Found(u, "$base [$ql].mp4", ref, Found.FILE, null, "Dailymotion • $ql • MP4", -1L, null, 1))
                    }
                }
            }
        }
        val sd = j.optJSONObject("subtitles")?.optJSONObject("data")
        if (sd != null) {
            val itr = sd.keys()
            while (itr.hasNext()) {
                val lang = itr.next()
                val o = sd.optJSONObject(lang) ?: continue
                val label = o.optString("label", lang)
                val u = o.optJSONArray("urls")?.optString(0) ?: ""
                if (u.isEmpty()) continue
                val ext = if (u.substringBefore('?').endsWith(".srt")) "srt" else "vtt"
                out.add(Found(u, "$base [${U.safe(label)}].$ext", ref, Found.FILE, null, "Altyazı ($label) • ${ext.uppercase()}", -1L, null, 1))
            }
        }
        return if (out.isEmpty()) null else YtResult(title, out, "Dailymotion")
    }

    // ---------------------------------------------------------------- OK.ru
    private fun okru(id: String): YtResult? {
        val ref = "https://ok.ru/"
        val page = Net.text("https://ok.ru/video/$id", UA, ref, "")
        val m = Regex("data-options=([\"'])(\\{.+?\\})\\1", RegexOption.DOT_MATCHES_ALL).find(page) ?: return null
        val player = JSONObject(unesc(m.groupValues[2]))
        val flash = player.optJSONObject("flashvars") ?: return null
        val ms = flash.optString("metadata")
        if (ms.isEmpty()) return null
        val meta = JSONObject(ms)
        val movie = meta.optJSONObject("movie")
        val title = movie?.optString("title") ?: "OK.ru"
        val base = U.safe(title).take(70)
        val out = ArrayList<Found>()
        val rank = listOf("ultra", "quad", "full", "hd", "sd", "low", "lowest", "mobile")
        val names = mapOf("ultra" to "2160p", "quad" to "1440p", "full" to "1080p", "hd" to "720p", "sd" to "480p", "low" to "360p", "lowest" to "240p", "mobile" to "144p")
        val vids = ArrayList<Pair<String, String>>()
        val va = meta.optJSONArray("videos")
        if (va != null) {
            for (i in 0 until va.length()) {
                val o = va.optJSONObject(i) ?: continue
                val u = o.optString("url")
                if (u.isNotEmpty()) vids.add(Pair(o.optString("name"), u))
            }
        }
        vids.sortBy { val r = rank.indexOf(it.first); if (r < 0) 99 else r }
        for (v in vids) {
            val ql = names[v.first] ?: v.first
            val f = Found(v.second, "$base [$ql].mp4", ref, Found.FILE, null, "OK.ru • $ql • MP4", -1L, null, 1)
            f.noCookies = true
            out.add(f)
        }
        var hls = meta.optString("hlsManifestUrl")
        if (hls.isEmpty()) hls = meta.optString("ondemandHls")
        if (hls.isNotEmpty()) {
            val f = Found(hls, "$base [HLS].m3u8", ref, Found.HLS, null, "OK.ru • HLS • video+ses birleşik", -1L, null, 1)
            f.noCookies = true
            out.add(f)
        }
        val dash = meta.optString("ondemandDash")
        if (dash.isNotEmpty()) {
            val f = Found(dash, "$base [DASH].mp4", ref, Found.DASH, null, "OK.ru • DASH • video+ses birleşik", -1L, null, 1)
            f.noCookies = true
            out.add(f)
        }
        val tracks = movie?.optJSONArray("subtitleTracks")
        if (tracks != null) {
            for (i in 0 until tracks.length()) {
                val o = tracks.optJSONObject(i) ?: continue
                val u = o.optString("url")
                if (u.isEmpty()) continue
                val lang = o.optString("language", "?")
                out.add(Found(u, "$base [$lang].vtt", ref, Found.FILE, null, "Altyazı ($lang) • VTT", -1L, null, 1))
            }
        }
        return if (out.isEmpty()) null else YtResult(title, out, "OK.ru")
    }

    // ---------------------------------------------------------------- VK
    private fun vk(oid: String, vid: String, orig: String): YtResult? {
        val hash = Regex("[?&]hash=([0-9a-fA-F]+)").find(orig)?.groupValues?.get(1)
        val bases = listOf(
            "https://vk.com/video_ext.php?oid=$oid&id=$vid&hd=2",
            "https://vkvideo.ru/video_ext.php?oid=$oid&id=$vid&hd=2"
        )
        for (b in bases) {
            try {
                val u = if (hash != null) "$b&hash=$hash" else b
                val t = unesc(Net.text(u, UA, "https://vk.com/", ""))
                val title = Regex("<title>([^<]*)</title>").find(t)?.groupValues?.get(1)?.trim()?.ifEmpty { null } ?: "VK video"
                val base = U.safe(title).take(70)
                val out = ArrayList<Found>()
                val seen = HashSet<String>()
                val qs = ArrayList<Pair<Int, String>>()
                for (m in Regex("\"url(\\d{3,4})\"\\s*:\\s*\"(https?://[^\"]+)\"").findAll(t)) {
                    qs.add(Pair(m.groupValues[1].toInt(), m.groupValues[2]))
                }
                qs.sortByDescending { it.first }
                for (q in qs) {
                    if (!seen.add(q.second)) continue
                    out.add(Found(q.second, "$base [${q.first}p].mp4", "https://vk.com/", Found.FILE, null, "VK • ${q.first}p • MP4", -1L, null, 1))
                }
                for (m in Regex("\"(hls|hls_ondemand|hls_live|dash_sep|dash_webm|dash_uni|postlive_mp4)\"\\s*:\\s*\"(https?://[^\"]+)\"").findAll(t)) {
                    val k = m.groupValues[1]
                    val url = m.groupValues[2]
                    if (!seen.add(url)) continue
                    when {
                        k.startsWith("hls") -> out.add(Found(url, "$base [HLS].m3u8", "https://vk.com/", Found.HLS, null, "VK • HLS • video+ses birleşik", -1L, null, 1))
                        k.startsWith("dash") -> out.add(Found(url, "$base [$k].mp4", "https://vk.com/", Found.DASH, null, "VK • DASH • video+ses birleşik", -1L, null, 1))
                        else -> out.add(Found(url, "$base [$k].mp4", "https://vk.com/", Found.FILE, null, "VK • MP4", -1L, null, 1))
                    }
                }
                if (out.isNotEmpty()) return YtResult(title, out, "VK")
            } catch (e: Exception) {
            }
        }
        return null
    }

    // ---------------------------------------------------------------- Mail.ru
    private fun mailru(url: String): YtResult? {
        val page = Net.text(url, UA, "https://my.mail.ru/", "")
        var meta: String? = Regex("\"metaUrl\"\\s*:\\s*\"([^\"]+)\"").find(page)?.groupValues?.get(1)?.let { unesc(it) }
        if (meta == null) {
            val idm = Regex("/(\\d+)\\.html").find(url)?.groupValues?.get(1)
            if (idm != null) meta = "https://my.mail.ru/+/video/meta/$idm?xemail=&ajax_call=1&func_name=&mna=&mnb=&ext=1&_=${System.currentTimeMillis()}"
        }
        val metaUrl: String = meta ?: return null
        val mu = if (metaUrl.startsWith("//")) "https:$metaUrl" else Hls.resolve(url, metaUrl)
        val k = Net.open(mu, UA, url, "")
        var body = ""
        var ck: String? = null
        try {
            if (k.code != 200) return null
            body = k.c.inputStream.bufferedReader().use { it.readText() }
            val sc = k.c.headerFields["Set-Cookie"]
            if (sc != null) {
                for (c in sc) {
                    val kv = c.substringBefore(';').trim()
                    if (kv.startsWith("video_key=")) ck = kv
                }
            }
        } finally {
            k.c.disconnect()
        }
        val j = JSONObject(body)
        val title = j.optJSONObject("meta")?.optString("title")?.ifEmpty { null } ?: "Mail.ru video"
        val base = U.safe(title).take(70)
        val out = ArrayList<Found>()
        val arr = j.optJSONArray("videos") ?: return null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val u = o.optString("url")
            if (u.isEmpty()) continue
            val key = o.optString("key", "?")
            val f = Found(fix(u), "$base [$key].mp4", "https://my.mail.ru/", Found.FILE, null, "Mail.ru • $key • MP4", -1L, null, 1)
            f.cookie = ck
            out.add(f)
        }
        out.reverse()
        return if (out.isEmpty()) null else YtResult(title, out, "Mail.ru")
    }
}

package com.bymesut1.idm

import android.webkit.CookieManager
import java.net.HttpURLConnection
import java.net.URL

class Conn(val c: HttpURLConnection, val url: String) {
    val code: Int get() = c.responseCode
}

/**
 * IDM'in yaptığı gibi: tarayıcının çerezleri + Referer + User-Agent ile istek atar,
 * yönlendirmeleri (http->https dahil) kendisi takip eder, sıkıştırma kapalıdır (Range ile uyumlu).
 */
object Net {
    private const val LANG = "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"

    fun cookieFor(url: String, fallback: String): String {
        val ck = try {
            CookieManager.getInstance().getCookie(url)
        } catch (e: Exception) {
            null
        }
        return if (!ck.isNullOrEmpty()) ck else fallback
    }

    fun open(
        url: String,
        ua: String,
        referer: String,
        cookie: String,
        range: String? = null,
        post: ByteArray? = null,
        headers: Map<String, String>? = null,
        useCookies: Boolean = true
    ): Conn {
        var cur = url
        var hops = 0
        while (true) {
            val c = URL(cur).openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 25000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", ua)
            c.setRequestProperty("Accept", "*/*")
            c.setRequestProperty("Accept-Language", LANG)
            c.setRequestProperty("Accept-Encoding", "identity")
            if (useCookies) {
                val ck = cookieFor(cur, if (hops == 0) cookie else "")
                if (ck.isNotEmpty()) c.setRequestProperty("Cookie", ck)
            }
            if (referer.isNotEmpty()) c.setRequestProperty("Referer", referer)
            if (range != null) c.setRequestProperty("Range", range)
            if (headers != null) {
                for ((k, v) in headers) c.setRequestProperty(k, v)
            }
            if (post != null && hops == 0) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.outputStream.use { it.write(post) }
            }
            val code = c.responseCode
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                hops++
                if (loc.isNullOrEmpty() || hops > 10) throw Exception("Yönlendirme döngüsü")
                cur = URL(URL(cur), loc).toString()
                continue
            }
            return Conn(c, cur)
        }
    }

    fun text(url: String, ua: String, referer: String, cookie: String): String {
        val k = open(url, ua, referer, cookie)
        try {
            if (k.code !in 200..299) throw Exception("HTTP ${k.code}")
            return k.c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            k.c.disconnect()
        }
    }

    fun bytes(url: String, ua: String, referer: String, cookie: String): ByteArray {
        val k = open(url, ua, referer, cookie)
        try {
            if (k.code !in 200..299) throw Exception("HTTP ${k.code}")
            return k.c.inputStream.use { it.readBytes() }
        } finally {
            k.c.disconnect()
        }
    }
}

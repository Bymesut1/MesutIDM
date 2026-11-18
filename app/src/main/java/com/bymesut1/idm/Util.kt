package com.bymesut1.idm

import android.webkit.MimeTypeMap
import java.net.URL

object U {
    fun safe(s: String): String {
        val r = s.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_").trim().trim('.')
        return r.ifBlank { "dosya" }
    }

    fun fmt(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1048576 -> "%.0f KB".format(b / 1024.0)
        b < 1073741824 -> "%.1f MB".format(b / 1048576.0)
        else -> "%.2f GB".format(b / 1073741824.0)
    }

    fun host(u: String): String = try {
        URL(u).host ?: ""
    } catch (e: Exception) {
        ""
    }

    fun baseName(n: String): String {
        val i = n.lastIndexOf('.')
        return if (i > 0 && n.length - i <= 6) n.substring(0, i) else n
    }

    fun extOf(n: String): String {
        val i = n.lastIndexOf('.')
        return if (i > 0 && n.length - i <= 6) n.substring(i + 1).lowercase() else ""
    }

    fun mimeForExt(ext: String): String = when (ext) {
        "ts" -> "video/mp2t"
        "mkv" -> "video/x-matroska"
        "m4a" -> "audio/mp4"
        "webm" -> "video/webm"
        "flv" -> "video/x-flv"
        "srt" -> "application/x-subrip"
        "vtt" -> "text/vtt"
        "ass", "ssa" -> "text/x-ssa"
        "ttml", "dfxp" -> "application/ttml+xml"
        "sbv" -> "text/plain"
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    fun extForMime(ct: String): String? {
        val m = ct.substringBefore(';').trim().lowercase()
        return when (m) {
            "video/mp4" -> "mp4"
            "video/webm", "audio/webm" -> "webm"
            "video/x-matroska" -> "mkv"
            "video/quicktime" -> "mov"
            "video/x-flv" -> "flv"
            "video/x-msvideo" -> "avi"
            "audio/mpeg" -> "mp3"
            "audio/mp4", "audio/x-m4a" -> "m4a"
            "audio/aac" -> "aac"
            "audio/ogg", "application/ogg" -> "ogg"
            "audio/wav", "audio/x-wav" -> "wav"
            "application/pdf" -> "pdf"
            "application/zip" -> "zip"
            "application/x-rar-compressed", "application/vnd.rar" -> "rar"
            "application/x-7z-compressed" -> "7z"
            "application/vnd.android.package-archive" -> "apk"
            else -> null
        }
    }
}

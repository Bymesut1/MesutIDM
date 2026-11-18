package com.bymesut1.idm

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

class MuxOut(val ok: Boolean, val ext: String, val hasVideo: Boolean, val msg: String)

/**
 * Android'in kendi MediaExtractor/MediaMuxer'ı ile (ek kütüphane yok) ses+video birleştirme
 * ve TS -> MP4 dönüştürme. Sadece yeniden paketleme yapar, kalite kaybı olmaz.
 */
object Mux {
    private class Src(val ex: MediaExtractor, val format: MediaFormat) {
        var outTrack = -1
    }

    private fun open(f: File, prefix: String): Src? {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(f.absolutePath)
            for (i in 0 until ex.trackCount) {
                val fm = ex.getTrackFormat(i)
                val mime = fm.getString(MediaFormat.KEY_MIME)
                if (mime != null && mime.startsWith(prefix)) {
                    ex.selectTrack(i)
                    return Src(ex, fm)
                }
            }
        } catch (e: Exception) {
        }
        try {
            ex.release()
        } catch (e: Exception) {
        }
        return null
    }

    fun merge(inputs: List<File>, out: File, onBytes: (Long) -> Unit, cancelled: () -> Boolean): MuxOut {
        var v: Src? = null
        var a: Src? = null
        try {
            for (f in inputs) {
                if (v == null) v = open(f, "video/")
                if (a == null) a = open(f, "audio/")
            }
            if (v == null && a == null) return MuxOut(false, "", false, "medya izi bulunamadı")
            val vm = v?.format?.getString(MediaFormat.KEY_MIME) ?: ""
            val am = a?.format?.getString(MediaFormat.KEY_MIME) ?: ""
            val mp4 = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            val webm = MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
            val webmFirst = vm == "video/x-vnd.on2.vp9" || vm == "video/x-vnd.on2.vp8" ||
                (vm.isEmpty() && (am == "audio/opus" || am == "audio/vorbis"))
            val order = if (webmFirst) intArrayOf(webm, mp4) else intArrayOf(mp4, webm)
            var err = ""
            for (fmt in order) {
                val r = attempt(v, a, out, fmt, onBytes, cancelled)
                if (r == null) {
                    val ext = if (fmt == mp4) (if (v != null) "mp4" else "m4a") else "webm"
                    return MuxOut(true, ext, v != null, "")
                }
                err = r
                if (cancelled()) break
            }
            return MuxOut(false, "", v != null, err)
        } finally {
            try {
                v?.ex?.release()
            } catch (e: Exception) {
            }
            try {
                a?.ex?.release()
            } catch (e: Exception) {
            }
        }
    }

    private fun attempt(
        v: Src?, a: Src?, out: File, fmt: Int,
        onBytes: (Long) -> Unit, cancelled: () -> Boolean
    ): String? {
        var mm: MediaMuxer? = null
        try {
            if (out.exists()) out.delete()
            v?.ex?.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            a?.ex?.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val m = MediaMuxer(out.absolutePath, fmt)
            mm = m
            if (v != null) {
                v.outTrack = m.addTrack(v.format)
                if (fmt == MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4 && v.format.containsKey(MediaFormat.KEY_ROTATION)) {
                    val rot = v.format.getInteger(MediaFormat.KEY_ROTATION)
                    if (rot % 90 == 0) m.setOrientationHint(rot)
                }
            }
            if (a != null) a.outTrack = m.addTrack(a.format)
            m.start()
            val buf = ByteBuffer.allocate(8 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var vEnd = v == null
            var aEnd = a == null
            while (!vEnd || !aEnd) {
                if (cancelled()) throw Exception("İptal")
                var vt = Long.MAX_VALUE
                if (!vEnd) {
                    val t = v!!.ex.sampleTime
                    if (t < 0) vEnd = true else vt = t
                }
                var at = Long.MAX_VALUE
                if (!aEnd) {
                    val t = a!!.ex.sampleTime
                    if (t < 0) aEnd = true else at = t
                }
                if (vEnd && aEnd) break
                val useV = !vEnd && (aEnd || vt <= at)
                val s = if (useV) v!! else a!!
                buf.clear()
                val size = s.ex.readSampleData(buf, 0)
                if (size < 0) {
                    if (useV) vEnd = true else aEnd = true
                    continue
                }
                val flags = if ((s.ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, size, s.ex.sampleTime, flags)
                m.writeSampleData(s.outTrack, buf, info)
                onBytes(size.toLong())
                s.ex.advance()
            }
            m.stop()
            m.release()
            mm = null
            return null
        } catch (e: Exception) {
            try {
                mm?.release()
            } catch (x: Exception) {
            }
            try {
                out.delete()
            } catch (x: Exception) {
            }
            return e.message ?: e.toString()
        }
    }
}

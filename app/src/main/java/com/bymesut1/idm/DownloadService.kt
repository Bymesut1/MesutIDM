package com.bymesut1.idm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager

class DownloadService : Service() {
    companion object {
        @Volatile var running = false
    }

    private var wl: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private fun build(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, "dl")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Mesut IDM")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("dl", "İndirmeler", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, build("İndirme başlıyor…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (!running) {
            running = true
            try {
                val pm = getSystemService(PowerManager::class.java)
                val l = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MesutIDM:dl")
                l.acquire(6 * 60 * 60 * 1000L)
                wl = l
            } catch (e: Exception) {
            }
            Thread {
                while (Dm.active()) {
                    val act = Dm.items.filter {
                        it.state == "İndiriliyor" || it.state == "Bekliyor" || it.state == "Birleştiriliyor" || it.state == "Kaydediliyor"
                    }
                    val pct = if (act.isEmpty()) 0 else act.map {
                        if (it.total > 0) minOf(100L, it.done.get() * 100 / it.total).toInt() else 0
                    }.average().toInt()
                    nm.notify(1, build("${act.size} indirme • %$pct"))
                    Thread.sleep(1000)
                }
                running = false
                try {
                    wl?.release()
                } catch (e: Exception) {
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }.start()
        }
        return START_NOT_STICKY
    }
}

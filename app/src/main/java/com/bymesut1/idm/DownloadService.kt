package com.bymesut1.idm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

class DownloadService : Service() {
    companion object {
        @Volatile var running = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun build(text: String): Notification =
        Notification.Builder(this, "dl")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Mesut IDM")
            .setContentText(text)
            .setOngoing(true)
            .build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("dl", "İndirmeler", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, build("İndirme başlıyor…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (!running) {
            running = true
            Thread {
                while (Dm.active()) {
                    val act = Dm.items.filter { it.state == "İndiriliyor" || it.state == "Bekliyor" }
                    val pct = if (act.isEmpty()) 0 else act.map {
                        if (it.total > 0) (it.done.get() * 100 / it.total).toInt() else 0
                    }.average().toInt()
                    nm.notify(1, build("${act.size} indirme • %$pct"))
                    Thread.sleep(1000)
                }
                running = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }.start()
        }
        return START_NOT_STICKY
    }
}

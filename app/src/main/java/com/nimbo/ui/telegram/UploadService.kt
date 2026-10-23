package com.nimbo.ui.telegram

import com.nimbo.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

// Uploads en 2do plano: prioridad + sobrevive bloqueo/otra app.
// Mismo proceso (TDLib vive aquí): si el sistema mata el proceso,
// el lote muere igual — el servicio compra tiempo, no resume.
class UploadService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startFg(this, getString(R.string.notif_preparing), 0f)
        return START_NOT_STICKY
    }

    companion object {
        private const val CH = "uploads"
        private const val ID = 21

        fun ensure(ctx: Context) {
            runCatching {
                ctx.startForegroundService(Intent(ctx, UploadService::class.java))
            }
        }

        fun progress(ctx: Context, label: String, frac: Float, current: String) {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            nm.notify(ID, build(ctx, label, frac, current))
        }

        fun finish(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, UploadService::class.java)) }
            runCatching {
                ctx.getSystemService(NotificationManager::class.java)?.cancel(ID)
            }
        }

        private fun startFg(svc: Service, label: String, frac: Float) {
            val nm = svc.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26 && nm?.getNotificationChannel(CH) == null) {
                nm?.createNotificationChannel(
                    NotificationChannel(CH, svc.getString(R.string.notif_channel_uploads), NotificationManager.IMPORTANCE_LOW)
                )
            }
            val n = build(svc, label, frac, "")
            if (Build.VERSION.SDK_INT >= 29) {
                svc.startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                svc.startForeground(ID, n)
            }
        }

        private fun build(ctx: Context, label: String, frac: Float, current: String) =
            NotificationCompat.Builder(ctx, CH)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(ctx.getString(R.string.uploading_name, label))
                .setContentText(current.ifEmpty { "${(frac * 100).toInt()}%" })
                .setProgress(100, (frac * 100).toInt().coerceIn(0, 100), false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
    }
}

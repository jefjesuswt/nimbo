package com.nimbo.data.transfer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.nimbo.R

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

        fun progress(ctx: Context, label: BatchLabel, frac: Float, current: String, down: Boolean = false) {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            nm.notify(ID, build(ctx, labelText(ctx, label), frac, current, down))
        }

        fun labelText(ctx: Context, l: BatchLabel): String = when (l) {
            is BatchLabel.Name -> l.text
            is BatchLabel.Files -> ctx.resources.getQuantityString(R.plurals.files_count, l.count, l.count)
            BatchLabel.Root -> ctx.getString(R.string.root)
            is BatchLabel.Encrypting -> ctx.getString(R.string.enc_migrating, l.n, l.total)
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

        private fun build(ctx: Context, label: String, frac: Float, current: String, down: Boolean = false) =
            NotificationCompat.Builder(ctx, CH)
                .setSmallIcon(if (down) android.R.drawable.stat_sys_download else android.R.drawable.stat_sys_upload)
                .setContentTitle(ctx.getString(if (down) R.string.downloading_name else R.string.uploading_name, label))
                .setContentText(current.ifEmpty { "${(frac * 100).toInt()}%" })
                .setProgress(100, (frac * 100).toInt().coerceIn(0, 100), false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
    }
}

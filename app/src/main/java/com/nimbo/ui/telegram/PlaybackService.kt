package com.nimbo.ui.telegram

import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.nimbo.MainActivity

// Notificación + reproducción en fondo. Comparte la instancia TgPlayer
// (mismo proceso): la UI y la sesión mandan sobre el mismo ExoPlayer.
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var notifProvider: DefaultMediaNotificationProvider? = null

    override fun onCreate() {
        super.onCreate()
        TgPlayer.init(this)
        val player = TgPlayer.ensure()
        notifProvider = DefaultMediaNotificationProvider(this)
        setMediaNotificationProvider(notifProvider!!)
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        session = MediaSession.Builder(this, player).setSessionActivity(pi).build()
        addSession(session!!)
        if (player.isPlaying) onUpdateNotification(session!!, true)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        session

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        Log.w("PlaybackSvc", "update fg=$startInForegroundRequired")
        super.onUpdateNotification(session, startInForegroundRequired)
    }

    override fun onDestroy() {
        session?.let { removeSession(it); it.release() }
        session = null
        super.onDestroy()
    }
}

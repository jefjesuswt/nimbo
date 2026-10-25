// Media3 marca la sesión/notificación como UnstableApi: opt-in por archivo.
@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.nimbo.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.nimbo.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

// Notificación + reproducción en fondo. Recibe el mismo PlayerController
// que la UI: la UI y la sesión mandan sobre el mismo ExoPlayer.
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {
    @Inject lateinit var controller: PlayerController
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = controller.ensure()
        setMediaNotificationProvider(DefaultMediaNotificationProvider(this))
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val s = MediaSession.Builder(this, player).setSessionActivity(pi).build()
        session = s
        addSession(s)
        if (player.isPlaying) onUpdateNotification(s, true)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        session?.let { removeSession(it); it.release() }
        session = null
        super.onDestroy()
    }
}

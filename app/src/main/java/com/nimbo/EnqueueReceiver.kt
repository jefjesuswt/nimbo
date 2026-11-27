package com.nimbo

import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.IntentCompat
import com.nimbo.data.auth.AuthRepository
import com.nimbo.data.cloud.safeFolder
import com.nimbo.data.transfer.TransferManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

// Kitto encola subidas sin abrir la UI (spec 16): EXTRA_STREAM = content
// URIs con permiso de lectura concedido, "folder" = ruta en la nube.
// Solo lo pueden mandar apps con el permiso ENQUEUE_UPLOAD (manifest).
@AndroidEntryPoint
class EnqueueReceiver : BroadcastReceiver() {
    @Inject lateinit var transfers: TransferManager
    // Construirlo arranca TDLib si el proceso nació por este broadcast.
    @Inject lateinit var auth: AuthRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val folder = safeFolder(intent.getStringExtra(EXTRA_FOLDER))
        val uris = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            ?.filter { it.scheme == ContentResolver.SCHEME_CONTENT }.orEmpty()
        if (folder == null || uris.isEmpty()) {
            Log.w(TAG, "ENQUEUE-RECHAZADO")
            return
        }
        transfers.enqueueWhenReady(uris, folder, background = true)
    }

    companion object {
        const val ACTION = "com.nimbo.action.ENQUEUE_UPLOAD"
        const val EXTRA_FOLDER = "folder"
        private const val TAG = "Enqueue"
    }
}

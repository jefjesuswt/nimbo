package com.nimbo

import android.app.Application
import com.nimbo.data.transfer.MediaCache
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

// Raíz del grafo Hilt (ADR 0007).
@HiltAndroidApp
class NimboApplication : Application() {
    // Desde el arranque: limpia la caché al entrar según Ajustes.
    @Inject lateinit var mediaCache: MediaCache
}

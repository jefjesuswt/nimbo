package com.nimbo

import android.app.Application
import com.nimbo.data.storage.StorageCleaner
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

// Raíz del grafo Hilt (ADR 0007).
@HiltAndroidApp
class NimboApplication : Application() {
    @Inject lateinit var storage: StorageCleaner

    override fun onCreate() {
        super.onCreate()
        // Sin esto los archivos de TDLib crecían sin tope (10 GB en 1.3.1).
        storage.start()
    }
}

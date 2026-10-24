package com.nimbo

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

// Raíz del grafo Hilt (ADR 0007).
@HiltAndroidApp
class NimboApplication : Application()

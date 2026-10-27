package com.nimbo.core.util

import java.io.File

// Caché por tamaño: borra los más viejos hasta quedar bajo cap.
fun trimCache(dir: File, cap: Long) {
    val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
    var total = files.sumOf { it.length() }
    for (f in files) {
        if (total <= cap) break
        total -= f.length()
        f.delete()
    }
}

package com.nimbo.core.util

import java.io.File

// Caché por tamaño: borra los más viejos hasta quedar bajo cap. El más
// nuevo se queda aunque solo pase el tope (es el que se va a abrir).
fun trimCache(dir: File, cap: Long) {
    val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
    var total = files.sumOf { it.length() }
    for (f in files.dropLast(1)) {
        if (total <= cap) break
        total -= f.length()
        f.delete()
    }
}

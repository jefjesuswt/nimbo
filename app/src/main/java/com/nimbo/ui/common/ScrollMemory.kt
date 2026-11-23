package com.nimbo.ui.common

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow

// Dónde iba cada pantalla de una lista (carpeta, álbum…), por clave. Vive en
// el ViewModel: sobrevive a que la lista salga de composición (carpeta
// vacía, cambio lista/grid, abrir un álbum).
class ScrollMemory {
    private val pos = HashMap<String, Pair<Int, Int>>()

    operator fun get(key: String): Pair<Int, Int> = pos[key] ?: (0 to 0)

    fun save(key: String, index: Int, offset: Int) {
        pos[key] = index to offset
    }

    // Entrar de nuevo (no volver): empieza arriba.
    fun forget(key: String) {
        pos.remove(key)
    }
}

// true si dir es el padre (o abuelo…) de from: volver por la miga o atrás.
fun isAncestor(dir: String, from: String): Boolean =
    dir != from && (dir.isEmpty() || from.startsWith("$dir/"))

// Al cambiar key salta a lo guardado para ella y va apuntando dónde está.
// keep = false (búsqueda): arriba en cada consulta, sin guardar.
@Composable
fun KeepScroll(key: String, keep: Boolean, memory: ScrollMemory, state: LazyListState) {
    LaunchedEffect(key) {
        val (i, o) = if (keep) memory[key] else 0 to 0
        state.scrollToItem(i, o)
        if (keep) snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .collect { (idx, off) -> memory.save(key, idx, off) }
    }
}

@Composable
fun KeepScroll(key: String, keep: Boolean, memory: ScrollMemory, state: LazyGridState) {
    LaunchedEffect(key) {
        val (i, o) = if (keep) memory[key] else 0 to 0
        state.scrollToItem(i, o)
        if (keep) snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .collect { (idx, off) -> memory.save(key, idx, off) }
    }
}

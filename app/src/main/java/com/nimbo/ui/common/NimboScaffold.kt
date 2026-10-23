package com.nimbo.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// Scaffold único de la app: topbar fijo + snackbar arriba + FABs fijos.
// Cambias aquí, cambia en las 6 pantallas. Las pantallas solo pasan
// título, acciones, FAB y contenido — cero Scaffold/TopBar repetidos.
@Composable
fun NimboScaffold(
    title: String,
    // Reemplaza el título (p. ej. campo de búsqueda a lo ancho del topbar).
    titleContent: (@Composable () -> Unit)? = null,
    snack: SnackbarHostState? = null,
    navigationIcon: @Composable (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            NimboTopBar(
                title = title,
                titleContent = titleContent,
                navigationIcon = navigationIcon,
                actions = actions
            )
        },
        // Insets ya consumidos por el contenedor: el FAB queda a 16dp.
        floatingActionButton = { floatingActionButton?.invoke() }
    ) { pad ->
        if (snack != null) {
            TopSnackBox(snack, Modifier.padding(pad)) { content() }
        } else {
            Box(Modifier.padding(pad).fillMaxSize()) { content() }
        }
    }
}

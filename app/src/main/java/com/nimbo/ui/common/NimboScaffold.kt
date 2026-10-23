package com.nimbo.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// Scaffold único de la app: topbar fijo + snackbar abajo + FABs con fade.
// Cambias aquí, cambia en las 6 pantallas. Las pantallas solo pasan
// título, acciones, FAB y contenido — cero Scaffold/TopBar repetidos.
@Composable
fun NimboScaffold(
    title: String,
    snack: SnackbarHostState? = null,
    navigationIcon: @Composable (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val fabOn = snack?.let { fabVisible(it) } ?: true
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            NimboTopBar(
                title = title,
                navigationIcon = navigationIcon,
                actions = actions
            )
        },
        floatingActionButton = {
            if (floatingActionButton != null) {
                // Scaffold suma FabSpacing + inset inferior, pero ese inset ya viene
                // descontado por el pad del padre → se baja lo mismo para quedar a 16dp.
                // Ojo: offset Y positivo = hacia abajo.
                val sysBottom = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()
                AnimatedVisibility(
                    visible = fabOn,
                    enter = fadeIn() + scaleIn(),
                    exit = fadeOut() + scaleOut()
                ) {
                    Box(Modifier.offset(y = sysBottom)) { floatingActionButton() }
                }
            }
        }
    ) { pad ->
        if (snack != null) {
            BottomSnackBox(snack, Modifier.withoutBottomBarInset(pad)) { content() }
        } else {
            Box(Modifier.padding(pad).fillMaxSize()) { content() }
        }
    }
}

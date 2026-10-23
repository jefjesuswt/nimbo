package com.nimbo.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp

// Snackbar anclado abajo (prototipo). Los FABs se ocultan con fade mientras
// dura — nada se desplaza. Uso:
//   val fabOn = fabVisible(snack)
//   Scaffold(floatingActionButton = {
//     androidx.compose.animation.AnimatedVisibility(
//       visible = fabOn,
//       enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()
//     ) { ...FABs... }
//   }) { BottomSnackBox(snack) { ... } }
@Composable
fun fabVisible(snack: SnackbarHostState): Boolean =
    snack.currentSnackbarData == null

@Composable
fun BottomSnackBox(
    snack: SnackbarHostState,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier.fillMaxSize()) {
        content()
        SnackbarHost(
            snack,
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp)
        )
    }
}

// Padding del scaffold SIN el inset inferior: el snackbar queda a 12dp del
// navbar en vez de sumar inset del sistema + 16dp.
@Composable
fun Modifier.withoutBottomBarInset(pad: PaddingValues): Modifier {
    val dir = LocalLayoutDirection.current
    return padding(
        top = pad.calculateTopPadding(),
        start = pad.calculateStartPadding(dir),
        end = pad.calculateEndPadding(dir)
    )
}

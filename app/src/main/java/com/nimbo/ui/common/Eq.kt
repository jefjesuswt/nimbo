package com.nimbo.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Ecualizador: barras animadas solo mientras suena; quieto en pausa.
// Común: minibar global + badges de lista.
@Composable
fun EqBars(playing: Boolean, tint: Color, modifier: Modifier = Modifier) {
    if (!playing) {
        Row(modifier.height(18.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
            listOf(0.5f, 0.9f, 0.6f, 0.8f).forEach { h ->
                Box(
                    Modifier.width(4.dp).height((18 * h).dp)
                        .clip(MaterialTheme.shapes.extraSmall).background(tint.copy(alpha = 0.5f))
                )
            }
        }
        return
    }
    val inf = rememberInfiniteTransition(label = "eq")
    Row(modifier.height(18.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
        listOf(0, 1, 2, 3).forEach { i ->
            val h by inf.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(380 + i * 140, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "bar$i"
            )
            Box(
                Modifier.width(4.dp).height((18 * h).dp)
                    .clip(MaterialTheme.shapes.extraSmall).background(tint)
            )
        }
    }
}

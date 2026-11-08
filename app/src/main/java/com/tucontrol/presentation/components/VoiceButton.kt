package com.tucontrol.presentation.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import com.tucontrol.presentation.theme.Gradients
import com.tucontrol.presentation.theme.Palette

enum class VoiceButtonState { IDLE, LISTENING, THINKING }

/**
 * The hero control: a gradient microphone orb. A soft halo breathes while listening and
 * a progress ring spins while the phrase is being interpreted.
 */
@Composable
fun VoiceButton(
    state: VoiceButtonState,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 76.dp,
) {
    val transition = rememberInfiniteTransition(label = "voice")
    val halo by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_400, easing = LinearEasing), RepeatMode.Restart),
        label = "halo",
    )
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(4_000, easing = LinearEasing)),
        label = "sweep",
    )

    Box(modifier.size(size + 28.dp), contentAlignment = Alignment.Center) {
        if (state == VoiceButtonState.LISTENING) {
            Canvas(Modifier.fillMaxSize()) {
                val base = size.toPx() / 2
                val radius = base + (this.size.minDimension / 2 - base) * halo
                drawCircle(
                    color = Palette.Teal.copy(alpha = 0.45f * (1f - halo)),
                    radius = radius,
                    style = Stroke(width = 3.dp.toPx()),
                )
            }
        }
        if (state == VoiceButtonState.THINKING) {
            CircularProgressIndicator(
                modifier = Modifier.size(size + 16.dp),
                strokeWidth = 4.dp,
            )
        }
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClickLabel = contentDescription, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            // Outer rim: a sweep gradient that rotates while the assistant is active.
            Box(
                Modifier
                    .fillMaxSize()
                    .rotate(if (state == VoiceButtonState.IDLE) 0f else sweep)
                    .background(
                        Brush.sweepGradient(
                            listOf(Palette.Periwinkle, Palette.Teal, Palette.Periwinkle),
                            center = Offset.Unspecified,
                        ),
                    ),
            )
            Box(
                Modifier
                    .size(size - 6.dp)
                    .clip(CircleShape)
                    .background(Gradients.Aurora),
            )
            Icon(
                imageVector = Icons.Rounded.Mic,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(size * 0.42f),
            )
        }
    }
}

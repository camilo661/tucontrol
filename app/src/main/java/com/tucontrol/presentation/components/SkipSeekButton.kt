package com.tucontrol.presentation.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A media key with two jobs, like the skip keys of many remotes: a tap runs [onTap] (next or
 * previous track) and holding it runs [onHoldStep] repeatedly (fast-forward or rewind) until
 * the finger lifts. The icon switches to [holdIcon] while held so the user sees which one runs.
 */
@Composable
fun SkipSeekButton(
    tapIcon: ImageVector,
    holdIcon: ImageVector,
    contentDescription: String,
    holdDescription: String,
    onTap: () -> Unit,
    onHoldStep: () -> Unit,
    size: Dp = 48.dp,
) {
    val tap by rememberUpdatedState(onTap)
    val step by rememberUpdatedState(onHoldStep)
    val scope = rememberCoroutineScope()
    val longPressMs = LocalViewConfiguration.current.longPressTimeoutMillis
    var pressed by remember { mutableStateOf(false) }
    var holding by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, label = "press")

    Box(
        modifier = Modifier
            .size(size)
            .scale(scale)
            .clip(CircleShape)
            .background(
                if (holding) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceContainer,
            )
            .semantics {
                role = Role.Button
                this.contentDescription = contentDescription
                onClick { tap(); true }
                onLongClick(label = holdDescription) { step(); true }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        // Held past the long-press time: keep seeking until released.
                        val seeking = scope.launch {
                            delay(longPressMs)
                            holding = true
                            while (true) {
                                step()
                                delay(HOLD_REPEAT_MS)
                            }
                        }
                        tryAwaitRelease()
                        seeking.cancel()
                        pressed = false
                        if (!holding) tap()
                        holding = false
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (holding) holdIcon else tapIcon,
            contentDescription = null,
            tint = if (holding) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

/** How often fast-forward/rewind is sent while the key is held. */
private const val HOLD_REPEAT_MS = 450L

package com.tucontrol.presentation.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.tucontrol.domain.model.ConnectionState
import com.tucontrol.presentation.theme.StatusColors

/** Small colored dot reflecting the TV link; pulses while a connection is in progress. */
@Composable
fun StatusDot(state: ConnectionState, modifier: Modifier = Modifier) {
    val target = when (state) {
        ConnectionState.Connected -> StatusColors.Connected
        ConnectionState.Connecting, ConnectionState.AwaitingApproval, ConnectionState.AwaitingPin -> StatusColors.Pending
        is ConnectionState.Failed -> StatusColors.Error
        ConnectionState.Disconnected -> StatusColors.Offline
    }
    val color by animateColorAsState(target, label = "statusColor")
    val pending = state is ConnectionState.Connecting || state is ConnectionState.AwaitingApproval
    val pulse by rememberInfiniteTransition(label = "statusPulse").animateFloat(
        initialValue = 1f,
        targetValue = if (pending) 0.3f else 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "statusAlpha",
    )
    Box(
        modifier
            .size(8.dp)
            .alpha(pulse)
            .background(color, CircleShape),
    )
}

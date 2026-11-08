package com.tucontrol.presentation.components

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme

enum class RemoteButtonStyle { PRIMARY, TONAL, POWER }

/** Round remote key with consistent sizing and emphasis levels. */
@Composable
fun RemoteButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: RemoteButtonStyle = RemoteButtonStyle.TONAL,
    size: Dp = 48.dp,
) {
    val content: @Composable () -> Unit = {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(size * 0.5f))
    }
    when (style) {
        RemoteButtonStyle.PRIMARY -> FilledIconButton(
            onClick = onClick,
            modifier = modifier.size(size),
        ) { content() }
        RemoteButtonStyle.POWER -> FilledIconButton(
            onClick = onClick,
            modifier = modifier.size(size),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            ),
        ) { content() }
        RemoteButtonStyle.TONAL -> FilledTonalIconButton(
            onClick = onClick,
            modifier = modifier.size(size),
        ) { content() }
    }
}

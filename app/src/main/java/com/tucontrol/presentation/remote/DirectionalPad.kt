package com.tucontrol.presentation.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.tucontrol.R
import com.tucontrol.domain.model.RemoteKey

/** Circular D-pad sized for round screens, with a prominent OK key in the center. */
@Composable
fun DirectionalPad(
    onKey: (RemoteKey) -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 150.dp,
) {
    Box(
        modifier = modifier
            .size(diameter)
            .background(MaterialTheme.colorScheme.surfaceContainer, CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Arrow(Icons.Rounded.KeyboardArrowUp, R.string.key_up, Alignment.TopCenter) { onKey(RemoteKey.UP) }
        Arrow(Icons.Rounded.KeyboardArrowDown, R.string.key_down, Alignment.BottomCenter) { onKey(RemoteKey.DOWN) }
        Arrow(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, R.string.key_left, Alignment.CenterStart) { onKey(RemoteKey.LEFT) }
        Arrow(Icons.AutoMirrored.Rounded.KeyboardArrowRight, R.string.key_right, Alignment.CenterEnd) { onKey(RemoteKey.RIGHT) }

        FilledIconButton(
            onClick = { onKey(RemoteKey.OK) },
            modifier = Modifier.size(diameter * 0.38f),
        ) {
            Text(
                text = stringResource(R.string.key_ok_short),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun BoxScope.Arrow(
    icon: ImageVector,
    labelRes: Int,
    alignment: Alignment,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.align(alignment).size(48.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(labelRes),
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(30.dp),
        )
    }
}

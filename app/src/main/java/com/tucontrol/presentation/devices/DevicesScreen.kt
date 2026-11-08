package com.tucontrol.presentation.devices

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WifiFind
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.tucontrol.R
import com.tucontrol.domain.model.ConnectionState
import com.tucontrol.domain.model.TvDevice
import com.tucontrol.presentation.components.ActionText
import com.tucontrol.presentation.components.StatusDot
import com.tucontrol.presentation.components.icon

@Composable
fun DevicesScreen(
    onDeviceSelected: () -> Unit,
    viewModel: DevicesViewModel = viewModel(factory = DevicesViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberTransformingLazyColumnState()
    var pendingForget by remember { mutableStateOf<TvDevice?>(null) }

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item { ListHeader { Text(stringResource(R.string.devices_title)) } }

            if (state.saved.isNotEmpty()) {
                item { SectionLabel(stringResource(R.string.devices_saved)) }
                state.saved.forEach { device ->
                    item(key = "saved:${device.id}") {
                        DeviceButton(
                            device = device,
                            connection = state.activeConnection.takeIf { device.id == state.activeId },
                            onClick = {
                                viewModel.select(device)
                                onDeviceSelected()
                            },
                            onLongClick = { pendingForget = device },
                        )
                    }
                }
            }

            if (state.found.isNotEmpty()) {
                item { SectionLabel(stringResource(R.string.devices_found)) }
                state.found.forEach { device ->
                    item(key = "found:${device.host}") {
                        DeviceButton(
                            device = device,
                            connection = null,
                            onClick = {
                                viewModel.select(device)
                                onDeviceSelected()
                            },
                        )
                    }
                }
            }

            when {
                state.scanning -> item { ScanningIndicator() }
                state.wifiUnavailable -> item {
                    EmptyState(Icons.Rounded.WifiOff, R.string.error_wifi, R.string.devices_empty_hint)
                }
                state.found.isEmpty() && state.saved.isEmpty() -> item {
                    EmptyState(Icons.Rounded.WifiFind, R.string.devices_empty, R.string.devices_empty_hint)
                }
            }

            if (!state.scanning) {
                item {
                    Button(
                        onClick = viewModel::scan,
                        modifier = Modifier.fillMaxWidth(),
                        icon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                        label = { Text(stringResource(R.string.devices_rescan)) },
                    )
                }
            }
        }
    }

    AlertDialog(
        visible = pendingForget != null,
        onDismissRequest = { pendingForget = null },
        title = { Text(pendingForget?.name.orEmpty(), maxLines = 2, textAlign = TextAlign.Center) },
        text = { Text(stringResource(R.string.devices_forget_question)) },
        confirmButton = {
            AlertDialogDefaults.ConfirmButton(onClick = {
                pendingForget?.let(viewModel::forget)
                pendingForget = null
            })
        },
        dismissButton = {
            AlertDialogDefaults.DismissButton(onClick = { pendingForget = null })
        },
    )
}

@Composable
private fun DeviceButton(
    device: TvDevice,
    connection: ConnectionState?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val supported = device.brand.isSupported
    Button(
        onClick = onClick,
        onLongClick = onLongClick,
        onLongClickLabel = onLongClick?.let { stringResource(R.string.devices_forget) },
        enabled = supported,
        modifier = Modifier.fillMaxWidth(),
        colors = if (connection == ConnectionState.Connected) ButtonDefaults.filledVariantButtonColors()
        else ButtonDefaults.filledTonalButtonColors(),
        icon = { Icon(device.brand.icon(), contentDescription = null) },
        label = { Text(device.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        secondaryLabel = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (connection != null) StatusDot(connection)
                Text(
                    text = when {
                        !supported -> stringResource(R.string.error_unsupported)
                        connection != null -> stringResource(ActionText.status(connection))
                        else -> device.brand.displayName
                    },
                    maxLines = 1,
                )
            }
        },
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp),
    )
}

@Composable
private fun ScanningIndicator() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(
            text = stringResource(R.string.devices_searching),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun EmptyState(icon: ImageVector, @StringRes title: Int, @StringRes hint: Int) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp),
        )
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            text = stringResource(hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

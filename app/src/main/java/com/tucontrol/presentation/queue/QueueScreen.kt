package com.tucontrol.presentation.queue

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.tucontrol.R

/** The play queue of the app on the TV; tapping an entry plays it. */
@Composable
fun QueueScreen(
    onPlayed: () -> Unit,
    viewModel: QueueViewModel = viewModel(factory = QueueViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberTransformingLazyColumnState()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item { ListHeader { Text(stringResource(R.string.queue_title)) } }
            when (val current = state) {
                QueueUiState.Loading -> item { CircularProgressIndicator(Modifier.size(32.dp)) }
                is QueueUiState.Ready -> current.items.forEach { entry ->
                    item(key = entry.id) {
                        Button(
                            onClick = { viewModel.play(entry, onPlayed) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = if (entry.isPlaying) ButtonDefaults.filledTonalButtonColors() else ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                            icon = if (entry.isPlaying) {
                                { Icon(Icons.Rounded.Equalizer, contentDescription = stringResource(R.string.queue_playing)) }
                            } else {
                                null
                            },
                            label = { Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            secondaryLabel = entry.subtitle.takeIf { it.isNotBlank() }?.let {
                                { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            },
                        )
                    }
                }
                QueueUiState.Empty -> item { Message(R.string.queue_empty) }
                QueueUiState.Unavailable -> item { Message(R.string.queue_unavailable) }
            }
            if (state != QueueUiState.Loading) {
                item {
                    Button(
                        onClick = viewModel::refresh,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(),
                        border = ButtonDefaults.outlinedButtonBorder(enabled = true),
                        icon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                        label = { Text(stringResource(R.string.queue_refresh)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Message(text: Int) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

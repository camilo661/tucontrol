package com.tucontrol.presentation.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import com.tucontrol.BuildConfig
import com.tucontrol.R
import com.tucontrol.presentation.common.rememberTextInputLauncher
import com.tucontrol.presentation.welcome.AuthorCredit

@Composable
fun SettingsScreen(
    onOpenGuide: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberTransformingLazyColumnState()
    val editSonyKey = rememberTextInputLauncher(
        label = stringResource(R.string.settings_sony_psk),
        onResult = viewModel::setSonyPreSharedKey,
    )

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item { ListHeader { Text(stringResource(R.string.settings_title)) } }
            item {
                SwitchButton(
                    checked = state.aiConfigured && state.settings.aiEnabled,
                    onCheckedChange = viewModel::setAiEnabled,
                    enabled = state.aiConfigured,
                    modifier = Modifier.fillMaxWidth(),
                    icon = { Icon(Icons.Rounded.AutoAwesome, contentDescription = null) },
                    label = { Text(stringResource(R.string.settings_ai)) },
                    secondaryLabel = {
                        Text(
                            stringResource(
                                when {
                                    !state.aiConfigured -> R.string.settings_ai_missing_key
                                    state.settings.aiEnabled -> R.string.settings_ai_on
                                    else -> R.string.settings_ai_off
                                },
                            ),
                        )
                    },
                )
            }
            item {
                SwitchButton(
                    checked = state.settings.hapticsEnabled,
                    onCheckedChange = viewModel::setHapticsEnabled,
                    modifier = Modifier.fillMaxWidth(),
                    icon = { Icon(Icons.Rounded.Vibration, contentDescription = null) },
                    label = { Text(stringResource(R.string.settings_haptics)) },
                )
            }
            item {
                Button(
                    onClick = editSonyKey,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    icon = { Icon(Icons.Rounded.Key, contentDescription = null) },
                    label = { Text(stringResource(R.string.settings_sony_psk)) },
                    secondaryLabel = { Text(state.settings.sonyPreSharedKey) },
                )
            }
            item {
                Button(
                    onClick = onOpenGuide,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    icon = { Icon(Icons.AutoMirrored.Rounded.HelpOutline, contentDescription = null) },
                    label = { Text(stringResource(R.string.settings_guide)) },
                )
            }
            item { AuthorCredit(Modifier.padding(top = 8.dp)) }
            item {
                Text(
                    text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
    }
}

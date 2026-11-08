package com.tucontrol.presentation.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tv
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.tucontrol.R
import com.tucontrol.domain.model.ConnectionState
import com.tucontrol.domain.model.RemoteKey
import com.tucontrol.presentation.common.rememberHaptics
import com.tucontrol.presentation.common.rememberTextInputLauncher
import com.tucontrol.presentation.components.ActionText
import com.tucontrol.presentation.components.RemoteButton
import com.tucontrol.presentation.components.RemoteButtonStyle
import com.tucontrol.presentation.components.StatusDot
import com.tucontrol.presentation.components.VoiceButton
import com.tucontrol.presentation.components.VoiceButtonState

@Composable
fun HomeScreen(
    onOpenRemote: () -> Unit,
    onOpenDevices: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val haptics = rememberHaptics(state.hapticsEnabled)
    val listState = rememberTransformingLazyColumnState()
    val context = LocalContext.current
    // Without microphone access the user can still type the command.
    val typeCommand = rememberTextInputLauncher(
        label = stringResource(R.string.home_voice_prompt),
        onResult = viewModel::onTypedCommand,
    )
    val requestMicrophone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            viewModel.toggleListening()
        } else {
            viewModel.onMicrophoneDenied()
            typeCommand()
        }
    }
    val startVoice = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.toggleListening() else requestMicrophone.launch(Manifest.permission.RECORD_AUDIO)
    }
    // Release the microphone when the user leaves the screen.
    DisposableEffect(Unit) { onDispose { viewModel.cancelListening() } }

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item {
                DeviceHeader(
                    name = state.device?.name,
                    connection = state.connection,
                    onClick = onOpenDevices,
                )
            }
            item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    VoiceButton(
                        state = when (state.voice) {
                            is VoiceState.Listening -> VoiceButtonState.LISTENING
                            is VoiceState.Thinking, is VoiceState.Preparing, is VoiceState.Running -> VoiceButtonState.THINKING
                            else -> VoiceButtonState.IDLE
                        },
                        contentDescription = stringResource(R.string.key_mic),
                        onClick = {
                            haptics.tick()
                            if (state.device == null) {
                                onOpenDevices()
                            } else {
                                startVoice()
                            }
                        },
                    )
                }
            }
            item { VoiceStatus(state) }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    RemoteButton(
                        icon = Icons.Rounded.PowerSettingsNew,
                        contentDescription = stringResource(R.string.key_power),
                        style = RemoteButtonStyle.POWER,
                        onClick = { haptics.tick(); viewModel.press(RemoteKey.POWER) },
                    )
                    Spacer(Modifier.width(12.dp))
                    RemoteButton(
                        icon = Icons.AutoMirrored.Rounded.VolumeOff,
                        contentDescription = stringResource(R.string.key_mute),
                        onClick = { haptics.tick(); viewModel.press(RemoteKey.MUTE) },
                    )
                }
            }
            item {
                NavigationButton(Icons.Rounded.Gamepad, stringResource(R.string.home_remote), onOpenRemote)
            }
            item {
                NavigationButton(Icons.Rounded.Tv, stringResource(R.string.home_devices), onOpenDevices)
            }
            item {
                NavigationButton(Icons.Rounded.Settings, stringResource(R.string.home_settings), onOpenSettings)
            }
        }
    }
}

@Composable
private fun DeviceHeader(name: String?, connection: ConnectionState, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.filledTonalButtonColors(),
        icon = { StatusDot(connection) },
        label = {
            Text(
                text = name ?: stringResource(R.string.home_choose_tv),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        secondaryLabel = {
            Text(
                text = if (name == null) stringResource(R.string.home_no_tv)
                else stringResource(ActionText.status(connection)),
                maxLines = 1,
            )
        },
    )
}

@Composable
private fun VoiceStatus(state: HomeUiState) {
    val resources = LocalResources.current
    val message = when (val voice = state.voice) {
        VoiceState.Idle -> stringResource(
            if (state.device == null) R.string.home_link_tv_hint else R.string.home_tap_to_speak,
        )
        is VoiceState.Preparing -> voice.progress
            ?.let { stringResource(R.string.home_preparing_voice, (it * 100).toInt()) }
            ?: stringResource(R.string.home_loading_voice)
        // Show the words as they are recognized, so the user knows they are being heard.
        is VoiceState.Listening -> voice.heard.ifEmpty { stringResource(R.string.home_listening) }
        is VoiceState.Thinking -> "“${voice.transcript}”"
        is VoiceState.Running -> stringResource(
            R.string.home_running_step,
            voice.step + 1,
            voice.plan.actions.size,
            ActionText.describeStep(resources, voice.plan, voice.step),
        )
        is VoiceState.Executed -> ActionText.describe(resources, voice.plan)
        is VoiceState.NotUnderstood -> stringResource(R.string.home_not_understood, voice.transcript)
        is VoiceState.AppUnavailable -> stringResource(R.string.home_app_unavailable, voice.app.displayName)
        VoiceState.NotHeard -> stringResource(R.string.home_not_heard)
        VoiceState.SendFailed -> stringResource(R.string.action_failed)
        VoiceState.MicDenied -> stringResource(R.string.home_mic_denied)
        VoiceState.ModelUnavailable -> stringResource(R.string.home_voice_download_failed)
        VoiceState.Unavailable -> stringResource(R.string.home_voice_unavailable)
    }
    val color = when (state.voice) {
        is VoiceState.Executed -> MaterialTheme.colorScheme.secondary
        is VoiceState.Listening, is VoiceState.Thinking, is VoiceState.Running -> MaterialTheme.colorScheme.onSurface
        VoiceState.Idle, is VoiceState.Preparing -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.error
    }
    AnimatedContent(
        targetState = message,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "voiceStatus",
        modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp),
    ) { text ->
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        )
    }
}

@Composable
private fun NavigationButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.filledTonalButtonColors(),
        icon = { Icon(icon, contentDescription = null) },
        label = { Text(label) },
    )
}

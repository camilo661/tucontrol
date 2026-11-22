package com.tucontrol.presentation.remote

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Input
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material3.HorizontalPageIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.tucontrol.R
import com.tucontrol.domain.model.RemoteKey
import com.tucontrol.presentation.common.rememberHaptics
import com.tucontrol.presentation.components.RemoteButton
import com.tucontrol.presentation.components.RemoteButtonStyle
import com.tucontrol.presentation.components.SkipSeekButton
import kotlinx.coroutines.delay
import kotlin.math.abs

private const val PAGE_COUNT = 3
private const val ROTARY_STEP_PX = 48f

/**
 * Three swipeable pages: navigation (D-pad), volume & channel, and media playback.
 * Turning the crown/bezel changes the volume from any page.
 */
@Composable
fun RemoteScreen(
    onExit: () -> Unit,
    onOpenQueue: () -> Unit,
    viewModel: RemoteViewModel = viewModel(factory = RemoteViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val haptics = rememberHaptics(state.hapticsEnabled)
    val pagerState = rememberPagerState(pageCount = { PAGE_COUNT })
    val focusRequester = remember { FocusRequester() }
    var rotaryAccumulator by remember { mutableFloatStateOf(0f) }
    var volumeHint by remember { mutableStateOf<RemoteKey?>(null) }

    val press: (RemoteKey) -> Unit = { key ->
        haptics.tick()
        viewModel.press(key)
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    LaunchedEffect(volumeHint) {
        if (volumeHint != null) {
            delay(900)
            volumeHint = null
        }
    }

    ScreenScaffold {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onRotaryScrollEvent { event ->
                    rotaryAccumulator += event.verticalScrollPixels
                    if (abs(rotaryAccumulator) >= ROTARY_STEP_PX) {
                        val key = if (rotaryAccumulator > 0) RemoteKey.VOLUME_UP else RemoteKey.VOLUME_DOWN
                        rotaryAccumulator = 0f
                        volumeHint = key
                        press(key)
                    }
                    true
                }
                .focusRequester(focusRequester)
                .focusable(),
        ) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                when (page) {
                    0 -> NavigationPage(press, onExit)
                    1 -> VolumeChannelPage(press)
                    else -> MediaPage(press, onOpenQueue, onExit)
                }
            }
            HorizontalPageIndicator(
                pagerState = pagerState,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
            )
            AnimatedVisibility(
                visible = volumeHint != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 28.dp),
            ) {
                VolumeToast(volumeHint == RemoteKey.VOLUME_UP)
            }
        }
    }
}

@Composable
private fun NavigationPage(press: (RemoteKey) -> Unit, onExit: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        DirectionalPad(onKey = press)
        Row(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(56.dp),
        ) {
            RemoteButton(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.key_back),
                onClick = { press(RemoteKey.BACK) },
                onLongClick = onExit, // Hold ← to leave the remote.
                size = 36.dp,
            )
            RemoteButton(
                icon = Icons.Rounded.Home,
                contentDescription = stringResource(R.string.key_home),
                onClick = { press(RemoteKey.HOME) },
                size = 36.dp,
            )
        }
    }
}

@Composable
private fun VolumeChannelPage(press: (RemoteKey) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            RockerControl(
                label = stringResource(R.string.remote_volume_short),
                upDescription = stringResource(R.string.key_volume_up),
                downDescription = stringResource(R.string.key_volume_down),
                onUp = { press(RemoteKey.VOLUME_UP) },
                onDown = { press(RemoteKey.VOLUME_DOWN) },
            )
            RemoteButton(
                icon = Icons.AutoMirrored.Rounded.VolumeOff,
                contentDescription = stringResource(R.string.key_mute),
                onClick = { press(RemoteKey.MUTE) },
                size = 40.dp,
            )
            RockerControl(
                label = stringResource(R.string.remote_channel_short),
                upDescription = stringResource(R.string.key_channel_up),
                downDescription = stringResource(R.string.key_channel_down),
                onUp = { press(RemoteKey.CHANNEL_UP) },
                onDown = { press(RemoteKey.CHANNEL_DOWN) },
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.remote_crown_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
}

/** Vertical pill with +/− keys, like the volume rocker of a physical remote. */
@Composable
private fun RockerControl(
    label: String,
    upDescription: String,
    downDescription: String,
    onUp: () -> Unit,
    onDown: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(52.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(26.dp))
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RemoteButton(Icons.Rounded.Add, upDescription, onUp, style = RemoteButtonStyle.PRIMARY, size = 44.dp)
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        RemoteButton(Icons.Rounded.Remove, downDescription, onDown, size = 44.dp)
    }
}

@Composable
private fun MediaPage(press: (RemoteKey) -> Unit, onOpenQueue: () -> Unit, onExit: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.remote_media),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            SkipSeekButton(
                tapIcon = Icons.Rounded.SkipPrevious,
                holdIcon = Icons.Rounded.FastRewind,
                contentDescription = stringResource(R.string.key_previous_hold_rewind),
                holdDescription = stringResource(R.string.key_rewind),
                onTap = { press(RemoteKey.PREVIOUS) },
                onHoldStep = { press(RemoteKey.REWIND) },
            )
            RemoteButton(
                icon = Icons.Rounded.PlayArrow,
                contentDescription = stringResource(R.string.key_play_pause),
                onClick = { press(RemoteKey.PLAY_PAUSE) },
                style = RemoteButtonStyle.PRIMARY,
                size = 60.dp,
            )
            SkipSeekButton(
                tapIcon = Icons.Rounded.SkipNext,
                holdIcon = Icons.Rounded.FastForward,
                contentDescription = stringResource(R.string.key_next_hold_forward),
                holdDescription = stringResource(R.string.key_fast_forward),
                onTap = { press(RemoteKey.NEXT) },
                onHoldStep = { press(RemoteKey.FAST_FORWARD) },
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RemoteButton(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.key_back),
                onClick = { press(RemoteKey.BACK) },
                onLongClick = onExit, // Hold ← to go back to the voice screen.
                size = 40.dp,
            )
            RemoteButton(Icons.AutoMirrored.Rounded.QueueMusic, stringResource(R.string.queue_title), onOpenQueue, size = 40.dp)
            RemoteButton(Icons.Rounded.ClosedCaption, stringResource(R.string.key_captions), { press(RemoteKey.CAPTIONS) }, size = 40.dp)
        }
    }
}

@Composable
private fun VolumeToast(up: Boolean) {
    Row(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (up) Icons.Rounded.Add else Icons.Rounded.Remove,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = stringResource(R.string.remote_volume),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

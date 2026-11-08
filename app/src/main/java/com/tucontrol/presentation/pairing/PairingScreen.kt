package com.tucontrol.presentation.pairing

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Pin
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.tucontrol.R
import com.tucontrol.domain.model.ConnectionState
import com.tucontrol.presentation.common.rememberTextInputLauncher
import com.tucontrol.presentation.components.ActionText
import com.tucontrol.presentation.theme.StatusColors

/** Guides the user through linking a TV: TV-side approval or a PIN, depending on the brand. */
@Composable
fun PairingScreen(
    onFinished: () -> Unit,
    viewModel: PairingViewModel = viewModel(factory = PairingViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val enterPin = rememberTextInputLauncher(
        label = stringResource(R.string.pairing_enter_pin),
        onResult = viewModel::submitPin,
    )

    ScreenScaffold {
        AnimatedContent(
            targetState = state.connection,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            contentKey = { it::class },
            label = "pairing",
        ) { connection ->
            when (connection) {
                ConnectionState.Connecting, ConnectionState.Disconnected -> PairingStep(
                    title = stringResource(R.string.pairing_connecting, state.deviceName),
                ) { CircularProgressIndicator(modifier = Modifier.size(36.dp)) }

                ConnectionState.AwaitingApproval -> PairingStep(
                    title = stringResource(R.string.pairing_accept_on_tv),
                ) { StepIcon(Icons.Rounded.Tv, MaterialTheme.colorScheme.primary) }

                ConnectionState.AwaitingPin -> PairingStep(
                    title = stringResource(
                        if (state.invalidPin) R.string.pairing_invalid_pin else R.string.pairing_enter_pin,
                    ),
                    titleColor = if (state.invalidPin) MaterialTheme.colorScheme.error else null,
                    action = stringResource(R.string.pairing_enter_pin_button) to enterPin,
                ) { StepIcon(Icons.Rounded.Pin, MaterialTheme.colorScheme.primary) }

                ConnectionState.Connected -> PairingStep(
                    title = stringResource(R.string.pairing_success),
                    action = stringResource(R.string.pairing_done) to onFinished,
                ) { StepIcon(Icons.Rounded.CheckCircle, StatusColors.Connected) }

                is ConnectionState.Failed -> PairingStep(
                    title = stringResource(ActionText.error(connection.error)),
                    titleColor = MaterialTheme.colorScheme.error,
                    action = stringResource(R.string.pairing_retry) to viewModel::retry,
                ) { StepIcon(Icons.Rounded.ErrorOutline, StatusColors.Error) }
            }
        }
    }
}

@Composable
private fun PairingStep(
    title: String,
    titleColor: Color? = null,
    action: Pair<String, () -> Unit>? = null,
    visual: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        visual()
        Spacer(Modifier.height(10.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = titleColor ?: MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(12.dp))
            Button(onClick = action.second, label = { Text(action.first) })
        }
    }
}

@Composable
private fun StepIcon(icon: ImageVector, tint: Color) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(40.dp))
}

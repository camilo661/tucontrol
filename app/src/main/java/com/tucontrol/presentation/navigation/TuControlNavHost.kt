package com.tucontrol.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.tucontrol.presentation.devices.DevicesScreen
import com.tucontrol.presentation.home.HomeScreen
import com.tucontrol.presentation.pairing.PairingScreen
import com.tucontrol.presentation.remote.RemoteScreen
import com.tucontrol.presentation.settings.SettingsScreen
import com.tucontrol.presentation.welcome.WelcomeScreen
import com.tucontrol.presentation.welcome.WelcomeViewModel

private object Routes {
    const val HOME = "home"
    const val REMOTE = "remote"
    const val DEVICES = "devices"
    const val PAIRING = "pairing"
    const val SETTINGS = "settings"
    const val WELCOME = "welcome"
}

/** Swipe-to-dismiss navigation, the standard back gesture on Wear OS. */
@Composable
fun TuControlNavHost(welcome: WelcomeViewModel = viewModel(factory = WelcomeViewModel.Factory)) {
    val navController = rememberSwipeDismissableNavController()
    // The splash screen covers this brief wait; the guide opens only on the first run.
    val welcomeSeen by welcome.welcomeSeen.collectAsStateWithLifecycle()
    val start = when (welcomeSeen) {
        null -> return
        true -> Routes.HOME
        false -> Routes.WELCOME
    }

    SwipeDismissableNavHost(navController = navController, startDestination = start) {
        composable(Routes.WELCOME) {
            WelcomeScreen(
                onStart = {
                    welcome.markSeen()
                    if (!navController.popBackStack()) {
                        navController.navigate(Routes.HOME) { popUpTo(Routes.WELCOME) { inclusive = true } }
                    }
                },
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                onOpenRemote = { navController.navigate(Routes.REMOTE) },
                onOpenDevices = { navController.navigate(Routes.DEVICES) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.REMOTE) { RemoteScreen() }
        composable(Routes.DEVICES) {
            DevicesScreen(onDeviceSelected = { navController.navigate(Routes.PAIRING) })
        }
        composable(Routes.PAIRING) {
            PairingScreen(onFinished = { navController.popBackStack(Routes.HOME, inclusive = false) })
        }
        composable(Routes.SETTINGS) { SettingsScreen(onOpenGuide = { navController.navigate(Routes.WELCOME) }) }
    }
}

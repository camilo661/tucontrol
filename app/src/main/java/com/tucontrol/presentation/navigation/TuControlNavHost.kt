package com.tucontrol.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.tucontrol.presentation.devices.DevicesScreen
import com.tucontrol.presentation.home.HomeScreen
import com.tucontrol.presentation.pairing.PairingScreen
import com.tucontrol.presentation.remote.RemoteScreen
import com.tucontrol.presentation.settings.SettingsScreen

private object Routes {
    const val HOME = "home"
    const val REMOTE = "remote"
    const val DEVICES = "devices"
    const val PAIRING = "pairing"
    const val SETTINGS = "settings"
}

/** Swipe-to-dismiss navigation, the standard back gesture on Wear OS. */
@Composable
fun TuControlNavHost() {
    val navController = rememberSwipeDismissableNavController()

    SwipeDismissableNavHost(navController = navController, startDestination = Routes.HOME) {
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
        composable(Routes.SETTINGS) { SettingsScreen() }
    }
}

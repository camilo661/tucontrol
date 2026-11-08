package com.tucontrol.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.wear.compose.material3.AppScaffold
import com.tucontrol.TuControlApp
import com.tucontrol.presentation.navigation.TuControlNavHost
import com.tucontrol.presentation.theme.TuControlTheme

class MainActivity : ComponentActivity() {

    private val session get() = (application as TuControlApp).container.session

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent {
            TuControlTheme {
                AppScaffold {
                    TuControlNavHost()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        session.onForeground()
    }

    override fun onStop() {
        session.onBackground()
        super.onStop()
    }
}

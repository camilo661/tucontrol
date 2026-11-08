package com.tucontrol

import android.app.Application
import com.tucontrol.di.AppContainer

class TuControlApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

package com.tucontrol.presentation.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.tucontrol.TuControlApp
import com.tucontrol.di.AppContainer

/** Builds a [ViewModelProvider.Factory] that receives the app's [AppContainer]. */
inline fun <reified VM : ViewModel> containerFactory(
    crossinline create: AppContainer.() -> VM,
): ViewModelProvider.Factory = viewModelFactory {
    initializer { (this.container()).create() }
}

fun CreationExtras.container(): AppContainer =
    (checkNotNull(this[APPLICATION_KEY]) as TuControlApp).container

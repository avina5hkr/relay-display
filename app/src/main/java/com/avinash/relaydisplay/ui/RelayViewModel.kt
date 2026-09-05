package com.avinash.relaydisplay.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.ui.platform.LocalContext
import com.avinash.relaydisplay.app.AppContainer
import com.avinash.relaydisplay.app.RelayApp

/** Reaches the application-scoped container without any view model ever holding an Activity. */
@Composable
fun appContainer(): AppContainer =
    (LocalContext.current.applicationContext as RelayApp).container

/**
 * Builds a view model from the container.
 *
 * A three-line factory instead of a DI framework: the graph is small and this keeps the
 * construction of every view model visible at its call site.
 */
@Composable
inline fun <reified VM : ViewModel> relayViewModel(
    key: String? = null,
    crossinline create: (AppContainer) -> VM,
): VM {
    val container = appContainer()
    return viewModel(
        key = key,
        factory = viewModelFactory { initializer { create(container) } },
    )
}

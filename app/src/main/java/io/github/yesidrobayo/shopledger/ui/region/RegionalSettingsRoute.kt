package io.github.yesidrobayo.shopledger.ui.region

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.domain.usecase.region.ObserveRegionalSettingsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.SaveRegionalSettingsUseCase
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel

/**
 * La configuración regional en la navegación: arma su ViewModel con la base abierta y le pasa a la
 * pantalla su estado.
 *
 * @param firstRun si es el paso del primer uso.
 * @param onBack vuelve desde la barra superior.
 * @param onConfirmed sigue al paso siguiente del primer uso, cuando la región quedó guardada.
 * @param visit distingue cada visita al paso del primer uso: al volver a él, la región empieza sin
 *   el guardado anterior, que si no lo daría por confirmado otra vez.
 */
@Composable
fun RegionalSettingsRoute(
    firstRun: Boolean,
    onBack: () -> Unit,
    onConfirmed: () -> Unit,
    visit: Int = 0,
) {
    val model =
        rememberAppViewModel(key = "region-$visit") { dependencies ->
            val ports = dependencies.ports
            val container = dependencies.container
            val observe = ObserveRegionalSettingsUseCase(ports.settings)
            val save =
                SaveRegionalSettingsUseCase(ports.transactions, ports.settings, container.clock)
            RegionalSettingsViewModel(
                firstRun = firstRun,
                settings = { observe() },
                save = { save(it) },
                catalog = IcuRegionalCatalog(),
                workContext = container.dispatchers.default,
            )
        }
    when (model) {
        ScreenModel.Opening ->
            RegionalSettingsScreen(RegionalSettingsUiState.Loading, onAction = {}, onBack = onBack)

        ScreenModel.Unavailable ->
            RegionalSettingsScreen(RegionalSettingsUiState.Unavailable, onAction = {
            }, onBack = onBack)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val uiLocale = rememberUiLocale()
            LaunchedEffect(uiLocale) { viewModel.setUiLocale(uiLocale) }
            val saved =
                (uiState as? RegionalSettingsUiState.Ready)?.saveState == RegionalSaveState.SAVED
            LaunchedEffect(saved, firstRun) { if (saved && firstRun) onConfirmed() }
            RegionalSettingsScreen(uiState, viewModel::onAction, onBack)
        }
    }
}

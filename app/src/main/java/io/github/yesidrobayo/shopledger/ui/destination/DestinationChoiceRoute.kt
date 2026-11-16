package io.github.yesidrobayo.shopledger.ui.destination

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.domain.usecase.backup.BackupDestinationReader
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel

/**
 * La nube del teléfono no entrega sola: la prueba con Drive y la app cerrada no dio escrituras
 * válidas, así que cada copia se entrega a mano, con la app abierta, y la opción lo dice antes de
 * elegir.
 */
private val SLOTS_HANDOFF = SlotsHandoff.MANUAL

/**
 * `Dónde guardar las copias` en la navegación. La política ya se aceptó: la navegación la pregunta
 * antes de mostrar esta pantalla.
 *
 * @param onNotNow lo que hace `Ahora no`; fuera del primer uso, volver.
 */
@Composable
fun DestinationChoiceRoute(
    key: AppNavKey.BackupDestinationChoice,
    navigator: AppNavigator,
    onNotNow: () -> Unit = { navigator.back() },
) {
    val model =
        rememberAppViewModel { dependencies ->
            val reader = BackupDestinationReader(dependencies.container.data.backupDestination)
            DestinationChoiceViewModel(
                describe = reader::describe,
                firstRun = key.firstRun,
                slotsHandoff = SLOTS_HANDOFF,
                computation = dependencies.container.dispatchers.io,
            )
        }
    when (model) {
        ScreenModel.Opening ->
            DestinationChoiceScreen(DestinationChoiceUiState(firstRun = key.firstRun), onAction = {
            })

        ScreenModel.Unavailable -> PlaceholderRoute(key, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            LaunchedEffect(uiState.target) {
                val target = uiState.target ?: return@LaunchedEffect
                viewModel.onAction(DestinationChoiceAction.TargetHandled)
                when (target) {
                    DestinationChoiceTarget.BACK -> navigator.back()

                    DestinationChoiceTarget.NOT_NOW -> onNotNow()

                    DestinationChoiceTarget.BACKUP -> {
                        // Si se llegó desde el respaldo, se vuelve a él; si no, se abre.
                        navigator.back()
                        navigator.open(AppNavKey.Backup)
                    }

                    DestinationChoiceTarget.WEBDAV_CONNECT -> navigator.open(
                        AppNavKey.WebDavConnect(),
                    )

                    DestinationChoiceTarget.DOCUMENT_SLOTS ->
                        navigator.open(AppNavKey.DocumentSlotsSetup)

                    DestinationChoiceTarget.DATA_TRANSPARENCY ->
                        navigator.open(AppNavKey.DataTransparency)
                }
            }
            DestinationChoiceScreen(uiState, viewModel::onAction)
        }
    }
}

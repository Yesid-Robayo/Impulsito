package io.github.yesidrobayo.shopledger.ui.cash

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.LocalAppMessages
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarVisuals
import io.github.yesidrobayo.shopledger.ui.components.SnackbarVariant
import io.github.yesidrobayo.shopledger.ui.components.saveErrorText
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats

/**
 * La caja en la navegación. El cierre y las denominaciones se dibujan encima de la caja, en la misma
 * entrada: atrás los cierra primero y conserva lo escrito mientras la pantalla siga viva. Las hojas
 * de abrir, de un cierre anterior y de una denominación se cierran también con atrás.
 */
@Composable
fun CashRegisterRoute(navigator: AppNavigator) {
    val formats = LocalAppFormats.current
    val numbers = CashNumberFormat(formats.money.inputSymbols, formats.money.currencyDecimals)
    val model = rememberAppViewModel(key = "cash-register") {
        CashRegisterViewModel(CashUseCases.of(it), numbers)
    }
    when (model) {
        ScreenModel.Opening -> CashRegisterScreen(CashRegisterUiState(), {})

        ScreenModel.Unavailable -> PlaceholderRoute(AppNavKey.CashRegister, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            ShowMessage(uiState.message) { viewModel.onAction(CashRegisterAction.MessageShown) }
            val sheetOpen = uiState.openSheet != null || uiState.detail != null ||
                uiState.denominationEditor != null
            BackHandler(enabled = uiState.showDenominations && !sheetOpen) {
                viewModel.onAction(CashRegisterAction.CloseDenominations)
            }
            BackHandler(enabled = sheetOpen || uiState.closeForm != null) {
                viewModel.onAction(CashRegisterAction.DismissSheet)
            }
            val onAction: (CashRegisterAction) -> Unit = { action ->
                when (action) {
                    CashRegisterAction.Back -> navigator.back()

                    CashRegisterAction.ChooseZone, CashRegisterAction.ConfirmCurrency ->
                        navigator.open(AppNavKey.RegionalSettings())

                    CashRegisterAction.Retry, is CashRegisterAction.SelectAccount,
                    CashRegisterAction.StartOpen, is CashRegisterAction.OpenAccount,
                    CashRegisterAction.StartClose, is CashRegisterAction.CountAmount,
                    is CashRegisterAction.CountByDenomination, is CashRegisterAction.CountPieces,
                    is CashRegisterAction.CloseNote, CashRegisterAction.ConfirmOpen,
                    CashRegisterAction.ConfirmClose, CashRegisterAction.ConfirmDifference,
                    CashRegisterAction.DismissDifference, CashRegisterAction.DismissSheet,
                    is CashRegisterAction.OpenClosed, CashRegisterAction.OpenDenominations,
                    CashRegisterAction.CloseDenominations, CashRegisterAction.NewDenomination,
                    is CashRegisterAction.EditDenomination, is CashRegisterAction.DenominationValue,
                    is CashRegisterAction.DenominationActive, CashRegisterAction.SaveDenomination,
                    CashRegisterAction.MessageShown,
                    -> viewModel.onAction(action)
                }
            }
            CashRegisterContentHost(uiState, onAction)
        }
    }
}

/** Lo que va a la vista: la caja, el cierre o las denominaciones, con sus hojas. */
@Composable
internal fun CashRegisterContentHost(
    uiState: CashRegisterUiState,
    onAction: (CashRegisterAction) -> Unit,
) {
    val form = uiState.closeForm
    when {
        uiState.showDenominations -> DenominationsScreen(uiState, onAction)
        form != null -> CloseCashScreen(uiState, form, onAction)
        else -> CashRegisterScreen(uiState, onAction)
    }
    uiState.openSheet?.let { OpenCashSheetHost(uiState, it, onAction) }
    uiState.detail?.let { ClosedDetailSheet(uiState, it, onAction) }
    uiState.denominationEditor?.let { DenominationEditorSheet(it, onAction) }
}

/** Muestra [message] con los mensajes de la navegación, que sobreviven a la pantalla. */
@Composable
private fun ShowMessage(message: CashMessage?, onShown: () -> Unit) {
    val messages = LocalAppMessages.current
    val text = message?.let {
        stringResource(
            when (it) {
                CashMessage.Opened -> R.string.cash_opened
                CashMessage.Closed -> R.string.cash_closed
                is CashMessage.Failed -> saveErrorText(it.outOfSpace)
            },
        )
    }
    val variant = when (message) {
        null, CashMessage.Opened, CashMessage.Closed -> SnackbarVariant.SUCCESS
        is CashMessage.Failed -> SnackbarVariant.ERROR
    }
    LaunchedEffect(message) {
        if (text != null) {
            messages.show(AppSnackbarVisuals(text, variant))
            onShown()
        }
    }
}

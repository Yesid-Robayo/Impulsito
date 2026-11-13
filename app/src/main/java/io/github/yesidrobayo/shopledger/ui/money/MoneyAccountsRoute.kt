package io.github.yesidrobayo.shopledger.ui.money

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyDocument
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
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
import io.github.yesidrobayo.shopledger.ui.region.IcuRegionalCatalog
import io.github.yesidrobayo.shopledger.ui.region.RegionalOption
import java.util.Locale

/**
 * Las cuentas de dinero en la navegación. El libro de una cuenta, las hojas y las confirmaciones se
 * dibujan en la misma entrada: atrás cierra primero lo que esté encima, después el libro y después
 * las archivadas, y conserva lo escrito mientras la pantalla siga viva. Un movimiento que viene de un
 * documento lleva a ese documento.
 */
@Composable
fun MoneyAccountsRoute(key: AppNavKey.MoneyAccounts, navigator: AppNavigator) {
    val formats = LocalAppFormats.current
    val numbers = MoneyInputFormat(formats.money.inputSymbols, formats.quantity.inputSymbols)
    val uiLocale = formats.uiLocale
    val choices = remember(uiLocale) { IcuCurrencyChoices(uiLocale) }
    val model = rememberAppViewModel(key = "money-accounts-${key.accountId}") {
        MoneyAccountsViewModel(key.accountId, MoneyAccountsUseCases.of(it), numbers, choices)
    }
    when (model) {
        ScreenModel.Opening -> MoneyAccountsContentHost(MoneyAccountsUiState(), {})

        ScreenModel.Unavailable -> PlaceholderRoute(key, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            ShowMessage(uiState.message) { viewModel.onAction(MoneyAccountsAction.MessageShown) }
            BackHandler(enabled = uiState.overlayOpen) {
                viewModel.onAction(
                    if (uiState.dialog != null) {
                        MoneyAccountsAction.DismissDialog
                    } else {
                        MoneyAccountsAction.DismissSheet
                    },
                )
            }
            BackHandler(enabled = !uiState.overlayOpen && uiState.selectedId != null) {
                viewModel.onAction(MoneyAccountsAction.Select(null))
            }
            BackHandler(
                enabled = !uiState.overlayOpen && uiState.selectedId == null &&
                    uiState.showArchived,
            ) {
                viewModel.onAction(MoneyAccountsAction.ShowArchived(false))
            }
            val onAction: (MoneyAccountsAction) -> Unit = { action ->
                when (action) {
                    MoneyAccountsAction.Back -> navigator.back()

                    MoneyAccountsAction.ChooseZone, MoneyAccountsAction.ConfirmCurrency ->
                        navigator.open(AppNavKey.RegionalSettings())

                    is MoneyAccountsAction.OpenDocument -> {
                        viewModel.onAction(action)
                        navigator.open(destinationOf(action.document))
                    }

                    MoneyAccountsAction.Retry, is MoneyAccountsAction.ShowArchived,
                    is MoneyAccountsAction.Select, is MoneyAccountsAction.StartManual,
                    MoneyAccountsAction.StartTransfer, MoneyAccountsAction.NewAccount,
                    MoneyAccountsAction.EditAccount, is MoneyAccountsAction.ChooseAccount,
                    is MoneyAccountsAction.ChooseFrom, is MoneyAccountsAction.ChooseTo,
                    is MoneyAccountsAction.Amount, is MoneyAccountsAction.Rate,
                    is MoneyAccountsAction.Note, is MoneyAccountsAction.Name,
                    is MoneyAccountsAction.Kind, is MoneyAccountsAction.OpeningBalance,
                    MoneyAccountsAction.OpenCurrencies, is MoneyAccountsAction.CurrencyQuery,
                    is MoneyAccountsAction.ChooseCurrency, MoneyAccountsAction.CloseCurrencies,
                    MoneyAccountsAction.Save, MoneyAccountsAction.DismissSheet,
                    is MoneyAccountsAction.OpenMovement, MoneyAccountsAction.AskVoid,
                    MoneyAccountsAction.AskArchive, MoneyAccountsAction.Reactivate,
                    MoneyAccountsAction.ConfirmDialog, MoneyAccountsAction.DismissDialog,
                    MoneyAccountsAction.MessageShown,
                    -> viewModel.onAction(action)
                }
            }
            MoneyAccountsContentHost(uiState, onAction)
        }
    }
}

/** La pantalla del documento de un movimiento. Un gasto abre la lista de gastos. */
internal fun destinationOf(document: MoneyDocument): AppNavKey = when (document) {
    is MoneyDocument.Sale -> AppNavKey.SaleDetail(document.saleId)
    is MoneyDocument.Customer -> AppNavKey.CustomerDetail(document.customerId)
    is MoneyDocument.Supplier -> AppNavKey.PayableDetail(document.supplierId)
    is MoneyDocument.Purchase -> AppNavKey.PurchaseDetail(document.purchaseId)
    is MoneyDocument.Expense -> AppNavKey.Expenses()
    is MoneyDocument.CashSession -> AppNavKey.CashRegister
}

/** Las monedas del catálogo de ICU del teléfono, con sus nombres en [uiLocale]. */
private class IcuCurrencyChoices(private val uiLocale: Locale) : CurrencyChoices {
    private val catalog = IcuRegionalCatalog()
    private val options by lazy { catalog.currencies(uiLocale) }

    override fun options(): List<RegionalOption> = options

    override fun decimals(code: CurrencyCode): Int? = catalog.isoDecimals(code)
}

/** Muestra [message] con los mensajes de la navegación, que sobreviven a la pantalla. */
@Composable
private fun ShowMessage(message: MoneyMessage?, onShown: () -> Unit) {
    val messages = LocalAppMessages.current
    val text = message?.let {
        stringResource(
            when (it) {
                MoneyMessage.MovementSaved -> R.string.money_movement_saved
                MoneyMessage.AccountSaved -> R.string.money_account_saved
                MoneyMessage.NameTaken -> R.string.money_error_name_duplicate
                is MoneyMessage.Failed -> saveErrorText(it.outOfSpace)
            },
        )
    }
    val variant = when (message) {
        null, MoneyMessage.MovementSaved, MoneyMessage.AccountSaved -> SnackbarVariant.SUCCESS
        MoneyMessage.NameTaken, is MoneyMessage.Failed -> SnackbarVariant.ERROR
    }
    LaunchedEffect(message) {
        if (text != null) {
            messages.show(AppSnackbarVisuals(text, variant))
            onShown()
        }
    }
}

package io.github.yesidrobayo.shopledger.ui.payable

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.LocalAppMessages
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarVisuals
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.SnackbarVariant
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.saveErrorText
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.payment.ListDetailPanes

/**
 * La lista de cuentas por pagar en la navegación. Abrir un proveedor lleva a su libro, encima o, en
 * una ventana `Expanded`, en el panel derecho. `Nueva compra` abre una compra nueva.
 */
@Composable
fun PayablesRoute(key: AppNavKey.Payables, navigator: AppNavigator) {
    val model = rememberAppViewModel(key = "payables") { PayablesViewModel(PayableUseCases.of(it)) }
    when (model) {
        ScreenModel.Opening -> PayablesScreen(PayablesUiState(), {})

        ScreenModel.Unavailable -> PlaceholderRoute(key, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val expanded = maxWidth >= EXPANDED_WIDTH
                val onAction: (PayablesAction) -> Unit = { action ->
                    when (action) {
                        PayablesAction.Back -> navigator.back()

                        is PayablesAction.OpenSupplier -> if (expanded) {
                            selectedId = action.supplierId
                        } else {
                            navigator.open(AppNavKey.PayableDetail(action.supplierId))
                        }

                        PayablesAction.NewPurchase -> navigator.open(AppNavKey.Purchase())

                        PayablesAction.ChooseZone -> navigator.open(AppNavKey.RegionalSettings())

                        PayablesAction.Retry, is PayablesAction.FilterChange,
                        PayablesAction.ClearFilters,
                        -> viewModel.onAction(action)
                    }
                }
                if (!expanded) {
                    PayablesScreen(uiState, onAction)
                } else {
                    ListDetailPanes(
                        list = { PayablesScreen(uiState, onAction, it, selectedId) },
                        detail = {
                            val id = selectedId
                            if (id == null) {
                                EmptyState(
                                    icon = R.drawable.ic_receipt_long,
                                    title = stringResource(R.string.payable_title),
                                )
                            } else {
                                PayableDetailPane(
                                    supplierId = id,
                                    navigator = navigator,
                                    navigation = TopBarNavigation.NONE,
                                    onClose = { selectedId = null },
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

/** El libro de un proveedor en su propia pantalla. */
@Composable
fun PayableDetailRoute(key: AppNavKey.PayableDetail, navigator: AppNavigator) {
    PayableDetailPane(
        supplierId = key.supplierId,
        navigator = navigator,
        navigation = TopBarNavigation.BACK,
        onClose = { navigator.back() },
    )
}

/**
 * El libro con su ViewModel, en su propia pantalla o en el panel derecho de una ventana ancha:
 * cada proveedor elegido tiene el suyo. Atrás con la hoja abierta la cierra primero.
 */
@Composable
private fun PayableDetailPane(
    supplierId: Long,
    navigator: AppNavigator,
    navigation: TopBarNavigation,
    onClose: () -> Unit,
) {
    val formats = LocalAppFormats.current
    val numbers = PayableNumberFormat(formats.money.inputSymbols, formats.money.currencyDecimals)
    val model = rememberAppViewModel(key = "payable-$supplierId") {
        PayableDetailViewModel(supplierId, PayableUseCases.of(it), numbers)
    }
    when (model) {
        ScreenModel.Opening ->
            PayableDetailScreen(PayableDetailUiState(), {}, navigation = navigation)

        ScreenModel.Unavailable -> PlaceholderRoute(AppNavKey.PayableDetail(supplierId), navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            ShowMessage(uiState.message) { viewModel.onAction(PayableDetailAction.MessageShown) }
            BackHandler(enabled = uiState.payment != null) {
                viewModel.onAction(PayableDetailAction.DismissSheet)
            }
            val onAction: (PayableDetailAction) -> Unit = { action ->
                when (action) {
                    PayableDetailAction.Back -> onClose()

                    PayableDetailAction.OpenSupplier ->
                        navigator.open(AppNavKey.SupplierDetail(supplierId))

                    is PayableDetailAction.OpenPurchase ->
                        navigator.open(AppNavKey.PurchaseDetail(action.purchaseId))

                    PayableDetailAction.ChooseZone, PayableDetailAction.ConfirmCurrency ->
                        navigator.open(AppNavKey.RegionalSettings())

                    PayableDetailAction.Retry, PayableDetailAction.OpenPayment,
                    is PayableDetailAction.PaymentAmount, is PayableDetailAction.PaymentAccount,
                    is PayableDetailAction.PaymentDate, is PayableDetailAction.PaymentNote,
                    PayableDetailAction.SavePayment, PayableDetailAction.DismissSheet,
                    is PayableDetailAction.AskVoidPayment, PayableDetailAction.ConfirmVoid,
                    PayableDetailAction.DismissDialog, PayableDetailAction.MessageShown,
                    -> viewModel.onAction(action)
                }
            }
            PayableDetailScreen(uiState, onAction, navigation = navigation)
            uiState.payment?.let { SupplierPaymentSheetHost(uiState, it, onAction) }
            if (uiState.voidingEntryId != null) VoidPaymentConfirm(onAction)
        }
    }
}

/** Muestra [message] con los mensajes de la navegación, que sobreviven a la pantalla. */
@Composable
private fun ShowMessage(message: PayableDetailMessage?, onShown: () -> Unit) {
    val messages = LocalAppMessages.current
    val text = message?.let {
        stringResource(
            when (it) {
                PayableDetailMessage.PaymentRecorded -> R.string.payable_payment_recorded
                PayableDetailMessage.PaymentVoided -> R.string.payable_payment_voided
                is PayableDetailMessage.Failed -> saveErrorText(it.outOfSpace)
            },
        )
    }
    val variant = when (message) {
        null, PayableDetailMessage.PaymentRecorded, PayableDetailMessage.PaymentVoided ->
            SnackbarVariant.SUCCESS

        is PayableDetailMessage.Failed -> SnackbarVariant.ERROR
    }
    LaunchedEffect(message) {
        if (text != null) {
            messages.show(AppSnackbarVisuals(text, variant))
            onShown()
        }
    }
}

private val EXPANDED_WIDTH = 840.dp

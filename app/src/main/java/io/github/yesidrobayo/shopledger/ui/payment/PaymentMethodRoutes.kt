package io.github.yesidrobayo.shopledger.ui.payment

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.usecase.money.ObservePayingAccountsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.ArchivePaymentMethodUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.CreatePaymentMethodUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.DeletePaymentMethodUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.EditPaymentMethodUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.FindPaymentImagesUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.ImportPaymentImageUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.LoadPaymentMethodOptionsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.ObserveCashRoundingUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.ObservePaymentMethodsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.ReactivatePaymentMethodUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.ReorderPaymentMethodsUseCase
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/** Ancho desde el que la ventana es `Expanded`: lista y formulario van lado a lado. */
private val EXPANDED_WIDTH = 840.dp

/**
 * La lista de métodos de pago con su ViewModel. En una ventana `Expanded`, la lista y el formulario
 * van lado a lado, y elegir un método lo abre en el panel derecho en vez de encima. Mientras la base
 * abre, o si no abre, la pantalla de reserva de la navegación.
 */
@Composable
fun PaymentMethodsRoute(navigator: AppNavigator) {
    val model =
        rememberAppViewModel { dependencies ->
            val ports = dependencies.ports
            PaymentMethodsViewModel(
                observe = ObservePaymentMethodsUseCase(ports.paymentMethods),
                reorder =
                    ReorderPaymentMethodsUseCase(
                        ports.transactions,
                        ports.paymentMethods,
                        dependencies.container.clock,
                    ),
                findImages = FindPaymentImagesUseCase(ports.transactions, ports.mediaAssets),
            )
        }
    when (model) {
        ScreenModel.Opening,
        ScreenModel.Unavailable,
        -> PlaceholderRoute(AppNavKey.PaymentMethods, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            // Lo elegido sobrevive al giro y al cambio de clase de ventana. [round] da a cada
            // formulario abierto en el panel su propio ViewModel.
            var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
            var creating by rememberSaveable { mutableStateOf(false) }
            var round by rememberSaveable { mutableIntStateOf(0) }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val expanded = maxWidth >= EXPANDED_WIDTH
                val onAction: (PaymentMethodsAction) -> Unit = { action ->
                    when (action) {
                        PaymentMethodsAction.Back -> navigator.back()

                        PaymentMethodsAction.NewMethod ->
                            if (expanded) {
                                selectedId = null
                                creating = true
                                round++
                            } else {
                                navigator.open(AppNavKey.PaymentMethodEdit())
                            }

                        is PaymentMethodsAction.OpenMethod ->
                            if (expanded) {
                                selectedId = action.id
                                creating = false
                                round++
                            } else {
                                navigator.open(AppNavKey.PaymentMethodEdit(action.id))
                            }

                        PaymentMethodsAction.Retry,
                        PaymentMethodsAction.ToggleArchived,
                        PaymentMethodsAction.StartSorting,
                        PaymentMethodsAction.CancelSorting,
                        is PaymentMethodsAction.Move,
                        PaymentMethodsAction.SaveOrder,
                        -> viewModel.onAction(action)
                    }
                }
                if (!expanded) {
                    PaymentMethodsScreen(uiState = state, onAction = onAction)
                } else {
                    ListDetailPanes(
                        list = { paneModifier ->
                            PaymentMethodsScreen(
                                uiState = state,
                                onAction = onAction,
                                modifier = paneModifier,
                            )
                        },
                        detail = {
                            if (creating || selectedId != null) {
                                PaymentMethodFormPane(
                                    methodId = selectedId,
                                    viewModelKey = "payment-method-form-$round",
                                    onClose = {
                                        selectedId = null
                                        creating = false
                                    },
                                    onOpenMethod = { id ->
                                        selectedId = id
                                        creating = false
                                        round++
                                    },
                                    onUnavailable = {
                                        PlaceholderRoute(AppNavKey.PaymentMethods, navigator)
                                    },
                                )
                            } else {
                                NoMethodChosen()
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * El formulario de un método de pago, abierto encima de la lista. Al guardar, archivar, reactivar o
 * eliminar, vuelve a la lista.
 */
@Composable
fun PaymentMethodFormRoute(key: AppNavKey.PaymentMethodEdit, navigator: AppNavigator) {
    PaymentMethodFormPane(
        methodId = key.methodId,
        viewModelKey = null,
        onClose = { navigator.back() },
        onOpenMethod = { id ->
            // El método inicial con ese nombre se abre en lugar de este formulario.
            navigator.back()
            navigator.open(AppNavKey.PaymentMethodEdit(id))
        },
        onUnavailable = { PlaceholderRoute(key, navigator) },
    )
}

/**
 * El formulario con su ViewModel, en su propia pantalla o en el panel derecho de una ventana ancha.
 * [viewModelKey] separa el ViewModel de cada formulario abierto en el panel.
 */
@Composable
private fun PaymentMethodFormPane(
    methodId: Long?,
    viewModelKey: String?,
    onClose: () -> Unit,
    onOpenMethod: (Long) -> Unit,
    onUnavailable: @Composable () -> Unit,
) {
    val formats = LocalAppFormats.current
    val model =
        rememberAppViewModel(viewModelKey) { dependencies ->
            val ports = dependencies.ports
            val clock = dependencies.container.clock
            val methods = ports.paymentMethods
            val transactions = ports.transactions
            val media = ports.mediaAssets
            PaymentMethodFormViewModel(
                methodId = methodId,
                useCases =
                    PaymentMethodFormUseCases(
                        observe = ObservePaymentMethodsUseCase(methods),
                        loadOptions =
                            LoadPaymentMethodOptionsUseCase(transactions, methods, media),
                        create = CreatePaymentMethodUseCase(transactions, methods, media, clock),
                        edit = EditPaymentMethodUseCase(transactions, methods, media, clock),
                        archive = ArchivePaymentMethodUseCase(transactions, methods, clock),
                        reactivate = ReactivatePaymentMethodUseCase(transactions, methods, clock),
                        delete = DeletePaymentMethodUseCase(transactions, methods, media, clock),
                        observeCashRounding = ObserveCashRoundingUseCase(ports.settings),
                        importImage =
                            ImportPaymentImageUseCase(dependencies.container.data.imageImporter),
                        payingAccounts = ObservePayingAccountsUseCase(
                            ports.moduleUsage,
                            ports.settings,
                            ports.moneyAccounts,
                        )::invoke,
                    ),
                numbers =
                    FormNumbers(
                        percentSymbols = formats.percent.inputSymbols,
                        moneySymbols = formats.money.inputSymbols,
                        currencyDecimals = formats.money.currencyDecimals,
                    ),
            )
        }
    when (model) {
        ScreenModel.Opening,
        ScreenModel.Unavailable,
        -> onUnavailable()

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            val finished = (state as? PaymentMethodFormUiState.Editing)?.finished == true
            LaunchedEffect(finished) { if (finished) onClose() }
            // El selector de fotos del sistema: no pide permisos, y en Android viejos abre el de
            // documentos. Lo elegido solo se lee una vez, al recodificarlo.
            val picker =
                rememberLauncherForActivityResult(
                    ActivityResultContracts.PickVisualMedia(),
                ) { uri ->
                    if (uri != null) {
                        viewModel.onAction(PaymentMethodFormAction.PickImage(uri.toString()))
                    }
                }
            PaymentMethodFormScreen(
                onPickImage = {
                    picker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                uiState = state,
                onAction = { action ->
                    when (action) {
                        PaymentMethodFormAction.Back -> onClose()

                        is PaymentMethodFormAction.OpenMethod -> onOpenMethod(action.id)

                        is PaymentMethodFormAction.NameChanged,
                        is PaymentMethodFormAction.KindChanged,
                        is PaymentMethodFormAction.FeeRateChanged,
                        is PaymentMethodFormAction.FeeFixedChanged,
                        is PaymentMethodFormAction.SettlementDaysChanged,
                        is PaymentMethodFormAction.PriceAdjustmentChanged,
                        is PaymentMethodFormAction.InstructionsChanged,
                        is PaymentMethodFormAction.AccountChanged,
                        PaymentMethodFormAction.Save,
                        PaymentMethodFormAction.Retry,
                        PaymentMethodFormAction.AskArchive,
                        PaymentMethodFormAction.AskDelete,
                        PaymentMethodFormAction.DismissDialog,
                        PaymentMethodFormAction.ConfirmDialog,
                        PaymentMethodFormAction.Reactivate,
                        is PaymentMethodFormAction.PickImage,
                        PaymentMethodFormAction.RemoveImage,
                        -> viewModel.onAction(action)
                    }
                },
            )
        }
    }
}

/** En una ventana ancha, la lista ocupa dos quintos y el formulario el resto. */
private const val LIST_WEIGHT = 2f
private const val FORM_WEIGHT = 3f

/**
 * Lista y detalle lado a lado, separados por un filete, como en una tableta: [list] recibe el
 * modificador con su ancho, y [detail] ocupa el resto.
 */
@Composable
internal fun ListDetailPanes(
    list: @Composable (Modifier) -> Unit,
    detail: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxSize()) {
        list(Modifier.weight(LIST_WEIGHT))
        VerticalDivider(thickness = AppTheme.sizes.hairline, color = AppTheme.colors.cardBorder)
        Box(Modifier.weight(FORM_WEIGHT).fillMaxHeight()) { detail() }
    }
}

/** El panel de detalle cuando todavía no se eligió ningún método. */
@Composable
internal fun NoMethodChosen() {
    EmptyState(icon = R.drawable.ic_sell, title = stringResource(R.string.payment_method_title))
}

package io.github.yesidrobayo.shopledger.ui.supplier

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.platform.LocalContext
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
import io.github.yesidrobayo.shopledger.ui.backup.RootBackupBanner
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarVisuals
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.SnackbarVariant
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.saveErrorText
import io.github.yesidrobayo.shopledger.ui.payment.ListDetailPanes

/** Desde este ancho la ventana es `Expanded`: lista a la izquierda y detalle a la derecha. */
private val EXPANDED_WIDTH = 840.dp

/**
 * La lista de proveedores en la navegación: como pestaña de Productos, con [tabs], o como destino
 * propio, con atrás. En una ventana `Expanded`, el proveedor elegido se abre en el panel derecho en
 * vez de encima. Atrás con la hoja abierta la cierra primero.
 */
@Composable
fun SupplierListRoute(
    navigator: AppNavigator,
    tabs: (@Composable () -> Unit)? = null,
    navigation: TopBarNavigation = TopBarNavigation.NONE,
) {
    val model = rememberAppViewModel { SupplierListViewModel(SupplierUseCases.of(it)) }
    when (model) {
        ScreenModel.Opening ->
            SupplierListScreen(SupplierListUiState(), {}, navigation = navigation, tabs = tabs)

        ScreenModel.Unavailable -> PlaceholderRoute(AppNavKey.Suppliers, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val editor by viewModel.editor.editor.collectAsStateWithLifecycle()
            ShowMessage(uiState.message) { viewModel.onAction(SupplierListAction.MessageShown) }
            BackHandler(enabled = editor != null) {
                viewModel.editor.onAction(SupplierEditorAction.Dismiss)
            }
            var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val expanded = maxWidth >= EXPANDED_WIDTH
                val onAction: (SupplierListAction) -> Unit = { action ->
                    when (action) {
                        is SupplierListAction.OpenSupplier ->
                            if (expanded) {
                                selectedId = action.supplierId
                            } else {
                                navigator.open(AppNavKey.SupplierDetail(action.supplierId))
                            }

                        is SupplierListAction.QueryChange, SupplierListAction.Retry,
                        is SupplierListAction.NewSupplier, SupplierListAction.ToggleArchived,
                        is SupplierListAction.Reactivate, SupplierListAction.MessageShown,
                        -> viewModel.onAction(action)
                    }
                }
                val list: @Composable (Modifier) -> Unit = { paneModifier ->
                    SupplierListScreen(
                        uiState = uiState,
                        onAction = onAction,
                        modifier = paneModifier,
                        navigation = navigation,
                        onBack = { navigator.back() },
                        tabs = tabs,
                        selectedId = if (expanded) selectedId else null,
                        // Como pestaña de Productos, con el mismo banner que las otras dos.
                        backupBanner = { if (tabs != null) RootBackupBanner(navigator::open) },
                    )
                }
                if (!expanded) {
                    list(Modifier)
                } else {
                    ListDetailPanes(
                        list = list,
                        detail = {
                            val id = selectedId
                            if (id == null) {
                                EmptyState(
                                    icon = R.drawable.ic_storefront,
                                    title = stringResource(R.string.product_tab_suppliers),
                                )
                            } else {
                                SupplierDetailPane(
                                    supplierId = id,
                                    navigator = navigator,
                                    navigation = TopBarNavigation.NONE,
                                    onClose = { selectedId = null },
                                )
                            }
                        },
                    )
                }
                editor?.let {
                    SupplierEditorSheet(it, viewModel.editor::onAction, asPanel = false)
                }
            }
        }
    }
}

/** El detalle de un proveedor en su propia pantalla. Al eliminarlo, vuelve a la lista. */
@Composable
fun SupplierDetailRoute(key: AppNavKey.SupplierDetail, navigator: AppNavigator) {
    SupplierDetailPane(
        supplierId = key.supplierId,
        navigator = navigator,
        navigation = TopBarNavigation.BACK,
        onClose = { navigator.back() },
    )
}

/**
 * El detalle con su ViewModel, en su propia pantalla o en el panel derecho de una ventana ancha:
 * cada proveedor elegido tiene el suyo.
 */
@Composable
private fun SupplierDetailPane(
    supplierId: Long,
    navigator: AppNavigator,
    navigation: TopBarNavigation,
    onClose: () -> Unit,
) {
    val model =
        rememberAppViewModel(key = "supplier-$supplierId") {
            SupplierDetailViewModel(supplierId, SupplierUseCases.of(it))
        }
    when (model) {
        ScreenModel.Opening -> SupplierDetailScreen(SupplierDetailUiState(), {
        }, navigation = navigation)

        ScreenModel.Unavailable -> PlaceholderRoute(AppNavKey.SupplierDetail(supplierId), navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val editor by viewModel.editor.editor.collectAsStateWithLifecycle()
            val context = LocalContext.current
            ShowMessage(uiState.message) { viewModel.onAction(SupplierDetailAction.MessageShown) }
            LaunchedEffect(uiState.closed) { if (uiState.closed) onClose() }
            BackHandler(enabled = editor != null) {
                viewModel.editor.onAction(SupplierEditorAction.Dismiss)
            }
            SupplierDetailScreen(
                uiState = uiState,
                navigation = navigation,
                onAction = { action ->
                    when (action) {
                        SupplierDetailAction.Back -> onClose()

                        is SupplierDetailAction.Dial -> openDialer(context, action.phone)

                        is SupplierDetailAction.OpenPurchase ->
                            navigator.open(AppNavKey.PurchaseDetail(action.purchaseId))

                        is SupplierDetailAction.OpenProduct ->
                            navigator.open(AppNavKey.ProductDetail(action.productId))

                        SupplierDetailAction.NewPurchase ->
                            navigator.open(AppNavKey.Purchase(prefillSupplierId = supplierId))

                        SupplierDetailAction.OpenPayables ->
                            navigator.open(AppNavKey.PayableDetail(supplierId))

                        SupplierDetailAction.ReturnToSupplier ->
                            navigator.open(AppNavKey.SupplierReturn(supplierId = supplierId))

                        SupplierDetailAction.Edit, SupplierDetailAction.AskArchive,
                        SupplierDetailAction.AskDelete, SupplierDetailAction.Reactivate,
                        SupplierDetailAction.ConfirmDialog, SupplierDetailAction.DismissDialog,
                        SupplierDetailAction.Retry, SupplierDetailAction.MessageShown,
                        -> viewModel.onAction(action)
                    }
                },
            )
            editor?.let { SupplierEditorSheet(it, viewModel.editor::onAction, asPanel = false) }
        }
    }
}

/**
 * Muestra [message] con los mensajes de la navegación, que sobreviven a la pantalla: así
 * `Proveedor eliminado` se ve en la lista a la que se vuelve.
 */
@Composable
private fun ShowMessage(message: SupplierMessage?, onShown: () -> Unit) {
    val messages = LocalAppMessages.current
    val text = message?.let { messageText(it) }
    val variant =
        when (message) {
            null, SupplierMessage.Saved, SupplierMessage.Archived, SupplierMessage.Reactivated,
            SupplierMessage.Deleted,
            -> SnackbarVariant.SUCCESS

            SupplierMessage.NameTaken, SupplierMessage.HasHistory, is SupplierMessage.Failed ->
                SnackbarVariant.ERROR
        }
    LaunchedEffect(message) {
        if (text != null) {
            messages.show(AppSnackbarVisuals(text, variant))
            onShown()
        }
    }
}

@Composable
private fun messageText(message: SupplierMessage): String = stringResource(
    when (message) {
        SupplierMessage.Saved -> R.string.supplier_saved
        SupplierMessage.Archived -> R.string.supplier_archived
        SupplierMessage.Reactivated -> R.string.supplier_reactivated
        SupplierMessage.Deleted -> R.string.supplier_deleted
        SupplierMessage.NameTaken -> R.string.error_supplier_reactivate_duplicate
        SupplierMessage.HasHistory -> R.string.supplier_error_has_history
        is SupplierMessage.Failed -> saveErrorText(message.outOfSpace)
    },
)

/**
 * Abre el marcador del sistema con [phone] escrito, sin llamar: la persona decide si llama. No pide
 * ningún permiso. Si no hay marcador, como en una tableta sin teléfono, no pasa nada.
 */
private fun openDialer(context: Context, phone: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone, null)))
    } catch (_: ActivityNotFoundException) {
        // Sin marcador no hay nada que abrir; el número sigue a la vista.
    }
}

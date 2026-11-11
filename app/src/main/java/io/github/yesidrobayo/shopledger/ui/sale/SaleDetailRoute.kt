package io.github.yesidrobayo.shopledger.ui.sale

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.sale.ObserveSaleDetailUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.VoidSaleUseCase
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * El detalle de una venta en la navegación: arma su ViewModel con los casos de uso. Si la base va
 * al rescate, la pantalla no lee nada.
 *
 * Anulada para corregirla, abre la venta nueva con sus datos. Compartir arma el comprobante como
 * texto y se lo entrega al selector del sistema: otra app hace el envío, solo porque la persona lo
 * tocó, y esta no abre ninguna conexión.
 *
 * @param onBack lo que hace atrás: volver en la pila, o cerrar el panel si el detalle va junto al
 *   historial en una ventana ancha.
 */
@Composable
fun SaleDetailRoute(
    key: AppNavKey.SaleDetail,
    navigator: AppNavigator,
    onBack: () -> Unit = { navigator.back() },
) {
    val model =
        rememberAppViewModel(key = "sale-${key.saleId}") { dependencies ->
            val ports = dependencies.ports
            val clock = dependencies.container.clock
            val observe = ObserveSaleDetailUseCase(ports.sales, ports.salePayments, ports.products)
            val void = VoidSaleUseCase(
                transactions = ports.transactions,
                settings = ports.settings,
                products = ports.products,
                sales = ports.sales,
                ledger = ports.stockLedger,
                clock = clock,
            )
            SaleDetailViewModel(
                saleId = key.saleId,
                observeDetail = { observe(it) },
                observeCalendar = { BusinessCalendarSource(ports.settings).observe() },
                observeUnits = { ports.units.observeAll() },
                voidSale = { id, reason, note -> void(id, reason, note) },
                computation = dependencies.container.dispatchers.default,
                observeBusinessName = {
                    ports.settings.observe().map { it.profile.businessName }.distinctUntilChanged()
                },
            )
        }
    when (model) {
        ScreenModel.Opening -> SaleDetailScreen(SaleDetailUiState.Loading, onAction = {})

        ScreenModel.Unavailable -> PlaceholderRoute(key, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val context = LocalContext.current
            val formats = LocalAppFormats.current
            val chooserTitle = stringResource(R.string.action_share)
            val corrected = (uiState as? SaleDetailUiState.Ready)?.corrected
            LaunchedEffect(corrected) {
                if (corrected == null) return@LaunchedEffect
                viewModel.onAction(SaleDetailAction.CorrectShown)
                navigator.open(AppNavKey.NewSale(prefillFromSaleId = corrected))
            }
            SaleDetailScreen(
                uiState = uiState,
                onAction = { action ->
                    when (action) {
                        SaleDetailAction.Back -> onBack()

                        SaleDetailAction.Share -> {
                            val sale = (uiState as? SaleDetailUiState.Ready)?.sale
                            val receipt = sale?.receipt
                            if (sale != null && receipt != null) {
                                val text = SaleReceiptText.of(
                                    receipt,
                                    context.resources,
                                    formats,
                                    sale.units,
                                    sale.zone,
                                )
                                context.startActivity(shareIntent(text, chooserTitle))
                            }
                        }

                        else -> viewModel.onAction(action)
                    }
                },
            )
        }
    }
}

/**
 * El texto del comprobante para el selector del sistema: la persona elige con qué app enviarlo, y
 * nada se envía solo ni a una dirección fija.
 */
internal fun shareIntent(text: String, title: String): Intent = Intent.createChooser(
    Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    },
    title,
)

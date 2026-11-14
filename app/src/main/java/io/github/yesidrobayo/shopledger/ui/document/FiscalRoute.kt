package io.github.yesidrobayo.shopledger.ui.document

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.usecase.document.MarkSalesFiscalUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.document.ObserveFiscalMonthUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.document.ObserveFiscalPortalLinkUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.document.SaveFiscalPortalLinkUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.LocalAppMessages
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarVisuals
import io.github.yesidrobayo.shopledger.ui.components.SnackbarVariant
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.sale.shareIntent

/**
 * Los pendientes de facturar en la navegación: arma su ViewModel y resuelve lo que sale de la app.
 *
 * - `Compartir resumen` arma el texto y abre el selector del sistema: la persona elige la app, y
 *   nada sale solo ni a una dirección fija.
 * - `Abrir enlace` entrega el enlace que escribió la persona a otra app, solo porque lo tocó, sin
 *   WebView: la app no lo visita ni lee la respuesta. Sin ninguna app que lo abra, lo dice.
 * - `Ver venta` abre el detalle de la venta.
 *
 * Desde el detalle de una venta, abre con su hoja `Registro externo`.
 */
@Composable
fun FiscalRoute(key: AppNavKey.PendingFiscal, navigator: AppNavigator) {
    val model =
        rememberAppViewModel(key = "fiscal-${key.saleId}") { dependencies ->
            val ports = dependencies.ports
            val clock = dependencies.container.clock
            val observeMonth = ObserveFiscalMonthUseCase(ports.saleFiscalMarks)
            val mark = MarkSalesFiscalUseCase(ports.transactions, ports.saleFiscalMarks, clock)
            val saveLink = SaveFiscalPortalLinkUseCase(ports.transactions, ports.settings, clock)
            FiscalViewModel(
                observeMonth = { month, status -> observeMonth(month, status) },
                observeSale = { ports.saleFiscalMarks.observeSale(it) },
                portalUrl = ObserveFiscalPortalLinkUseCase(ports.settings)(),
                calendar = BusinessCalendarSource(ports.settings).observe(),
                now = clock::now,
                mark = { ids, status, reference -> mark(ids, status, reference) },
                saveLink = { saveLink(it) },
                initialSaleId = key.saleId,
            )
        }
    when (model) {
        ScreenModel.Opening -> FiscalScreen(FiscalUiState(), onAction = {})

        ScreenModel.Unavailable -> PlaceholderRoute(key, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val context = LocalContext.current
            val formats = LocalAppFormats.current
            val messages = LocalAppMessages.current
            val chooserTitle = stringResource(R.string.action_share)
            val noAppText = stringResource(R.string.fiscal_error_no_app)
            LaunchedEffect(uiState.message) {
                val message = uiState.message ?: return@LaunchedEffect
                viewModel.onAction(FiscalAction.MessageShown)
                when (message) {
                    FiscalMessage.NO_APP_FOR_LINK ->
                        messages.show(AppSnackbarVisuals(noAppText, SnackbarVariant.ERROR))
                }
            }
            FiscalScreen(
                uiState = uiState,
                onAction = { action ->
                    viewModel.onAction(action)
                    when (action) {
                        FiscalAction.Back -> navigator.back()

                        FiscalAction.ChooseZone -> navigator.open(AppNavKey.RegionalSettings())

                        is FiscalAction.ViewSale ->
                            navigator.open(AppNavKey.SaleDetail(action.saleId))

                        FiscalAction.OpenLink -> uiState.portalUrl?.let { url ->
                            if (!openLink(context, url)) {
                                viewModel.onAction(FiscalAction.NoAppForLink)
                            }
                        }

                        FiscalAction.ShareSummary -> {
                            val month = uiState.month
                            val content = uiState.content as? FiscalContent.Sales
                            if (month != null && content != null) {
                                val text = FiscalSummaryText.of(
                                    month,
                                    content.sales,
                                    content.total,
                                    context.resources,
                                    formats,
                                )
                                context.startActivity(shareIntent(text, chooserTitle))
                            }
                        }

                        FiscalAction.PreviousMonth,
                        FiscalAction.NextMonth,
                        is FiscalAction.FilterChange,
                        FiscalAction.Retry,
                        is FiscalAction.OpenSale,
                        is FiscalAction.ToggleSelection,
                        FiscalAction.ClearSelection,
                        is FiscalAction.MarkSelection,
                        is FiscalAction.ReferenceChange,
                        is FiscalAction.MarkSale,
                        FiscalAction.CloseSheet,
                        FiscalAction.ConfigureLink,
                        is FiscalAction.LinkChange,
                        FiscalAction.SaveLink,
                        FiscalAction.CloseLinkSheet,
                        FiscalAction.NoAppForLink,
                        FiscalAction.MessageShown,
                        -> Unit
                    }
                },
            )
        }
    }
}

/**
 * Abre [url] en la app que la persona tenga para eso, con `ACTION_VIEW` y sin WebView: la app no
 * lee la respuesta. Solo llega aquí un enlace `https` validado al guardarlo. `false` si ninguna
 * app lo abre.
 */
private fun openLink(context: Context, url: String): Boolean = try {
    context.startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE),
    )
    true
} catch (_: ActivityNotFoundException) {
    false
}

package io.github.yesidrobayo.shopledger.ui.sale

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.experience.BusinessTool
import io.github.yesidrobayo.shopledger.domain.usecase.experience.ObserveModuleUsageUseCase
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.expense.ExpensesRoute
import io.github.yesidrobayo.shopledger.ui.order.OrdersRoute
import io.github.yesidrobayo.shopledger.ui.purchase.PurchaseHistoryRoute
import io.github.yesidrobayo.shopledger.ui.quote.QuotesRoute
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Los segmentos de la raíz de Registros, en su orden. */
enum class RecordsTab {
    SALES,
    PURCHASES,
    EXPENSES,

    /** Solo aparece si hay al menos una cotización. */
    QUOTES,

    /** Solo aparece si hay al menos un pedido. */
    ORDERS,
}

/**
 * Los segmentos de Registros que solo aparecen si su herramienta está en uso, en su orden, y cómo se
 * elige uno. Los tres de siempre los dibuja cada lista; estos los agrega [RecordsSegments] detrás.
 */
internal class RecordsExtraSegments(val tabs: List<RecordsTab>, val select: (RecordsTab) -> Unit)

internal val LocalRecordsExtraSegments =
    staticCompositionLocalOf { RecordsExtraSegments(emptyList()) {} }

/**
 * La raíz de Registros: dibuja en el mismo lugar el segmento elegido, que sobrevive al giro, a
 * abrir un detalle y volver, y a que Android cierre el proceso. Cambiar de segmento no apila nada
 * ni abre otra pantalla: la barra de arriba, el banner y los segmentos quedan quietos, la barra
 * inferior sigue a la vista y atrás sigue llevando a Inicio.
 *
 * Cada segmento conserva su ViewModel mientras Registros esté abierto: volver a uno ya visto lo
 * muestra con los filtros que tenía.
 *
 * `Cotizaciones` solo aparece si hay al menos una, y `Pedidos` si hay al menos uno: quien no los usa
 * no ve nada nuevo. Si deja de haberlos mientras está elegido, vuelve a `Ventas`.
 */
@Composable
fun RecordsRoute(navigator: AppNavigator) {
    var tab by rememberSaveable { mutableStateOf(RecordsTab.SALES) }
    val select: (RecordsTab) -> Unit = { tab = it }
    val extra = rememberExtraTabs()
    LaunchedEffect(extra, tab) {
        if (tab.toolOf() != null && tab !in extra) tab = RecordsTab.SALES
    }
    val segments = RecordsExtraSegments(extra, select)
    CompositionLocalProvider(LocalRecordsExtraSegments provides segments) {
        when (tab) {
            RecordsTab.SALES -> SaleHistoryRoute(navigator, select)

            RecordsTab.PURCHASES ->
                PurchaseHistoryRoute(AppNavKey.PurchaseHistory(), navigator, onSegment = select)

            RecordsTab.EXPENSES ->
                ExpensesRoute(AppNavKey.Expenses(), navigator, onSegment = select)

            RecordsTab.QUOTES -> QuotesRoute(navigator, asTab = true, onSegment = select)

            RecordsTab.ORDERS -> OrdersRoute(navigator, asTab = true, onSegment = select)
        }
    }
}

/**
 * Los segmentos extra que están en uso, leídos del uso de módulos de la base abierta. Mientras abre,
 * sin base o si la lectura falla, ninguno: los tres de siempre siguen funcionando.
 */
@Composable
private fun rememberExtraTabs(): List<RecordsTab> {
    val model = rememberAppViewModel { dependencies ->
        RecordsSegmentsViewModel(ObserveModuleUsageUseCase(dependencies.ports.moduleUsage)())
    }
    val viewModel = when (model) {
        ScreenModel.Opening, ScreenModel.Unavailable -> return emptyList()
        is ScreenModel.Ready -> model.viewModel
    }
    val tabs by viewModel.tabs.collectAsStateWithLifecycle()
    return tabs
}

/** Los segmentos extra en uso, leídos una vez para toda la raíz de Registros. */
internal class RecordsSegmentsViewModel(usage: Flow<Set<BusinessTool>>) : ViewModel() {
    val tabs: StateFlow<List<RecordsTab>> = usage
        .map { used -> RecordsTab.entries.filter { it.toolOf() in used } }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/** La herramienta que hace aparecer el segmento, o `null` si aparece siempre. */
private fun RecordsTab.toolOf(): BusinessTool? = when (this) {
    RecordsTab.SALES, RecordsTab.PURCHASES, RecordsTab.EXPENSES -> null
    RecordsTab.QUOTES -> BusinessTool.QUOTES
    RecordsTab.ORDERS -> BusinessTool.ORDERS
}

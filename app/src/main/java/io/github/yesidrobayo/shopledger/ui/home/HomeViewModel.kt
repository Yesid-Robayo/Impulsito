package io.github.yesidrobayo.shopledger.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupOverview
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleHistoryFilter
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleHistoryRow
import io.github.yesidrobayo.shopledger.domain.model.sale.SalesSummary
import io.github.yesidrobayo.shopledger.domain.usecase.home.TodaySales
import io.github.yesidrobayo.shopledger.domain.usecase.sale.SaleHistoryReader
import io.github.yesidrobayo.shopledger.ui.backup.BackupWording
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/**
 * Inicio: lo vendido hoy y las últimas ventas, de nuevo con cada venta o anulación confirmada, sin
 * recargar la pantalla.
 *
 * El día se vuelve a mirar con [onReturnToForeground]: quien deja la app abierta en Inicio y vuelve
 * al día siguiente ve las cifras del día nuevo, sin un trabajo en segundo plano que la despierte.
 *
 * @param todaySales las ventas de hoy; recibe las veces que se vuelve a mirar la fecha.
 * @param hasProducts si el negocio ya tiene algún producto.
 * @param computation el hilo donde se leen las últimas ventas, fuera del principal.
 * @param backupOverview el estado del respaldo; recibe las veces que la pantalla vuelve a primer
 *   plano, para ver un día sin copia aunque nada haya cambiado.
 * @param providerLabel el nombre de la nube del teléfono, si el destino son sus ranuras.
 * @param zones la zona del negocio, para decir cuándo fue la última copia.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    todaySales: (Flow<Unit>) -> Flow<TodaySales>,
    hasProducts: Flow<Boolean>,
    private val history: SaleHistoryReader,
    private val computation: CoroutineDispatcher,
    backupOverview: (Flow<Unit>) -> Flow<BackupOverview> = { emptyFlow() },
    private val providerLabel: suspend () -> String? = { null },
    zones: Flow<BusinessTimeZone?> = flowOf(null),
) : ViewModel() {
    // La primera mirada a la fecha es al abrir; las demás, al volver a primer plano. Un contador,
    // y no un evento, porque dos vueltas seguidas sin leer entre ellas valen por una.
    private val dayChecks = MutableStateFlow(0L)
    private val attempts = MutableStateFlow(0)

    private val recentSales: Flow<List<SaleHistoryRow>> = history.changes().map {
        withContext(computation) { history.page(SaleHistoryFilter(), after = null, RECENT_SALES) }
    }

    val uiState: StateFlow<HomeUiState> = attempts.flatMapLatest { attempt ->
        combine<TodaySales, Boolean, List<SaleHistoryRow>, HomeUiState>(
            todaySales(dayChecks.map {}),
            hasProducts,
            recentSales,
        ) { today, products, recent -> contentOf(today, products, recent) }
            // Al reintentar, la pantalla deja el error mientras vuelve a leer.
            .onStart { if (attempt > 0) emit(HomeUiState.Loading) }
            // Una falla de lectura no cierra la pantalla: muestra el error y deja reintentar.
            .catch { emit(HomeUiState.ReadError) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), HomeUiState.Loading)

    /**
     * El estado del respaldo, aparte de las ventas: si no se puede leer, Inicio sigue mostrando el
     * día, sin chip. El nombre de la nube se lee de nuevo solo si cambia el destino.
     */
    val backup: StateFlow<BackupWording?> =
        combine(backupOverview(dayChecks.map {}), zones) { overview, zone -> overview to zone }
            .mapLatest<Pair<BackupOverview, BusinessTimeZone?>, BackupWording?> { pair ->
                val (overview, zone) = pair
                BackupWording(overview, providerOf(overview.destinationKind), zone)
            }
            .catch { emit(null) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), null)

    private var providerFor: Pair<BackupDestinationKind?, String?>? = null

    private suspend fun providerOf(kind: BackupDestinationKind?): String? {
        if (kind != BackupDestinationKind.DOCUMENT_SLOTS) return null
        providerFor?.takeIf { it.first == kind }?.let { return it.second }
        val label = withContext(computation) { providerLabel() }
        providerFor = kind to label
        return label
    }

    fun onAction(action: HomeAction) {
        when (action) {
            HomeAction.Retry -> attempts.value += 1

            // Lo demás lo resuelve la navegación.
            HomeAction.OpenSettings,
            HomeAction.OpenBusiness,
            HomeAction.CreateFirstProduct,
            HomeAction.OpenAllSales,
            is HomeAction.OpenSale,
            HomeAction.OpenBackup,
            HomeAction.ChooseDestination,
            -> Unit
        }
    }

    /** La pantalla volvió a primer plano: si cambió el día, las cifras pasan al día nuevo. */
    fun onReturnToForeground() {
        dayChecks.value += 1
    }

    private fun contentOf(
        today: TodaySales,
        hasProducts: Boolean,
        recent: List<SaleHistoryRow>,
    ): HomeUiState.Content = when (today) {
        is TodaySales.Ready -> HomeUiState.Content(
            today = today.date,
            zone = today.zone,
            hero = heroOf(today.summary),
            firstUse = !hasProducts,
            recentSales = recent,
        )

        TodaySales.UnknownZone -> HomeUiState.Content(
            today = null,
            zone = null,
            hero = TodayHero.Unavailable,
            firstUse = !hasProducts,
            recentSales = recent,
        )
    }

    private fun heroOf(summary: SalesSummary?): TodayHero = when {
        summary == null -> TodayHero.Unavailable

        // Sin ventas ni devoluciones, el día espera su primera venta. Una devolución sin ventas
        // sí cambia las cifras, y se muestran.
        summary.salesCount == 0L && summary.netSales == Money.ZERO &&
            summary.grossProfit == Money.ZERO -> TodayHero.NoSalesYet

        else -> TodayHero.Sales(summary)
    }

    companion object {
        /** Cuántas ventas muestra Inicio: el resto vive en el historial. */
        const val RECENT_SALES = 5

        /** Tras girar la pantalla, las lecturas siguen vivas este tiempo y no se repiten. */
        private const val STOP_AFTER_MS = 5_000L
    }
}

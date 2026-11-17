package io.github.yesidrobayo.shopledger.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupOverview
import io.github.yesidrobayo.shopledger.domain.model.backup.PhotoBackupFacts
import io.github.yesidrobayo.shopledger.domain.model.report.GoalAdvance
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleHistoryFilter
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleHistoryRow
import io.github.yesidrobayo.shopledger.domain.model.sale.SalesSummary
import io.github.yesidrobayo.shopledger.domain.rule.backup.NoDestinationAcknowledgement
import io.github.yesidrobayo.shopledger.domain.rule.backup.NoDestinationNotice
import io.github.yesidrobayo.shopledger.domain.rule.backup.NoDestinationNoticeRule
import io.github.yesidrobayo.shopledger.domain.usecase.home.HomeProfit
import io.github.yesidrobayo.shopledger.domain.usecase.home.TodaySales
import io.github.yesidrobayo.shopledger.domain.usecase.sale.SaleHistoryReader
import io.github.yesidrobayo.shopledger.domain.usecase.stock.InventoryOverview
import io.github.yesidrobayo.shopledger.ui.backup.BackupWording
import io.github.yesidrobayo.shopledger.ui.backup.BackupWordings
import io.github.yesidrobayo.shopledger.ui.backup.NoDestinationNoticeStore
import io.github.yesidrobayo.shopledger.ui.stock.inventoryItemOf
import java.io.IOException
import kotlin.time.Instant
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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
 * @param inventory el inventario, del que sale la tarjeta por agotarse con el mismo conteo y el
 *   mismo orden que su pantalla. No mira las alertas: se ve aunque las notificaciones estén
 * negadas.
 * @param homeProfit la ganancia final de la semana y del mes; recibe las veces que se vuelve a
 *   mirar la fecha.
 * @param noticeStore dónde se guarda cuándo se aceptó el aviso sin destino, fuera de la base.
 * @param photoBackup lo que se sabe del respaldo de las fotos, para su línea aparte.
 * @param now el reloj, para contar los días desde que se aceptó ese aviso.
 * @param monthGoal la meta de ventas del mes en curso; recibe las veces que se vuelve a mirar la
 *   fecha.
 * @param requestBackup pide la copia con `Guardar en {provider}`, con la nube del teléfono. Un
 *   fallo al pedirla no cierra la pantalla: el estado del respaldo sigue diciendo lo pendiente.
 * @param producible los id de lo que se puede producir hoy, para que las filas de la tarjeta digan
 *   lo mismo que las del inventario.
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
    inventory: Flow<InventoryOverview> = emptyFlow(),
    homeProfit: (Flow<Unit>) -> Flow<HomeProfit?> = { flowOf(null) },
    private val noticeStore: NoDestinationNoticeStore? = null,
    private val now: () -> Instant = { kotlin.time.Clock.System.now() },
    private val photoBackup: suspend () -> PhotoBackupFacts? = { null },
    monthGoal: (Flow<Unit>) -> Flow<GoalAdvance?> = { flowOf(null) },
    private val requestBackup: suspend () -> Unit = {},
    producible: Flow<Set<Long>> = flowOf(emptySet()),
) : ViewModel() {
    // La primera mirada a la fecha es al abrir; las demás, al volver a primer plano. Un contador,
    // y no un evento, porque dos vueltas seguidas sin leer entre ellas valen por una.
    private val dayChecks = MutableStateFlow(0L)
    private val attempts = MutableStateFlow(0)

    // La aceptación del aviso sin destino, leída del archivo una vez al abrir.
    private val acknowledgement = MutableStateFlow<Acknowledgement>(Acknowledgement.Unknown)

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
        BackupWordings(providerLabel, computation)
            .of(backupOverview(dayChecks.map {}), zones)
            // Con un destino elegido, el ciclo del aviso sin destino termina.
            .onEach { if (it.overview.destinationKind != null) forgetAcknowledgement() }
            .map<BackupWording, BackupWording?> { it }
            .catch { emit(null) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), null)

    /**
     * Cuántas fotos llevan más de un día sin subir, con WebDAV: una línea aparte bajo el chip, que no
     * cambia el estado del respaldo. Cero si no hay o si no se puede leer. Se vuelve a mirar al
     * volver a primer plano.
     */
    val photosPending: StateFlow<Int> =
        dayChecks
            .map { photoBackup()?.takeIf { it.showsHomeLine }?.pendingOverADay ?: 0 }
            .flowOn(computation)
            .catch { emit(0) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), 0)

    /**
     * La tarjeta por agotarse, aparte de las ventas: si no se puede leer, Inicio sigue mostrando el
     * día, sin tarjeta. Sin nada que se esté acabando no hay tarjeta.
     */
    val lowStock: StateFlow<HomeLowStock?> =
        // Sin saber qué se produce, la tarjeta sigue: sus filas solo no lo marcan.
        combine(inventory, producible.catch { emit(emptySet()) }) { overview, made ->
            overview.lowStock.takeIf { it.isNotEmpty() }?.let { low ->
                HomeLowStock(
                    count = low.size,
                    negativeCount = overview.negative.size,
                    items = low.take(LOW_STOCK_ROWS).map {
                        inventoryItemOf(it, overview.units, made)
                    },
                )
            }
        }.flowOn(computation)
            .catch { emit(null) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), null)

    /**
     * La ganancia de la semana y del mes, aparte de las ventas: si no se puede leer, Inicio sigue
     * mostrando el día, sin esas tarjetas.
     */
    val profit: StateFlow<HomeProfit?> =
        homeProfit(dayChecks.map {})
            .catch { emit(null) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), null)

    /**
     * La meta de ventas del mes en curso, aparte de las ventas: sin meta, o si no se puede leer,
     * Inicio sigue igual, sin la tarjeta.
     */
    val goal: StateFlow<GoalAdvance?> =
        monthGoal(dayChecks.map {})
            .catch { emit(null) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), null)

    /**
     * Cómo va el aviso sin destino: entero, reducido a una línea, o `null` mientras se lee si se
     * aceptó. Se vuelve a mirar al volver a primer plano, para contar los días.
     */
    val noDestinationNotice: StateFlow<NoDestinationNotice?> =
        combine(acknowledgement, uiState, dayChecks) { read, ui, _ ->
            when (read) {
                Acknowledgement.Unknown -> null

                is Acknowledgement.Known ->
                    NoDestinationNoticeRule.noticeOf(read.value, now(), lastSaleIdOf(ui))
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), null)

    init {
        viewModelScope.launch {
            val read = withContext(computation) {
                try {
                    noticeStore?.read()
                } catch (_: IOException) {
                    // Un archivo que no se lee vale como ninguna aceptación: el aviso va entero.
                    null
                }
            }
            // Si ya se aceptó o se eligió destino mientras se leía, eso manda.
            acknowledgement.compareAndSet(Acknowledgement.Unknown, Acknowledgement.Known(read))
        }
    }

    fun onAction(action: HomeAction) {
        when (action) {
            HomeAction.Retry -> attempts.value += 1

            HomeAction.AcknowledgeNoDestination -> acknowledge()

            HomeAction.SaveToProvider -> saveToProvider()

            // Lo demás lo resuelve la navegación.
            HomeAction.OpenSettings,
            HomeAction.OpenBusiness,
            HomeAction.CreateFirstProduct,
            HomeAction.OpenAllSales,
            is HomeAction.OpenSale,
            HomeAction.OpenBackup,
            HomeAction.ChooseDestination,
            HomeAction.ReviewPolicy,
            HomeAction.OpenPhrase,
            HomeAction.OpenPurchase,
            HomeAction.OpenExpense,
            is HomeAction.OpenLowStock,
            is HomeAction.OpenProduct,
            HomeAction.OpenGoals,
            -> Unit
        }
    }

    /**
     * `Guardar en {provider}`: pide la copia fuera del hilo principal. Si no se pudo pedir, el
     * chip sigue mostrando los cambios sin copia, y el botón sigue ahí para intentarlo de
     * nuevo.
     */
    private fun saveToProvider() {
        viewModelScope.launch {
            try {
                withContext(computation) { requestBackup() }
            } catch (_: IOException) {
                // Lo pendiente sigue a la vista: nada se da por guardado.
            } catch (_: IllegalStateException) {
                // La base todavía no se puede leer: lo mismo.
            }
        }
    }

    /**
     * `Entendido`: se guarda el momento y la venta más reciente. Si no se puede guardar, el aviso
     * queda reducido solo mientras dure esta pantalla, y la próxima vez vuelve entero.
     */
    private fun acknowledge() {
        val accepted = NoDestinationAcknowledgement(now(), lastSaleIdOf(uiState.value))
        acknowledgement.value = Acknowledgement.Known(accepted)
        viewModelScope.launch {
            withContext(computation) {
                try {
                    noticeStore?.write(accepted)
                } catch (_: IOException) {
                    // Sin archivo, la próxima apertura lo muestra entero: el lado seguro.
                }
            }
        }
    }

    /** Ya hay destino: se borra la aceptación, y un destino quitado después avisa entero. */
    private fun forgetAcknowledgement() {
        val current = acknowledgement.value
        if (current is Acknowledgement.Known && current.value == null) return
        acknowledgement.value = Acknowledgement.Known(null)
        viewModelScope.launch {
            withContext(computation) {
                try {
                    noticeStore?.clear()
                } catch (_: IOException) {
                    // Quedó el archivo: al quitar el destino, la regla decide con su fecha.
                }
            }
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

    // Las ventas solo crecen de número y ninguna se borra: la mayor de las últimas es la más
    // reciente.
    private fun lastSaleIdOf(ui: HomeUiState): Long =
        (ui as? HomeUiState.Content)?.recentSales?.maxOfOrNull { it.id } ?: 0L

    private fun heroOf(summary: SalesSummary?): TodayHero = when {
        summary == null -> TodayHero.Unavailable

        // Sin ventas ni devoluciones, el día espera su primera venta. Una devolución sin ventas
        // sí cambia las cifras, y se muestran.
        summary.salesCount == 0L && summary.netSales == Money.ZERO &&
            summary.grossProfit == Money.ZERO -> TodayHero.NoSalesYet

        else -> TodayHero.Sales(summary)
    }

    /** Lo que se sabe de la aceptación del aviso sin destino. */
    private sealed interface Acknowledgement {
        /** Todavía se está leyendo el archivo. */
        data object Unknown : Acknowledgement

        /** Ya se sabe: [value] es la aceptación, o `null` si no hay. */
        data class Known(val value: NoDestinationAcknowledgement?) : Acknowledgement
    }

    companion object {
        /** Cuántas ventas muestra Inicio: el resto vive en el historial. */
        const val RECENT_SALES = 5

        /** Cuántos productos muestra la tarjeta por agotarse: el resto vive en el inventario. */
        const val LOW_STOCK_ROWS = 3

        /** Tras girar la pantalla, las lecturas siguen vivas este tiempo y no se repiten. */
        private const val STOP_AFTER_MS = 5_000L
    }
}

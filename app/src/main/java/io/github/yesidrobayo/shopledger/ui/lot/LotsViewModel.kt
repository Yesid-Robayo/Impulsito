package io.github.yesidrobayo.shopledger.ui.lot

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.settings.NoticeSettings
import io.github.yesidrobayo.shopledger.domain.model.stock.Location
import io.github.yesidrobayo.shopledger.domain.model.stock.LotEntry
import io.github.yesidrobayo.shopledger.domain.model.stock.LotFilter
import io.github.yesidrobayo.shopledger.domain.model.stock.LotsOverview
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementRecord
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.rule.stock.LocationRules
import io.github.yesidrobayo.shopledger.domain.rule.stock.LotCodeCheck
import io.github.yesidrobayo.shopledger.domain.rule.stock.LotListRules
import io.github.yesidrobayo.shopledger.domain.usecase.settings.SetExpiryNoticeDaysResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.CorrectLotResult
import io.github.yesidrobayo.shopledger.ui.components.lotExpiryOf
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.DayOfWeek

/**
 * Lo que la pantalla de lotes le pide al dominio. Es una interfaz para que las pruebas del
 * ViewModel den respuestas fijas sin una base.
 */
interface LotsOperations {
    /** Los lotes, de todos los productos o solo de [productId]. */
    fun lots(productId: Long?): Flow<LotsOverview>

    fun units(): Flow<List<MeasureUnit>>

    /** Las ubicaciones vigentes: con más de una, cada lote dice dónde está. */
    fun locations(): Flow<List<Location>>

    /** El calendario del negocio, para saber qué día es hoy en la hoja de la fecha. */
    fun calendar(): Flow<BusinessCalendarResult>

    fun now(): Instant

    /** Los movimientos del lote, del más reciente al más antiguo. */
    fun movements(productId: Long, lotId: Long): Flow<List<StockMovementRecord>>

    suspend fun correct(lotId: Long, code: String, expiryDate: BusinessDate?): CorrectLotResult

    suspend fun setNoticeDays(days: Int): SetExpiryNoticeDaysResult
}

/**
 * La pantalla de lotes y vencimientos: qué hay por lote, qué vence pronto y qué ya venció.
 *
 * Todo sale de los lotes y sus saldos, no de los avisos, así que funciona sin el permiso de
 * notificaciones. Cada venta, compra o ajuste llega solo, porque la lista observa la base. El
 * armado corre en [computation], nunca en el hilo principal. Un error al guardar nunca borra lo
 * escrito en la hoja.
 *
 * @param productId solo los lotes de ese producto, como al llegar desde su detalle; entonces la
 *   lista abre en `Todos`, porque se busca un lote y no lo que vence.
 * @param openLotId el lote cuya hoja se abre al llegar.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LotsViewModel(
    private val operations: LotsOperations,
    computation: CoroutineDispatcher,
    private val productId: Long? = null,
    openLotId: Long? = null,
) : ViewModel() {
    private val filter =
        MutableStateFlow(if (productId == null) LotFilter.EXPIRING else LotFilter.ALL)
    private val sheet = MutableStateFlow<LotSheet?>(openLotId?.let { LotSheet.Detail(it) })
    private val message = MutableStateFlow<LotsMessage?>(null)
    private val attempts = MutableStateFlow(0)
    private var latest: LotsReady? = null

    private val read: Flow<Read?> = attempts.flatMapLatest {
        val units = operations.units().catch { emit(emptyList()) }
        // Sin ubicaciones leídas, la lista sigue: solo no dice dónde está cada lote.
        val locations = operations.locations().catch { emit(emptyList()) }
        val calendar = operations.calendar().map<BusinessCalendarResult, BusinessCalendarResult?> {
            it
        }.catch { emit(null) }
        combine(operations.lots(productId), units, locations, calendar) { lots, u, l, c ->
            Read(lots, u.associateBy { it.id }, l, c)
        }.map<Read, Read?> { it }
            .flowOn(computation)
            // Una falla de lectura no cierra la pantalla: muestra el error y deja reintentar.
            .catch { emit(null) }
    }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), replay = 1)

    // Los movimientos del lote abierto, mientras su hoja esté abierta. El producto sale de lo
    // leído: la hoja puede abrirse antes de la primera lectura, al llegar con un lote ya elegido.
    private val detailRows: Flow<List<StockMovementRecord>> = combine(
        sheet.map { (it as? LotSheet.Detail)?.lotId },
        read.map { data ->
            data?.overview?.entries.orEmpty().associate { it.lot.id to it.product.id }
        },
    ) { lotId, products -> lotId?.let { id -> products[id]?.let { id to it } } }
        .distinctUntilChanged()
        .flatMapLatest { open ->
            if (open == null) {
                flowOf(emptyList())
            } else {
                operations.movements(open.second, open.first).catch { emit(emptyList()) }
            }
        }

    val uiState: StateFlow<LotsUiState> =
        combine(filter, read, sheet, message, detailRows) { chosen, data, open, shown, rows ->
            val content = if (data == null) {
                LotsContent.ReadError
            } else {
                LotsContent.Ready(readyOf(data, chosen).also { latest = it })
            }
            LotsUiState(chosen, content, open?.withMovements(rows), shown)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = LotsUiState(filter.value, sheet = sheet.value),
        )

    fun onAction(action: LotsAction) {
        when (action) {
            LotsAction.Retry -> attempts.update { it + 1 }

            is LotsAction.SelectFilter -> filter.value = action.filter

            is LotsAction.OpenLot -> sheet.value = LotSheet.Detail(action.lotId)

            LotsAction.CloseSheet -> sheet.value = null

            is LotsAction.StartFixExpiry -> {
                val lot = itemOf(action.lotId) ?: return
                sheet.value = LotSheet.FixExpiry(lot.lotId, lot.expiryDate)
            }

            is LotsAction.ExpiryChange -> sheet.update {
                (it as? LotSheet.FixExpiry)?.copy(date = action.date, failed = false) ?: it
            }

            is LotsAction.StartFixCode -> {
                val lot = itemOf(action.lotId) ?: return
                sheet.value = LotSheet.FixCode(lot.lotId, lot.code.orEmpty())
            }

            is LotsAction.CodeChange -> sheet.update {
                (it as? LotSheet.FixCode)?.copy(
                    text = action.text,
                    error = null,
                    repeatedIn = null,
                    failed = false,
                ) ?: it
            }

            LotsAction.OpenNotice -> {
                val days = latest?.noticeDays ?: return
                sheet.value = LotSheet.Notice(days.toString())
            }

            is LotsAction.NoticeChange -> sheet.update {
                val digits = action.text.filter { c -> c.isDigit() }.take(MAX_DAY_DIGITS)
                (it as? LotSheet.Notice)?.copy(text = digits, invalid = false, failed = false)
                    ?: it
            }

            LotsAction.SaveSheet -> save()

            LotsAction.MessageShown -> message.value = null

            // Las resuelve la ruta: salen de la pantalla.
            LotsAction.Back,
            LotsAction.OpenProducts,
            is LotsAction.TakeOut,
            is LotsAction.RegisterPurchase,
            is LotsAction.AdjustStock,
            -> Unit
        }
    }

    private fun itemOf(lotId: Long): LotItem? = latest?.lots?.firstOrNull { it.lotId == lotId }

    private fun save() {
        when (val open = sheet.value) {
            is LotSheet.FixExpiry -> if (!open.saving) saveExpiry(open)
            is LotSheet.FixCode -> if (!open.saving) saveCode(open)
            is LotSheet.Notice -> if (!open.saving) saveNotice(open)
            is LotSheet.Detail, null -> Unit
        }
    }

    private fun saveExpiry(open: LotSheet.FixExpiry) {
        val lot = itemOf(open.lotId) ?: return
        val code = lot.code ?: return
        sheet.value = open.copy(saving = true, failed = false)
        viewModelScope.launch {
            when (operations.correct(lot.lotId, code, open.date)) {
                CorrectLotResult.Saved -> saved(LotsMessage.LOT_SAVED)

                CorrectLotResult.NotFound -> sheet.value = null

                // El código no cambió: ya era válido y del lote.
                is CorrectLotResult.InvalidCode,
                is CorrectLotResult.CodeRepeated,
                is CorrectLotResult.Failed,
                -> sheet.update {
                    (it as? LotSheet.FixExpiry)?.copy(saving = false, failed = true)
                }
            }
        }
    }

    private fun saveCode(open: LotSheet.FixCode) {
        val lot = itemOf(open.lotId) ?: return
        sheet.value = open.copy(saving = true, failed = false, error = null)
        viewModelScope.launch {
            val result = operations.correct(lot.lotId, open.text, lot.expiryDate)
            sheet.update { current ->
                val form = current as? LotSheet.FixCode ?: return@update current
                when (result) {
                    CorrectLotResult.Saved -> null

                    CorrectLotResult.NotFound -> null

                    is CorrectLotResult.InvalidCode -> form.copy(
                        saving = false,
                        error = when (result.problem) {
                            is LotCodeCheck.TooLong -> LotCodeError.TOO_LONG
                            LotCodeCheck.Blank, is LotCodeCheck.Valid -> LotCodeError.BLANK
                        },
                    )

                    is CorrectLotResult.CodeRepeated -> form.copy(
                        saving = false,
                        error = LotCodeError.REPEATED,
                        repeatedIn = result.productName,
                    )

                    is CorrectLotResult.Failed -> form.copy(saving = false, failed = true)
                }
            }
            if (result == CorrectLotResult.Saved) message.value = LotsMessage.LOT_SAVED
        }
    }

    private fun saveNotice(open: LotSheet.Notice) {
        val days = open.text.toIntOrNull()
        if (days == null || days > NoticeSettings.MAX_EXPIRY_NOTICE_DAYS) {
            sheet.value = open.copy(invalid = true)
            return
        }
        sheet.value = open.copy(saving = true, failed = false)
        viewModelScope.launch {
            when (operations.setNoticeDays(days)) {
                SetExpiryNoticeDaysResult.Saved -> saved(LotsMessage.NOTICE_SAVED)

                SetExpiryNoticeDaysResult.OutOfRange ->
                    sheet.update { (it as? LotSheet.Notice)?.copy(saving = false, invalid = true) }

                is SetExpiryNoticeDaysResult.Failed ->
                    sheet.update { (it as? LotSheet.Notice)?.copy(saving = false, failed = true) }
            }
        }
    }

    private fun saved(shown: LotsMessage) {
        sheet.value = null
        message.value = shown
    }

    /** La hoja del detalle con sus movimientos; las demás, como están. */
    private fun LotSheet.withMovements(rows: List<StockMovementRecord>): LotSheet = when (this) {
        is LotSheet.Detail -> copy(
            movements = rows.take(MOVEMENT_ROWS).map {
                LotMovementItem(
                    it.id,
                    it.movement.type,
                    it.businessDate,
                    it.movement.quantity,
                    it.reason,
                )
            },
            originDate = rows.lastOrNull { it.movement.type == StockMovementType.PURCHASE }
                ?.businessDate,
        )

        is LotSheet.FixExpiry, is LotSheet.FixCode, is LotSheet.Notice -> this
    }

    private fun readyOf(data: Read, chosen: LotFilter): LotsReady {
        val calendar = (data.calendar as? BusinessCalendarResult.Ready)?.calendar
        return LotsReadyBuilder.build(
            data.overview,
            chosen,
            data.units,
            data.locations,
            calendar?.businessDateOf(operations.now()),
            calendar?.weekStartDay,
        )
    }

    /** Lo que se leyó de la base, junto. */
    private data class Read(
        val overview: LotsOverview,
        val units: Map<Long, MeasureUnit>,
        val locations: List<Location>,
        val calendar: BusinessCalendarResult?,
    )

    companion object {
        /** Cuántos movimientos muestra la hoja de un lote. */
        const val MOVEMENT_ROWS = 5

        /** Los días de aviso caben en cinco cifras: hasta cien años. */
        private const val MAX_DAY_DIGITS = 5

        /** Lo que sigue viva la lectura al rotar la pantalla, para no releer los lotes. */
        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/** Arma lo listo para mostrar a partir de lo leído. Aparte, para las vistas previas. */
internal object LotsReadyBuilder {
    fun build(
        overview: LotsOverview,
        filter: LotFilter,
        units: Map<Long, MeasureUnit>,
        locations: List<Location>,
        today: BusinessDate?,
        weekStartDay: DayOfWeek?,
    ): LotsReady {
        val entries = overview.entries
        val locationsById = locations.associateBy { it.id }
        val items = entries.associate { it.lot.id to itemOf(it, units, locationsById) }
        return LotsReady(
            hasLots = entries.any { !it.lot.isUnassigned },
            expiringCount = LotListRules.count(entries, LotFilter.EXPIRING),
            expiringValue = LotListRules.expiringValue(entries),
            groups = LotListRules.grouped(entries, filter).map { group ->
                LotGroupItem(
                    group.product.id,
                    group.product.details.name,
                    group.entries.map { items.getValue(it.lot.id) },
                )
            },
            noticeDays = overview.noticeDays,
            showsLocations = LocationRules.showsLocations(locations.size),
            today = today,
            weekStartDay = weekStartDay,
            lots = items.values.toList(),
        )
    }

    private fun itemOf(
        entry: LotEntry,
        units: Map<Long, MeasureUnit>,
        locations: Map<Long, Location>,
    ): LotItem = LotItem(
        lotId = entry.lot.id,
        productId = entry.product.id,
        productName = entry.product.details.name,
        code = entry.lot.code,
        unassigned = entry.lot.isUnassigned,
        expiryDate = entry.lot.expiryDate,
        expiry = lotExpiryOf(entry.due),
        expired = entry.due?.isExpired == true,
        onHand = entry.onHand,
        unit = units[entry.product.details.baseUnitId],
        // Solo se leen las ubicaciones vigentes: la parte de una archivada no se nombra, y el
        // total de arriba igual la cuenta.
        places = entry.byLocation.mapNotNull { held ->
            locations[held.locationId]?.let { LotPlace(it, held.onHand) }
        },
    )
}

package io.github.yesidrobayo.shopledger.ui.document

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.document.FiscalMarkStatus
import io.github.yesidrobayo.shopledger.domain.model.document.FiscalSale
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.document.FiscalMonth
import io.github.yesidrobayo.shopledger.domain.usecase.document.MarkFiscalResult
import io.github.yesidrobayo.shopledger.domain.usecase.document.ReferenceChange
import io.github.yesidrobayo.shopledger.domain.usecase.document.SavePortalLinkResult
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.YearMonth
import kotlinx.datetime.minusMonth
import kotlinx.datetime.plusMonth
import kotlinx.datetime.yearMonth

/**
 * Los pendientes de facturar: las ventas de un mes por marca, su hoja `Registro externo`, la
 * selección de varias y el enlace al sistema externo. Todo es local: nada espera a la red.
 *
 * - Abre en el mes de hoy, según la zona del negocio, y no deja pasar de él.
 * - Al cambiar de mes o de marca, lo anterior se conserva hasta tener lo nuevo, y la selección se
 *   vacía: no se marca algo que ya no se ve.
 * - Una venta marcada sale sola de la lista, porque la lista vuelve a leer.
 * - Un error al guardar nunca borra lo escrito, y la venta no cambia.
 *
 * @param observeMonth las ventas de un mes con una marca, con su resumen.
 * @param observeSale una venta activa con su marca, para la hoja abierta desde su detalle.
 * @param portalUrl el enlace que guardó la persona, o `null`.
 * @param calendar el calendario del negocio, para el mes de hoy.
 * @param now el instante actual, del reloj inyectado.
 * @param mark marca una o varias ventas en una sola transacción.
 * @param saveLink guarda el enlace, o lo quita si queda vacío.
 * @param initialSaleId la venta cuya hoja se abre al entrar, como desde su detalle.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FiscalViewModel(
    private val observeMonth: (YearMonth, FiscalMarkStatus) -> Flow<FiscalMonth>,
    private val observeSale: (Long) -> Flow<FiscalSale?>,
    portalUrl: Flow<String?>,
    calendar: Flow<BusinessCalendarResult>,
    private val now: () -> Instant,
    private val mark: suspend (List<Long>, FiscalMarkStatus, ReferenceChange) -> MarkFiscalResult,
    private val saveLink: suspend (String) -> SavePortalLinkResult,
    initialSaleId: Long? = null,
) : ViewModel() {
    private val state = MutableStateFlow(FiscalUiState())
    val uiState: StateFlow<FiscalUiState> = state.asStateFlow()

    /** El mes, la marca y el número de intento: cambiar cualquiera vuelve a leer. */
    private val query = MutableStateFlow<Query?>(null)

    /** La venta de la hoja abierta, para seguir sus datos mientras está abierta. */
    private val sheetSaleId = MutableStateFlow<Long?>(null)

    init {
        viewModelScope.launch {
            // Sin calendario legible, la pantalla lo trata como una zona que no conoce.
            calendar.catch {}.collect { result ->
                val ready = when (result) {
                    is BusinessCalendarResult.Ready -> result.calendar
                    is BusinessCalendarResult.UnknownZone -> null
                }
                val today = ready?.businessDateOf(now())
                state.update { it.copy(today = today) }
                if (today == null) {
                    state.update { it.copy(content = FiscalContent.UnknownZone) }
                } else if (query.value == null) {
                    query.value = Query(today.date.yearMonth, state.value.filter, attempt = 0)
                }
            }
        }
        viewModelScope.launch {
            // Sin enlace legible, no se ofrece abrirlo: la lista funciona igual.
            portalUrl.catch { emit(null) }.collect { url ->
                state.update { it.copy(portalUrl = url) }
            }
        }
        viewModelScope.launch {
            query.filterNotNull().flatMapLatest { current ->
                state.update { it.copy(month = current.month, filter = current.filter) }
                observeMonth(current.month, current.filter)
                    .map<FiscalMonth, FiscalContent> {
                        FiscalContent.Sales(it.sales, it.total, it.hasMarks)
                    }
                    .catch { emit(FiscalContent.ReadError) }
            }.collect { content -> state.update { it.copy(content = content) } }
        }
        viewModelScope.launch {
            sheetSaleId.flatMapLatest { saleId ->
                if (saleId == null) {
                    emptyFlow()
                } else {
                    observeSale(saleId).map { saleId to it }.catch { emit(saleId to null) }
                }
            }.collect { (saleId, sale) -> showSheetSale(saleId, sale) }
        }
        initialSaleId?.let(::openSheet)
    }

    fun onAction(action: FiscalAction) {
        when (action) {
            FiscalAction.PreviousMonth -> moveMonth(forward = false)

            FiscalAction.NextMonth -> moveMonth(forward = true)

            is FiscalAction.FilterChange -> {
                clearSelection()
                query.update { it?.copy(filter = action.filter) }
            }

            FiscalAction.Retry -> query.update { it?.copy(attempt = it.attempt + 1) }

            is FiscalAction.OpenSale -> if (state.value.selecting) {
                toggle(action.saleId)
            } else {
                openSheet(action.saleId)
            }

            is FiscalAction.ToggleSelection -> toggle(action.saleId)

            FiscalAction.ClearSelection -> clearSelection()

            is FiscalAction.MarkSelection -> markSelection(action.status)

            is FiscalAction.ReferenceChange -> editSheet {
                it.copy(reference = action.text, referenceTooLong = false)
            }

            is FiscalAction.MarkSale -> markSale(action.status)

            FiscalAction.CloseSheet -> closeSheet()

            FiscalAction.ConfigureLink -> state.update {
                it.copy(linkSheet = FiscalLinkSheet(text = it.portalUrl.orEmpty()))
            }

            is FiscalAction.LinkChange -> state.update {
                val sheet = it.linkSheet
                if (sheet == null || sheet.status == FiscalSheetStatus.WORKING) {
                    it
                } else {
                    it.copy(linkSheet = sheet.copy(text = action.text, error = null))
                }
            }

            FiscalAction.SaveLink -> saveLink()

            FiscalAction.CloseLinkSheet -> state.update { it.copy(linkSheet = null) }

            FiscalAction.NoAppForLink -> state.update {
                it.copy(message = FiscalMessage.NO_APP_FOR_LINK)
            }

            FiscalAction.MessageShown -> state.update { it.copy(message = null) }

            FiscalAction.Back,
            FiscalAction.ChooseZone,
            is FiscalAction.ViewSale,
            FiscalAction.OpenLink,
            FiscalAction.ShareSummary,
            -> Unit
        }
    }

    private fun moveMonth(forward: Boolean) {
        val today = state.value.today ?: return
        query.update { current ->
            current ?: return@update null
            val next = if (forward) current.month.plusMonth() else current.month.minusMonth()
            // No hay ventas en meses que todavía no empiezan.
            if (next > today.date.yearMonth) current else current.copy(month = next)
        }
        clearSelection()
    }

    private fun clearSelection() {
        state.update { it.copy(selection = emptySet(), bulkStatus = FiscalSheetStatus.IDLE) }
    }

    private fun toggle(saleId: Long) {
        state.update {
            val selection = if (saleId in it.selection) {
                it.selection - saleId
            } else {
                it.selection + saleId
            }
            it.copy(selection = selection, bulkStatus = FiscalSheetStatus.IDLE)
        }
    }

    private fun openSheet(saleId: Long) {
        val listed = (state.value.content as? FiscalContent.Sales)
            ?.sales
            ?.firstOrNull { it.saleId == saleId }
        state.update {
            it.copy(
                sheet = FiscalMarkSheet(
                    saleId = saleId,
                    sale = listed,
                    reference = listed?.externalReference.orEmpty(),
                ),
            )
        }
        sheetSaleId.value = saleId
    }

    /**
     * Lo leído de la venta de la hoja. La primera lectura trae la referencia guardada si la hoja
     * se abrió sin ella; después, lo escrito por la persona se conserva.
     */
    private fun showSheetSale(saleId: Long, sale: FiscalSale?) {
        editSheet { sheet ->
            if (sheet.saleId != saleId) return@editSheet sheet
            val firstRead = sheet.sale == null && !sheet.unavailable
            sheet.copy(
                sale = sale ?: sheet.sale,
                unavailable = sale == null,
                reference = if (firstRead && sheet.reference.isEmpty()) {
                    sale?.externalReference.orEmpty()
                } else {
                    sheet.reference
                },
            )
        }
    }

    private fun closeSheet() {
        sheetSaleId.value = null
        state.update { it.copy(sheet = null) }
    }

    private fun markSale(status: FiscalMarkStatus) {
        val sheet = state.value.sheet ?: return
        if (sheet.status == FiscalSheetStatus.WORKING) return
        editSheet { it.copy(status = FiscalSheetStatus.WORKING) }
        viewModelScope.launch {
            val result = mark(listOf(sheet.saleId), status, ReferenceChange.Write(sheet.reference))
            when (result) {
                is MarkFiscalResult.Marked, MarkFiscalResult.NothingSelected -> closeSheet()

                MarkFiscalResult.ReferenceTooLong -> editSheet {
                    it.copy(status = FiscalSheetStatus.IDLE, referenceTooLong = true)
                }

                MarkFiscalResult.SaleUnavailable -> editSheet {
                    it.copy(status = FiscalSheetStatus.IDLE, unavailable = true)
                }

                is MarkFiscalResult.Failed -> editSheet {
                    it.copy(status = FiscalSheetStatus.FAILED)
                }
            }
        }
    }

    private fun markSelection(status: FiscalMarkStatus) {
        val current = state.value
        if (current.selection.isEmpty() || current.bulkStatus == FiscalSheetStatus.WORKING) return
        state.update { it.copy(bulkStatus = FiscalSheetStatus.WORKING) }
        viewModelScope.launch {
            val ids = current.selection.sorted()
            when (mark(ids, status, ReferenceChange.Keep)) {
                is MarkFiscalResult.Marked, MarkFiscalResult.NothingSelected -> state.update {
                    it.copy(selection = emptySet(), bulkStatus = FiscalSheetStatus.IDLE)
                }

                // Sin referencia no hay largo que revisar; una venta anulada mientras tanto, o una
                // falla, dejan la selección como estaba para intentarlo de nuevo.
                MarkFiscalResult.ReferenceTooLong,
                MarkFiscalResult.SaleUnavailable,
                is MarkFiscalResult.Failed,
                -> state.update { it.copy(bulkStatus = FiscalSheetStatus.FAILED) }
            }
        }
    }

    private fun saveLink() {
        val sheet = state.value.linkSheet ?: return
        if (sheet.status == FiscalSheetStatus.WORKING) return
        state.update { it.copy(linkSheet = sheet.copy(status = FiscalSheetStatus.WORKING)) }
        viewModelScope.launch {
            val next = when (saveLink(sheet.text)) {
                is SavePortalLinkResult.Saved -> null
                SavePortalLinkResult.NotHttps -> sheet.copy(error = FiscalLinkError.NOT_HTTPS)
                SavePortalLinkResult.TooLong -> sheet.copy(error = FiscalLinkError.TOO_LONG)
                is SavePortalLinkResult.Failed -> sheet.copy(status = FiscalSheetStatus.FAILED)
            }
            state.update { it.copy(linkSheet = next) }
        }
    }

    private fun editSheet(change: (FiscalMarkSheet) -> FiscalMarkSheet) {
        state.update { current ->
            current.sheet?.let { current.copy(sheet = change(it)) } ?: current
        }
    }

    private data class Query(val month: YearMonth, val filter: FiscalMarkStatus, val attempt: Int)
}

package io.github.yesidrobayo.shopledger.ui.sale

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.document.SaleFiscalMark
import io.github.yesidrobayo.shopledger.domain.model.returns.SaleReturnLine
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleDetail
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleReceipt
import io.github.yesidrobayo.shopledger.domain.rule.mulDivRound
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.rule.sale.SaleResults
import io.github.yesidrobayo.shopledger.domain.usecase.returns.VoidSaleReturnResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.VoidSaleResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * El detalle de una venta y su anulación.
 *
 * El detalle observa la base: al anular, la venta se ve anulada sin reabrirla, con su motivo y su
 * fecha. Anular abre una hoja con el motivo, una nota opcional y lo que pasará; confirmar corre la
 * anulación en una sola transacción, y si falla la venta sigue activa y se puede reintentar.
 *
 * El costo, la ganancia y el margen solo aparecen aquí, después de vender: la pantalla de venta
 * nunca los muestra, porque quien compra puede estar mirando.
 *
 * Anular y corregir pregunta antes, porque la venta queda anulada aunque la nueva no se guarde.
 * Anula con el motivo de error al registrar, que es lo que se corrige, y solo entonces abre la venta
 * nueva con sus datos.
 *
 * El comprobante que se comparte sale de un modelo sin costo, ganancia, comisión ni nota.
 *
 * Una devolución se anula desde su hoja, con motivo y nota como la venta. Si es parte de un
 * cambio cuya venta nueva sigue activa, la hoja no lo ofrece: el cambio se deshace anulando antes
 * la venta nueva. Al anularla, lo devuelto se recalcula sin ella, y sin devoluciones activas la
 * venta se vuelve a poder anular.
 *
 * @param voidSale anula la venta con el motivo y la nota.
 * @param observeBusinessName el nombre del negocio, que encabeza el comprobante si existe.
 * @param loadReturnLines las líneas de una devolución, para su hoja.
 * @param voidReturn anula una devolución con el motivo y la nota.
 * @param isSaleActive si una venta sigue activa: la venta nueva de un cambio, para su hoja.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SaleDetailViewModel(
    private val saleId: Long,
    private val observeDetail: (Long) -> Flow<SaleDetail?>,
    private val observeCalendar: () -> Flow<BusinessCalendarResult>,
    private val observeUnits: () -> Flow<List<MeasureUnit>>,
    private val voidSale: suspend (Long, VoidReason, String?) -> VoidSaleResult,
    computation: CoroutineDispatcher,
    private val observeBusinessName: () -> Flow<String?> = { flowOf(null) },
    private val loadReturnLines: suspend (Long) -> List<SaleReturnLine> = { emptyList() },
    private val voidReturn: suspend (Long, VoidReason, String?) -> VoidSaleReturnResult =
        { _, _, _ -> VoidSaleReturnResult.NotFound },
    private val isSaleActive: suspend (Long) -> Boolean = { true },
    private val observeFiscalMark: (Long) -> Flow<SaleFiscalMark?> = { flowOf(null) },
) : ViewModel() {
    private val attempts = MutableStateFlow(0)
    private val overlay = MutableStateFlow(Overlay())

    private val content: Flow<Content> = attempts.flatMapLatest {
        combine(
            observeDetail(saleId),
            observeCalendar(),
            observeUnits(),
            observeBusinessName(),
            observeFiscalMark(saleId),
        ) { detail, calendar, units, businessName, fiscalMark ->
            if (detail == null) {
                Content.Missing
            } else {
                val ready = (calendar as? BusinessCalendarResult.Ready)?.calendar
                val view = viewOf(detail, ready, units.associateBy { it.id }, businessName)
                Content.Found(view.copy(fiscalMark = fiscalMark))
            }
        }.flowOn(computation)
            // Una falla de lectura no cierra la pantalla: muestra el error y deja reintentar.
            .catch { emit(Content.Failed) }
    }

    val uiState: StateFlow<SaleDetailUiState> = combine(content, overlay) { found, shown ->
        when (found) {
            Content.Missing -> SaleDetailUiState.NotFound

            Content.Failed -> SaleDetailUiState.ReadError

            is Content.Found -> SaleDetailUiState.Ready(
                sale = found.view,
                // Una venta ya anulada no tiene hoja de anular, aunque se haya quedado abierta.
                sheet = shown.sheet.takeIf { found.view.canVoid },
                status = shown.status,
                voided = shown.voided,
                correcting = shown.correcting && found.view.canVoid,
                corrected = shown.corrected,
                correctFailed = shown.correctFailed,
                returnSheet = shown.returnId?.let { id ->
                    found.view.returns.firstOrNull { it.returnId == id }?.let { row ->
                        SaleReturnSheet(
                            row = row,
                            lines = shown.returnLines?.let { linesOf(found.view, it) },
                            replacementActive = shown.replacementActive,
                            // Una devolución ya anulada no tiene paso de anular.
                            void = shown.returnVoid.takeIf { !row.voided },
                            voidStatus = shown.returnVoidStatus,
                        )
                    }
                },
                returnVoided = shown.returnVoided,
            )
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        SaleDetailUiState.Loading,
    )

    fun onAction(action: SaleDetailAction) {
        when (action) {
            SaleDetailAction.OpenVoid -> overlay.update {
                it.copy(sheet = VoidSheet(), status = VoidStatus.IDLE)
            }

            is SaleDetailAction.ReasonChange -> editSheet { it.copy(reason = action.reason) }

            is SaleDetailAction.NoteChange -> editSheet { it.copy(note = action.note) }

            SaleDetailAction.ConfirmVoid -> confirmVoid()

            SaleDetailAction.CloseSheet -> overlay.update { it.copy(sheet = null) }

            SaleDetailAction.Retry -> attempts.update { it + 1 }

            SaleDetailAction.VoidedShown -> overlay.update { it.copy(voided = false) }

            SaleDetailAction.OpenCorrect -> overlay.update {
                it.copy(correcting = true, sheet = null, status = VoidStatus.IDLE)
            }

            SaleDetailAction.DismissCorrect -> overlay.update {
                if (it.status == VoidStatus.WORKING) it else it.copy(correcting = false)
            }

            SaleDetailAction.ConfirmCorrect -> confirmCorrect()

            SaleDetailAction.CorrectShown -> overlay.update {
                it.copy(corrected = null, correctFailed = false)
            }

            // Compartir lo resuelve la navegación, con el comprobante del estado.
            SaleDetailAction.Share -> Unit

            // Lo resuelve la ruta: abre el comprobante.
            SaleDetailAction.OpenReceipt -> Unit

            // Volver lo resuelve la navegación.
            SaleDetailAction.Back -> Unit

            // Abrir la devolución es navegación: la resuelve la ruta.
            SaleDetailAction.OpenReturn -> Unit

            // El registro externo es otra pantalla: lo resuelve la ruta.
            SaleDetailAction.OpenFiscalMark -> Unit

            is SaleDetailAction.OpenReturnDetail -> openReturn(action.returnId)

            SaleDetailAction.CloseReturnDetail -> overlay.update {
                if (it.returnVoidStatus == VoidStatus.WORKING) it else it.closedReturn()
            }

            SaleDetailAction.OpenReturnVoid -> overlay.update {
                if (it.returnId == null) {
                    it
                } else {
                    it.copy(returnVoid = VoidSheet(), returnVoidStatus = VoidStatus.IDLE)
                }
            }

            is SaleDetailAction.ReturnVoidReasonChange ->
                editReturnVoid { it.copy(reason = action.reason) }

            is SaleDetailAction.ReturnVoidNoteChange -> editReturnVoid {
                it.copy(note = action.note)
            }

            SaleDetailAction.ConfirmReturnVoid -> confirmReturnVoid()

            SaleDetailAction.ReturnVoidedShown -> overlay.update { it.copy(returnVoided = false) }
        }
    }

    /**
     * Abre la hoja de la devolución y lee sus líneas, que nunca cambian, y si la venta nueva de su
     * cambio sigue activa.
     */
    private fun openReturn(returnId: Long) {
        overlay.update { it.closedReturn().copy(returnId = returnId) }
        val replacementId = (uiState.value as? SaleDetailUiState.Ready)?.sale?.returns
            ?.firstOrNull { it.returnId == returnId }?.replacementSaleId
        viewModelScope.launch {
            // Sin poder leer las líneas, la hoja muestra igual la cabecera.
            val lines = flow { emit(loadReturnLines(returnId)) }
                .catch { emit(emptyList()) }
                .first()
            // Sin poder leer la venta nueva, se supone activa: la anulación la revisa de nuevo.
            val replacementActive = replacementId != null &&
                flow { emit(isSaleActive(replacementId)) }.catch { emit(true) }.first()
            overlay.update {
                if (it.returnId == returnId) {
                    it.copy(returnLines = lines, replacementActive = replacementActive)
                } else {
                    it
                }
            }
        }
    }

    /**
     * Anula la devolución abierta con el motivo y la nota de su paso. Al anularse se cierra la
     * hoja y el detalle, que observa la base, la muestra anulada; si es parte de un cambio
     * vigente, la hoja lo explica; si falla, sigue activa y se puede reintentar.
     */
    private fun confirmReturnVoid() {
        val shown = overlay.value
        val returnId = shown.returnId ?: return
        val sheet = shown.returnVoid ?: return
        if (shown.returnVoidStatus == VoidStatus.WORKING || sheet.noteTooLong) return
        overlay.update { it.copy(returnVoidStatus = VoidStatus.WORKING) }
        viewModelScope.launch {
            val note = sheet.note.trim().takeIf { it.isNotEmpty() }
            val result = voidReturn(returnId, sheet.reason, note)
            overlay.update {
                when (result) {
                    // Ya anulada por otro camino: el detalle la muestra así, sin mensaje.
                    VoidSaleReturnResult.Voided, VoidSaleReturnResult.AlreadyVoided -> {
                        val announced = result == VoidSaleReturnResult.Voided
                        it.closedReturn().copy(returnVoided = announced)
                    }

                    is VoidSaleReturnResult.PartOfExchange -> it.copy(
                        returnVoid = null,
                        returnVoidStatus = VoidStatus.IDLE,
                        replacementActive = true,
                    )

                    VoidSaleReturnResult.NotFound,
                    VoidSaleReturnResult.ReasonNotAllowed,
                    VoidSaleReturnResult.NoteTooLong,
                    is VoidSaleReturnResult.UnknownTimeZone,
                    VoidSaleReturnResult.LedgerDamaged,
                    is VoidSaleReturnResult.StorageFailed,
                    -> it.copy(returnVoidStatus = VoidStatus.FAILED)

                    is VoidSaleReturnResult.LotAlreadyUsed ->
                        it.copy(returnVoidStatus = VoidStatus.LOT_USED)
                }
            }
        }
    }

    private fun editReturnVoid(change: (VoidSheet) -> VoidSheet) = overlay.update { shown ->
        val sheet = shown.returnVoid ?: return@update shown
        if (shown.returnVoidStatus == VoidStatus.WORKING) return@update shown
        shown.copy(returnVoid = change(sheet), returnVoidStatus = VoidStatus.IDLE)
    }

    /** Las líneas de una devolución con el nombre y la unidad de su línea vendida. */
    private fun linesOf(
        view: SaleDetailView,
        lines: List<SaleReturnLine>,
    ): List<SaleReturnSheetLine> {
        val sold = view.lines.associateBy { it.lineId }
        return lines.mapNotNull { line ->
            val row = sold[line.content.saleLineId] ?: return@mapNotNull null
            SaleReturnSheetLine(
                name = row.name,
                quantity = inLineUnit(line.content.quantity, row.factor),
                unit = row.unit,
                total = line.content.lineTotal,
                restock = line.content.restock,
            )
        }
    }

    private fun confirmVoid() {
        val shown = overlay.value
        val sheet = shown.sheet ?: return
        if (shown.status == VoidStatus.WORKING || sheet.noteTooLong) return
        overlay.update { it.copy(status = VoidStatus.WORKING) }
        viewModelScope.launch {
            val note = sheet.note.trim().takeIf { it.isNotEmpty() }
            when (voidSale(saleId, sheet.reason, note)) {
                // Ya anulada por otro camino también deja la venta como la persona quería.
                VoidSaleResult.Voided, VoidSaleResult.AlreadyVoided ->
                    overlay.update { Overlay(voided = true) }

                else -> overlay.update { it.copy(status = VoidStatus.FAILED) }
            }
        }
    }

    private fun confirmCorrect() {
        val shown = overlay.value
        if (!shown.correcting || shown.status == VoidStatus.WORKING) return
        overlay.update { it.copy(status = VoidStatus.WORKING) }
        viewModelScope.launch {
            when (voidSale(saleId, VoidReason.ENTRY_ERROR, null)) {
                // Ya anulada por otro camino también deja la venta lista para corregirla.
                VoidSaleResult.Voided, VoidSaleResult.AlreadyVoided ->
                    overlay.update { Overlay(corrected = saleId) }

                else -> overlay.update {
                    it.copy(correcting = false, status = VoidStatus.IDLE, correctFailed = true)
                }
            }
        }
    }

    private fun editSheet(change: (VoidSheet) -> VoidSheet) = overlay.update { shown ->
        val sheet = shown.sheet ?: return@update shown
        shown.copy(sheet = change(sheet), status = VoidStatus.IDLE)
    }

    private fun viewOf(
        detail: SaleDetail,
        calendar: BusinessCalendar?,
        units: Map<Long, MeasureUnit>,
        businessName: String?,
    ): SaleDetailView {
        val sale = detail.sale
        val content = sale.content
        return SaleDetailView(
            saleId = sale.id,
            total = content.total,
            businessDate = content.businessDate,
            occurredAt = content.occurredAt,
            zone = calendar?.zone,
            voiding = sale.voiding?.let {
                SaleVoidView(it.reason, calendar?.businessDateOf(it.voidedAt), it.note)
            },
            hasActiveReturns = detail.hasActiveReturns,
            canReturn = detail.canBeReturned,
            returns = detail.returns.map { saleReturn ->
                val header = saleReturn.content
                SaleReturnRow(
                    returnId = saleReturn.id,
                    date = header.businessDate,
                    resolution = header.resolution,
                    total = header.totalRefund,
                    voided = saleReturn.voiding != null,
                    note = header.note,
                    replacementSaleId = header.replacementSaleId,
                )
            },
            lines = detail.lines.map { line ->
                val row = line.content
                val returned = detail.returned[line.id]?.quantity ?: QuantityMilli.ZERO
                SaleDetailLine(
                    lineId = line.id,
                    name = row.productNameSnapshot,
                    quantity = row.enteredQuantity,
                    unit = units[row.unitId],
                    unitPrice = row.unitPrice,
                    discount = row.lineDiscount,
                    total = row.lineTotal,
                    archived = row.productId in detail.archivedProductIds,
                    returned = inLineUnit(returned, row.unitFactor),
                    left = inLineUnit(detail.remainingOf(line), row.unitFactor),
                    factor = row.unitFactor,
                )
            },
            payments = detail.payments.map { payment ->
                val row = payment.content
                SaleDetailPayment(
                    paymentId = payment.id,
                    methodName = row.methodNameSnapshot,
                    amount = row.amount,
                    settlesOn = row.settlementDate.takeIf { it > content.businessDate },
                    fee = row.feeAmount,
                    netReceived = row.netReceived,
                    foreign = row.foreignTender,
                )
            },
            results = SaleResults.of(sale, detail.payments),
            charges = SaleDetailCharges(
                taxes = taxesOf(detail),
                orderAdjustment = content.orderAdjustment,
                tip = content.tipAmount,
                cashRounding = content.cashRoundingAmount,
            ),
            note = content.note,
            // Una venta anulada no se comparte: el comprobante diría que se cobró.
            receipt = SaleReceipt.of(detail, businessName).takeIf { sale.voiding == null },
            units = units,
        )
    }

    /**
     * El impuesto de cada tasa, con el nombre que tenía al vender, en el orden de su primera línea.
     * Una línea sin impuesto no aporta fila.
     */
    private fun taxesOf(detail: SaleDetail): List<SaleDetailTax> = detail.lines
        .map { it.content }
        .filter { it.taxAmount > Money.ZERO }
        .groupBy { it.taxNameSnapshot }
        .map { (name, lines) ->
            SaleDetailTax(name, lines.fold(Money.ZERO) { sum, line -> sum + line.taxAmount })
        }

    /**
     * [quantity], que va en la unidad base, en la unidad en que se vendió la línea: [factor] unidades
     * base por cada una. Una cifra que no cabría se muestra en la base.
     */
    private fun inLineUnit(quantity: QuantityMilli, factor: QuantityMilli): QuantityMilli {
        if (factor == QuantityMilli(MILLI_PER_UNIT)) return quantity
        val scaled = mulDivRound(quantity.milli, MILLI_PER_UNIT, factor.milli)
        return (scaled as? Checked.Value)?.value?.let(::QuantityMilli) ?: quantity
    }

    private sealed interface Content {
        data object Missing : Content

        data object Failed : Content

        data class Found(val view: SaleDetailView) : Content
    }

    private data class Overlay(
        val sheet: VoidSheet? = null,
        val status: VoidStatus = VoidStatus.IDLE,
        val voided: Boolean = false,
        val correcting: Boolean = false,
        val corrected: Long? = null,
        val correctFailed: Boolean = false,
        val returnId: Long? = null,
        val returnLines: List<SaleReturnLine>? = null,
        val replacementActive: Boolean = false,
        val returnVoid: VoidSheet? = null,
        val returnVoidStatus: VoidStatus = VoidStatus.IDLE,
        val returnVoided: Boolean = false,
    ) {
        /** Sin la hoja de la devolución, ni su paso de anular. */
        fun closedReturn(): Overlay = copy(
            returnId = null,
            returnLines = null,
            replacementActive = false,
            returnVoid = null,
            returnVoidStatus = VoidStatus.IDLE,
        )
    }

    private companion object {
        /** Milésimas en una unidad entera. */
        const val MILLI_PER_UNIT = 1_000L

        /** Lo que sigue viva la lectura al rotar la pantalla. */
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

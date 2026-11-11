package io.github.yesidrobayo.shopledger.ui.sale

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleDetail
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.rule.sale.SaleResults
import io.github.yesidrobayo.shopledger.domain.usecase.sale.VoidSaleResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
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
 * @param voidSale anula la venta con el motivo y la nota.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SaleDetailViewModel(
    private val saleId: Long,
    private val observeDetail: (Long) -> Flow<SaleDetail?>,
    private val observeCalendar: () -> Flow<BusinessCalendarResult>,
    private val observeUnits: () -> Flow<List<MeasureUnit>>,
    private val voidSale: suspend (Long, VoidReason, String?) -> VoidSaleResult,
    computation: CoroutineDispatcher,
) : ViewModel() {
    private val attempts = MutableStateFlow(0)
    private val overlay = MutableStateFlow(Overlay())

    private val content: Flow<Content> = attempts.flatMapLatest {
        combine(observeDetail(saleId), observeCalendar(), observeUnits()) {
                detail,
                calendar,
                units,
            ->
            if (detail == null) {
                Content.Missing
            } else {
                val ready = (calendar as? BusinessCalendarResult.Ready)?.calendar
                Content.Found(viewOf(detail, ready, units.associateBy { it.id }))
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

            // Volver lo resuelve la navegación.
            SaleDetailAction.Back -> Unit
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

    private fun editSheet(change: (VoidSheet) -> VoidSheet) = overlay.update { shown ->
        val sheet = shown.sheet ?: return@update shown
        shown.copy(sheet = change(sheet), status = VoidStatus.IDLE)
    }

    private fun viewOf(
        detail: SaleDetail,
        calendar: BusinessCalendar?,
        units: Map<Long, MeasureUnit>,
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
            lines = detail.lines.map { line ->
                val row = line.content
                SaleDetailLine(
                    lineId = line.id,
                    name = row.productNameSnapshot,
                    quantity = row.enteredQuantity,
                    unit = units[row.unitId],
                    unitPrice = row.unitPrice,
                    discount = row.lineDiscount,
                    total = row.lineTotal,
                    archived = row.productId in detail.archivedProductIds,
                )
            },
            payments = detail.payments.map { payment ->
                val row = payment.content
                SaleDetailPayment(
                    paymentId = payment.id,
                    methodName = row.methodNameSnapshot,
                    amount = row.amount,
                    settlesOn = row.settlementDate.takeIf { it > content.businessDate },
                )
            },
            results = SaleResults.of(sale, detail.payments),
            note = content.note,
        )
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
    )

    private companion object {
        /** Lo que sigue viva la lectura al rotar la pantalla. */
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

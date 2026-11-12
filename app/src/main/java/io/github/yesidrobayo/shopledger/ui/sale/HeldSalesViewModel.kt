package io.github.yesidrobayo.shopledger.ui.sale

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.sale.HeldSale
import io.github.yesidrobayo.shopledger.domain.model.sale.LineDiscount
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleCatalog
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.rule.sale.CartPricing
import io.github.yesidrobayo.shopledger.domain.rule.sale.HeldSaleRules
import io.github.yesidrobayo.shopledger.domain.rule.sale.SaleCart
import io.github.yesidrobayo.shopledger.domain.usecase.sale.HeldSaleChangeResult
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Las ventas en espera: atender a otra persona a mitad de una venta sin perder lo escrito.
 *
 * - **No son ventas.** Su total es estimado, con los precios vigentes, y nada de esto toca stock,
 *   dinero ni reportes.
 * - **Retomar no borra.** La espera sigue guardada hasta que la venta se confirme o se descarte. Si
 *   hay otra venta en edición, se pregunta antes: esa pasa a espera y se retoma la elegida.
 * - La venta que se edita ahora no se lista: ya está a la vista.
 *
 * @param saleInProgress si la venta nueva abierta tiene líneas.
 * @param editingHeldSaleId la espera de la que viene la venta nueva abierta, si viene de una.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HeldSalesViewModel(
    private val heldSales: () -> Flow<List<HeldSale>>,
    private val catalog: () -> Flow<SaleCatalog>,
    private val calendar: () -> Flow<BusinessCalendarResult>,
    private val rename: suspend (Long, String?) -> HeldSaleChangeResult,
    private val discard: suspend (Long) -> HeldSaleChangeResult,
    private val saleInProgress: () -> Boolean,
    private val editingHeldSaleId: () -> Long?,
    computation: CoroutineDispatcher,
) : ViewModel() {
    private val overlay = MutableStateFlow(HeldSalesUiState())
    private val attempts = MutableStateFlow(0)

    private val content: Flow<HeldSalesContent> = attempts
        .flatMapLatest {
            combine(heldSales(), catalog(), calendar()) { sales, current, zone ->
                contentOf(sales, current, (zone as? BusinessCalendarResult.Ready)?.calendar?.zone)
            }.catch { emit(HeldSalesContent.ReadError) }
        }.flowOn(computation)

    val uiState: StateFlow<HeldSalesUiState> = combine(content, overlay) { listed, shown ->
        shown.copy(content = listed)
    }.flowOn(computation)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            HeldSalesUiState(),
        )

    fun onAction(action: HeldSalesAction) {
        when (action) {
            is HeldSalesAction.Resume -> resume(action.heldSaleId)

            HeldSalesAction.ConfirmSwap -> confirmSwap()

            is HeldSalesAction.AskDiscard ->
                overlay.update { it.copy(dialog = HeldSalesDialog.Discard(action.heldSaleId)) }

            HeldSalesAction.ConfirmDiscard -> confirmDiscard()

            is HeldSalesAction.OpenRename -> openRename(action.heldSaleId)

            is HeldSalesAction.RenameChange -> renameText(action.text)

            HeldSalesAction.ConfirmRename -> confirmRename()

            HeldSalesAction.CloseRename -> overlay.update { it.copy(rename = null) }

            HeldSalesAction.DismissDialog -> overlay.update { it.copy(dialog = null) }

            HeldSalesAction.RetryRead -> attempts.update { it + 1 }

            HeldSalesAction.NoticeShown -> overlay.update { it.copy(notice = null) }

            HeldSalesAction.ResumeShown -> overlay.update { it.copy(resume = null) }

            // Ir a vender y volver son navegación: los resuelve la ruta.
            HeldSalesAction.GoSell, HeldSalesAction.Back -> Unit
        }
    }

    private fun resume(heldSaleId: Long) {
        if (saleInProgress()) {
            overlay.update { it.copy(dialog = HeldSalesDialog.Swap(heldSaleId)) }
        } else {
            overlay.update { it.copy(resume = heldSaleId) }
        }
    }

    private fun confirmSwap() {
        val swap = overlay.value.dialog as? HeldSalesDialog.Swap ?: return
        overlay.update { it.copy(dialog = null, resume = swap.heldSaleId) }
    }

    private fun confirmDiscard() {
        val chosen = overlay.value.dialog as? HeldSalesDialog.Discard ?: return
        overlay.update { it.copy(dialog = null) }
        viewModelScope.launch { report(discard(chosen.heldSaleId)) }
    }

    private fun openRename(heldSaleId: Long) {
        val label = (uiState.value.content as? HeldSalesContent.Sales)
            ?.rows?.firstOrNull { it.id == heldSaleId }?.label.orEmpty()
        overlay.update { it.copy(rename = RenameEditor(heldSaleId, label)) }
    }

    private fun renameText(text: String) = overlay.update { shown ->
        val editor = shown.rename ?: return@update shown
        val tooLong = !HeldSaleRules.fits(HeldSaleRules.labelOf(text))
        shown.copy(rename = editor.copy(text = text, tooLong = tooLong))
    }

    private fun confirmRename() {
        val editor = overlay.value.rename ?: return
        if (editor.tooLong || editor.working) return
        overlay.update { it.copy(rename = editor.copy(working = true)) }
        viewModelScope.launch {
            val result = rename(editor.heldSaleId, editor.text)
            // Si no se pudo guardar, la hoja sigue abierta con lo escrito, y el aviso lo dice.
            val keep = result is HeldSaleChangeResult.StorageFailed
            overlay.update { it.copy(rename = if (keep) editor.copy(working = false) else null) }
            report(result)
        }
    }

    /** Un cambio que no se pudo hacer se dice; lo que salió bien ya se ve en la lista. */
    private fun report(result: HeldSaleChangeResult) {
        val failed = when (result) {
            HeldSaleChangeResult.Changed -> false

            // Ya no existe: la lista ya no la muestra, y no hay nada que decir.
            HeldSaleChangeResult.NotFound -> false

            HeldSaleChangeResult.LabelTooLong, is HeldSaleChangeResult.StorageFailed -> true
        }
        if (failed) overlay.update { it.copy(notice = HeldSalesNotice.CHANGE_FAILED) }
    }

    private fun contentOf(
        sales: List<HeldSale>,
        catalog: SaleCatalog,
        zone: BusinessTimeZone?,
    ): HeldSalesContent {
        val editing = editingHeldSaleId()
        val listed = sales.filter { it.id != editing }
        if (listed.isEmpty()) return HeldSalesContent.Empty
        val products = catalog.products.associateBy { it.id }
        val fractions = SaleContentBuilder.fractionUnits(catalog)
        val pricing = CartPricing(
            taxMode = catalog.taxes.mode,
            rates = catalog.taxRates,
            defaultRateId = catalog.taxes.defaultTaxRateId,
            methodAdjustment = BasisPoints.ZERO,
        )
        val rows = listed.map { sale ->
            // Un producto archivado o eliminado no se vende: no cuenta en el total estimado.
            val lines = sale.lines.filter { it.productId in products }.map {
                SaleLineRequest(
                    productId = it.productId,
                    quantity = it.quantity,
                    unitPrice = it.unitPrice,
                    discount = if (it.discount == Money.ZERO) {
                        LineDiscount.None
                    } else {
                        LineDiscount.Amount(it.discount)
                    },
                    unitId = it.unitId,
                )
            }
            val preview = SaleCart.preview(lines, products, fractions, pricing, catalog.conversions)
            HeldSaleRow(
                id = sale.id,
                label = sale.label,
                itemCount = preview.itemCount,
                estimatedTotal = preview.total,
                heldSince = sale.createdAt,
            )
        }
        return HeldSalesContent.Sales(rows, zone)
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

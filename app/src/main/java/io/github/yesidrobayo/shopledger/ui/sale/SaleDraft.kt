package io.github.yesidrobayo.shopledger.ui.sale

import androidx.lifecycle.SavedStateHandle
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.sale.LineDiscount
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest

/**
 * La venta a medio armar: lo que la persona eligió y nada más. No se escribe en la base hasta
 * confirmar; vive en el estado guardado de la pantalla, que sobrevive a una llamada, a cambiar de
 * app y a que Android cierre el proceso.
 *
 * @param methodId el método elegido, o `null` si la persona no tocó ninguno: entonces se cobra con
 *   el primero que se ofrece.
 * @param date la fecha elegida, o `null` para hoy.
 * @param awaitingProductAfter con un producto creado desde la búsqueda, el mayor id que había al
 *   salir a crearlo: el primero que aparezca con un id mayor se agrega a la venta al volver.
 * @param prefilled si ya se agregó el producto con que se abrió la venta, para no agregarlo dos veces
 *   al recrear la pantalla.
 * @param heldSaleId la venta en espera de la que se retomó esta, o `null`.
 * @param heldLabel el nombre que tenía en espera, para volver a ponerla en espera con él.
 */
data class SaleDraft(
    val lines: List<SaleLineRequest> = emptyList(),
    val methodId: Long? = null,
    val date: BusinessDate? = null,
    val note: String = "",
    val awaitingProductAfter: Long? = null,
    val prefilled: Boolean = false,
    val heldSaleId: Long? = null,
    val heldLabel: String? = null,
)

/**
 * Guarda la venta en edición en el estado guardado, con tipos que caben en un `Bundle`: las líneas
 * van como seis números por línea. Solo números y lo que la persona escribió en la nota.
 */
internal object SaleDraftStore {
    private const val LINES = "sale.lines"
    private const val METHOD = "sale.method"
    private const val DATE = "sale.date"
    private const val NOTE = "sale.note"
    private const val AWAITING = "sale.awaiting"
    private const val PREFILLED = "sale.prefilled"
    private const val HELD = "sale.held"
    private const val HELD_LABEL = "sale.heldLabel"

    private const val FIELDS = 6
    private const val NO_DISCOUNT = 0L
    private const val AMOUNT = 1L
    private const val PERCENT = 2L

    fun read(state: SavedStateHandle): SaleDraft = SaleDraft(
        lines = state.get<LongArray>(LINES)?.let(::decode).orEmpty(),
        methodId = state.get<Long>(METHOD),
        date = state.get<String>(DATE)?.let(BusinessDate::parse),
        note = state.get<String>(NOTE).orEmpty(),
        awaitingProductAfter = state.get<Long>(AWAITING),
        prefilled = state.get<Boolean>(PREFILLED) ?: false,
        heldSaleId = state.get<Long>(HELD),
        heldLabel = state.get<String>(HELD_LABEL),
    )

    fun write(state: SavedStateHandle, draft: SaleDraft) {
        state[LINES] = encode(draft.lines)
        state[METHOD] = draft.methodId
        state[DATE] = draft.date?.toString()
        state[NOTE] = draft.note
        state[AWAITING] = draft.awaitingProductAfter
        state[PREFILLED] = draft.prefilled
        state[HELD] = draft.heldSaleId
        state[HELD_LABEL] = draft.heldLabel
    }

    private fun encode(lines: List<SaleLineRequest>): LongArray {
        val values = LongArray(lines.size * FIELDS)
        lines.forEachIndexed { index, line ->
            val base = index * FIELDS
            values[base] = line.productId
            values[base + 1] = line.quantity.milli
            values[base + 2] = if (line.unitPrice == null) 0L else 1L
            values[base + 3] = line.unitPrice?.minor ?: 0L
            val (kind, value) = when (val discount = line.discount) {
                LineDiscount.None -> NO_DISCOUNT to 0L
                is LineDiscount.Amount -> AMOUNT to discount.amount.minor
                is LineDiscount.Percent -> PERCENT to discount.rate.bp
            }
            values[base + 4] = kind
            values[base + 5] = value
        }
        return values
    }

    /** Lo guardado; un arreglo que no tiene la forma esperada se descarta entero. */
    private fun decode(values: LongArray): List<SaleLineRequest> {
        if (values.size % FIELDS != 0) return emptyList()
        return (0 until values.size / FIELDS).map { index ->
            val base = index * FIELDS
            SaleLineRequest(
                productId = values[base],
                quantity = QuantityMilli(values[base + 1]),
                unitPrice = if (values[base + 2] == 1L) Money(values[base + 3]) else null,
                discount = when (values[base + 4]) {
                    AMOUNT -> LineDiscount.Amount(Money(values[base + 5]))
                    PERCENT -> LineDiscount.Percent(BasisPoints(values[base + 5]))
                    else -> LineDiscount.None
                },
            )
        }
    }
}

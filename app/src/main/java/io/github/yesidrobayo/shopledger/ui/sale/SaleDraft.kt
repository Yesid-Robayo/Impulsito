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
 * @param customerId el cliente con que se abrió la venta, como desde `Vender a este cliente`: el
 *   cobro lo trae elegido.
 * @param priceListId la lista de precios elegida, o `null` para la inicial. Al abrir la venta desde
 *   un cliente con lista propia, es la suya.
 * @param locationId la ubicación elegida, o `null` para la que propone la venta: donde se hizo la
 *   última. Solo se elige con más de una ubicación.
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
    val customerId: Long? = null,
    val priceListId: Long? = null,
    val locationId: Long? = null,
)

/**
 * Guarda la venta en edición en el estado guardado, con tipos que caben en un `Bundle`: las líneas
 * van como ocho números por línea, con su unidad y el enlace de un extra a su línea principal. Solo
 * números y lo que la persona escribió en la nota.
 *
 * Antes las líneas iban en seis números, sin unidad ni enlace, y una línea vendida por caja llegaba
 * al cobro como una unidad suelta. La clave cambió con la forma: una venta guardada con la forma
 * vieja no se lee, en vez de leerse mal.
 */
internal object SaleDraftStore {
    private const val LINES = "sale.lineValues"
    private const val METHOD = "sale.method"
    private const val DATE = "sale.date"
    private const val NOTE = "sale.note"
    private const val AWAITING = "sale.awaiting"
    private const val PREFILLED = "sale.prefilled"
    private const val HELD = "sale.held"
    private const val HELD_LABEL = "sale.heldLabel"
    private const val CUSTOMER = "sale.customer"
    private const val PRICE_LIST = "sale.priceList"
    private const val LOCATION = "sale.location"

    private const val FIELDS = 9
    private const val UNIT = 6
    private const val PARENT = 7
    private const val LOT = 8
    private const val NO_DISCOUNT = 0L
    private const val AMOUNT = 1L
    private const val PERCENT = 2L

    /** Una línea en su unidad base, o una principal: los ids y las posiciones nunca son negativos. */
    private const val NONE = -1L

    fun read(state: SavedStateHandle): SaleDraft = SaleDraft(
        lines = state.get<LongArray>(LINES)?.let(::decode).orEmpty(),
        methodId = state.get<Long>(METHOD),
        date = state.get<String>(DATE)?.let(BusinessDate::parse),
        note = state.get<String>(NOTE).orEmpty(),
        awaitingProductAfter = state.get<Long>(AWAITING),
        prefilled = state.get<Boolean>(PREFILLED) ?: false,
        heldSaleId = state.get<Long>(HELD),
        heldLabel = state.get<String>(HELD_LABEL),
        customerId = state.get<Long>(CUSTOMER),
        priceListId = state.get<Long>(PRICE_LIST),
        locationId = state.get<Long>(LOCATION),
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
        state[CUSTOMER] = draft.customerId
        state[PRICE_LIST] = draft.priceListId
        state[LOCATION] = draft.locationId
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
            values[base + UNIT] = line.unitId ?: NONE
            values[base + PARENT] = line.parentIndex?.toLong() ?: NONE
            values[base + LOT] = line.lotId ?: NONE
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
                unitId = values[base + UNIT].takeIf { it != NONE },
                parentIndex = values[base + PARENT].takeIf { it in 0L until index }?.toInt(),
                lotId = values[base + LOT].takeIf { it != NONE },
            )
        }
    }
}

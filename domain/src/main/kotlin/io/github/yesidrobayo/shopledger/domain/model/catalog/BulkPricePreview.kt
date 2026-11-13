package io.github.yesidrobayo.shopledger.domain.model.catalog

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceList
import io.github.yesidrobayo.shopledger.domain.rule.catalog.BulkExclusion
import io.github.yesidrobayo.shopledger.domain.rule.catalog.BulkPriceOutcome
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ReferenceScale

/** Qué productos toca una actualización masiva. */
sealed interface BulkPriceScope {
    /** Todos los productos. */
    data object All : BulkPriceScope

    /** Los de la categoría [categoryId]. */
    data class Category(val categoryId: Long) : BulkPriceScope

    /** Los que tienen al proveedor [supplierId] como habitual. */
    data class Supplier(val supplierId: Long) : BulkPriceScope

    /** Los que esperan revisión porque una compra subió su costo: la cola `Costo nuevo sin revisar`. */
    data object PendingReview : BulkPriceScope

    /** Los que llevan la etiqueta [tagId]: [productIds], leídos con ella. */
    data class Tag(val tagId: Long, val productIds: Set<Long>) : BulkPriceScope

    /** Los que la persona eligió uno por uno. */
    data class Picked(val productIds: Set<Long>) : BulkPriceScope
}

/**
 * Lo que la persona pidió: el alcance, el cálculo con su dato, el paso de redondeo en unidad menor
 * (cero es sin redondeo), los productos que quitó de la vista previa y las listas que cambia.
 *
 * [priceListIds] vacío es solo el precio de venta, la lista inicial, como siempre. Con listas, cada
 * una cambia su precio: la inicial el de venta, y una fija el propio, o el de venta si no tiene. Una
 * relativa no se escribe: se calcula desde su base.
 */
data class BulkPriceRequest(
    val scope: BulkPriceScope,
    val adjustment: BulkPriceAdjustment,
    val roundingStep: Money,
    val removed: Set<Long> = emptySet(),
    val priceListIds: Set<Long> = emptySet(),
) {
    /** La actualización por porcentaje de siempre, sobre el precio de venta. */
    constructor(
        scope: BulkPriceScope,
        percent: BasisPoints,
        roundingStep: Money,
        removed: Set<Long> = emptySet(),
    ) : this(scope, BulkPriceAdjustment.Percent(percent), roundingStep, removed)
}

/**
 * Lo que la vista previa necesita además del catálogo: lo que fija el negocio para el margen
 * objetivo, las listas y los precios propios de cada producto, por producto y lista, y los decimales
 * de la moneda de referencia. Sin [pricing] no hay margen objetivo; sin [scale], no hay cotización.
 */
data class BulkPriceContext(
    val pricing: PricingContext? = null,
    val lists: Map<Long, PriceList> = emptyMap(),
    val ownPrices: Map<Long, Map<Long, Money>> = emptyMap(),
    val scale: ReferenceScale? = null,
)

/**
 * Un producto que cambia, con su nombre para la lista. [priceListId] es la lista cuyo precio cambia,
 * o `null` para el precio de venta cuando la actualización no eligió listas.
 */
data class BulkPriceLine(
    val productId: Long,
    val name: String,
    val change: BulkPriceOutcome.Changes,
    val priceListId: Long? = null,
) {
    /** Si conviene revisarlo antes de aplicar: margen negativo o variación distorsionada. */
    val flagged: Boolean get() = change.negativeMargin || change.distorted
}

/** Un producto que queda fuera, con el motivo. */
data class BulkExcludedLine(val productId: Long, val name: String, val reason: BulkExclusion)

/**
 * La vista previa de una actualización masiva: los que cambian, primero los que conviene revisar;
 * los que quedan fuera, con su motivo; y cuántos quedan igual o se quitaron a mano.
 */
data class BulkPricePreview(
    val changes: List<BulkPriceLine>,
    val excluded: List<BulkExcludedLine>,
    val unchanged: Int,
    val removed: Int,
) {
    val flagged: Int get() = changes.count { it.flagged }

    companion object {
        val EMPTY: BulkPricePreview = BulkPricePreview(emptyList(), emptyList(), 0, 0)
    }
}

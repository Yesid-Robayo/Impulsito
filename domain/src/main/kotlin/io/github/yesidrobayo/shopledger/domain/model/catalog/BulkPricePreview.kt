package io.github.yesidrobayo.shopledger.domain.model.catalog

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.rule.catalog.BulkExclusion
import io.github.yesidrobayo.shopledger.domain.rule.catalog.BulkPriceOutcome

/** Qué productos toca una actualización masiva. */
sealed interface BulkPriceScope {
    /** Todos los productos. */
    data object All : BulkPriceScope

    /** Los de la categoría [categoryId]. */
    data class Category(val categoryId: Long) : BulkPriceScope

    /** Los que tienen al proveedor [supplierId] como habitual. */
    data class Supplier(val supplierId: Long) : BulkPriceScope

    /** Los que la persona eligió uno por uno. */
    data class Picked(val productIds: Set<Long>) : BulkPriceScope
}

/**
 * Lo que la persona pidió: el alcance, el porcentaje con signo, el paso de redondeo en unidad
 * menor (cero es sin redondeo) y los productos que quitó de la vista previa.
 */
data class BulkPriceRequest(
    val scope: BulkPriceScope,
    val percent: BasisPoints,
    val roundingStep: Money,
    val removed: Set<Long> = emptySet(),
)

/** Un producto que cambia, con su nombre para la lista. */
data class BulkPriceLine(
    val productId: Long,
    val name: String,
    val change: BulkPriceOutcome.Changes,
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

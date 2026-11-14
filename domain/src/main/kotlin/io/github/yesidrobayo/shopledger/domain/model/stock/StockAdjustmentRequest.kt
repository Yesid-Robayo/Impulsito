package io.github.yesidrobayo.shopledger.domain.model.stock

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli

/**
 * Lo que la persona pide al ajustar el stock de un producto: un conteo o un cambio con motivo.
 *
 * [note] es lo que escribió, tal cual: el caso de uso la recorta, y una nota en blanco es ninguna.
 * [locationId] es la ubicación que se ajusta; nula es la inicial, la única mientras no haya otra.
 */
sealed interface StockAdjustmentRequest {
    val productId: Long
    val note: String?
    val locationId: Long?

    /** El motivo que queda en la fila del libro. */
    val reason: StockAdjustmentReason

    /**
     * Contó [counted] milésimas en la realidad, en la ubicación que se ajusta. La diferencia con lo
     * que el libro dice de esa ubicación es el ajuste, y se guarda como corrección por conteo.
     */
    data class Count(
        override val productId: Long,
        val counted: QuantityMilli,
        override val note: String? = null,
        override val locationId: Long? = null,
    ) : StockAdjustmentRequest {
        override val reason: StockAdjustmentReason get() = StockAdjustmentReason.COUNT_CORRECTION
    }

    /**
     * Sale o entra [quantity] milésimas, siempre positivas, por [reason]. [increase] dice el
     * sentido: solo un conteo y otro motivo admiten una entrada.
     */
    data class Change(
        override val productId: Long,
        override val reason: StockAdjustmentReason,
        val quantity: QuantityMilli,
        val increase: Boolean = false,
        override val note: String? = null,
        override val locationId: Long? = null,
    ) : StockAdjustmentRequest
}

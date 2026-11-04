package io.github.yesidrobayo.shopledger.domain.model.stock

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli

/**
 * De qué lado del cero está el stock de un producto.
 *
 * Vender nunca se bloquea por falta de stock: si se vendió, se vendió, y bloquear obligaría a
 * mentir en el registro o a perder la venta. Por eso el saldo puede quedar negativo, y ese negativo
 * es una señal útil: casi siempre falta registrar una compra o el último conteo quedó viejo.
 *
 * El orden de las constantes es el de la lista de productos por agotarse: primero el stock
 * negativo, luego los agotados y al final el resto.
 */
enum class StockLevel {
    /** Menos de cero: se vendió más de lo que el libro tenía. */
    NEGATIVE,

    /** Exactamente cero. */
    OUT_OF_STOCK,

    /** Más de cero. */
    IN_STOCK,
    ;

    companion object {
        /** El nivel de un saldo [onHand]. */
        fun of(onHand: QuantityMilli): StockLevel = when {
            onHand.milli < 0L -> NEGATIVE
            onHand.milli == 0L -> OUT_OF_STOCK
            else -> IN_STOCK
        }
    }
}

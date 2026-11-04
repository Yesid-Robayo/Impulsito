package io.github.yesidrobayo.shopledger.domain.model.stock

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli

/**
 * El signo que admite la cantidad de un movimiento de stock, según su tipo.
 *
 * El signo es la dirección del movimiento: lo que entra suma y lo que sale resta. Una fila con el
 * signo cambiado sumaría donde debía restar, y el saldo quedaría mal sin que nada fallara. Por eso
 * cada tipo fija el suyo, y una fila con otro signo se rechaza antes de escribirse.
 */
enum class QuantitySign {
    /** Siempre entra: mayor que cero. */
    POSITIVE,

    /** Siempre sale: menor que cero. */
    NEGATIVE,

    /** Entra o sale según el caso, pero siempre mueve algo. */
    NON_ZERO,

    /** No mueve unidades: solo cambia el costo. */
    ZERO,
    ;

    /** Si [quantity] tiene este signo. */
    fun allows(quantity: QuantityMilli): Boolean = when (this) {
        POSITIVE -> quantity.milli > 0L
        NEGATIVE -> quantity.milli < 0L
        NON_ZERO -> quantity.milli != 0L
        ZERO -> quantity.milli == 0L
    }
}

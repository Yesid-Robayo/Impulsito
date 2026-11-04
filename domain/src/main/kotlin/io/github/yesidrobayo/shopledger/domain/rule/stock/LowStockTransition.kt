package io.github.yesidrobayo.shopledger.domain.rule.stock

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli

/** Qué le pasa a la alerta de stock bajo de un producto al confirmar una transacción. */
sealed interface LowStockTransition {
    /**
     * El producto no se vigila: no lleva stock propio, o está archivado. Una alerta activa de un
     * producto archivado ya se resolvió al archivarlo.
     */
    data object NotWatched : LowStockTransition

    /** No cruzó hacia abajo ni quedó por encima del mínimo: la alerta sigue como estaba. */
    data object Unchanged : LowStockTransition

    /**
     * Pasó de no bajo a bajo: se crea la alerta y se avisa una vez. [onHand] y [minStock] son el
     * saldo y el mínimo en ese momento, y quedan en la alerta.
     */
    data class Crossed(val onHand: QuantityMilli, val minStock: QuantityMilli) : LowStockTransition

    /**
     * Quedó por encima del mínimo con una alerta activa: la alerta se resuelve, y la próxima caída
     * vuelve a avisar.
     */
    data object Rearmed : LowStockTransition
}

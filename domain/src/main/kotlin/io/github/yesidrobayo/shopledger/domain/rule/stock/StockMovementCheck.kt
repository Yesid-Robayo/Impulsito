package io.github.yesidrobayo.shopledger.domain.rule.stock

/**
 * Resultado de [StockMovementRules.check]. Cualquier rechazo revierte la transacción entera: un
 * documento rechazado no deja ninguna fila.
 *
 * No existe un rechazo por falta de stock, porque vender nunca se bloquea por eso.
 */
sealed interface StockMovementCheck {
    /** La fila se puede agregar al libro. */
    data object Valid : StockMovementCheck

    /** La cantidad no tiene el signo que exige su tipo. */
    data object WrongQuantitySign : StockMovementCheck

    /** Una fila que no es compensatorio dice anular otra. */
    data object UnexpectedCompensation : StockMovementCheck

    /** Un compensatorio no dice qué fila anula, o esa fila no existe. */
    data object MissingCompensatedMovement : StockMovementCheck

    /**
     * El compensatorio no es del tipo que anula esa fila: por ejemplo, la anulación de una compra
     * apuntando a una venta, o cualquier anulación apuntando a otro compensatorio o a una
     * corrección de costo.
     */
    data object CrossCompensation : StockMovementCheck

    /** El compensatorio cae en otro producto, otra ubicación u otro lote que la fila que anula. */
    data object DifferentStockKey : StockMovementCheck

    /** La cantidad del compensatorio no es exactamente la opuesta de la fila que anula. */
    data object QuantityNotOpposite : StockMovementCheck

    /** La fila ya tiene su compensatorio: anularla otra vez descontaría dos veces. */
    data object AlreadyCompensated : StockMovementCheck

    /** La entrada dejaría el stock del producto sobre su tope. */
    data object AboveOnHandLimit : StockMovementCheck
}

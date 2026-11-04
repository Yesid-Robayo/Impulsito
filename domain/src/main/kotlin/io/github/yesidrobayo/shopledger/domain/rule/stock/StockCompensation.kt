package io.github.yesidrobayo.shopledger.domain.rule.stock

import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementDraft

/** Resultado de [StockMovementRules.compensationOf]. */
sealed interface StockCompensation {
    /** El compensatorio listo para agregar al libro. */
    data class Ready(val draft: StockMovementDraft) : StockCompensation

    /** La fila no se anula: es una corrección de costo o ya es un compensatorio. */
    data object NotCompensable : StockCompensation

    /** La fila ya tiene su compensatorio. Anular dos veces no compensa dos veces. */
    data object AlreadyCompensated : StockCompensation
}

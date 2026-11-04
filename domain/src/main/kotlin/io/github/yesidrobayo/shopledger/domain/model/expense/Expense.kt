package io.github.yesidrobayo.shopledger.domain.model.expense

import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.Voiding

/**
 * Un gasto guardado. Nunca se edita: solo se anula, y entonces [voiding] guarda cuándo y por qué.
 */
data class Expense(val id: Long, val content: NewExpense, val voiding: Voiding?) {
    /** Activo o anulado, según [voiding]. */
    val status: DocumentStatus get() = DocumentStatus.of(voiding)
}

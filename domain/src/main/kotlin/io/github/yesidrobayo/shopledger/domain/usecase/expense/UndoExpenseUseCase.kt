package io.github.yesidrobayo.shopledger.domain.usecase.expense

import io.github.yesidrobayo.shopledger.domain.model.VoidReason

/**
 * Deshacer un gasto recién guardado, desde el aviso que sigue a guardarlo.
 *
 * No es un borrado ni un camino aparte: es la anulación de siempre, con el motivo
 * [VoidReason.UNDO] y sin nota, en una sola transacción. El gasto queda en la lista, anulado, porque
 * nada se borra. Cuánto dura el aviso lo decide la pantalla: pasado ese tiempo, la persona anula
 * desde la hoja del gasto, con el motivo que elija.
 */
class UndoExpenseUseCase(private val voidExpense: VoidExpenseUseCase) {
    suspend operator fun invoke(expenseId: Long): VoidExpenseResult =
        voidExpense(expenseId, VoidReason.UNDO, note = null)
}

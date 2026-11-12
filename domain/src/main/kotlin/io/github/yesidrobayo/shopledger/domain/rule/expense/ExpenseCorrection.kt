package io.github.yesidrobayo.shopledger.domain.rule.expense

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseCategory
import io.github.yesidrobayo.shopledger.domain.model.expense.NewExpense

/**
 * Lo que trae un gasto nuevo que corrige otro ya anulado: el valor, la categoría, la descripción y
 * la fecha. [categoryId] es `null` si la categoría original hoy está archivada o ya no existe: no se
 * puede elegir, y [categoryLeftOut] lo dice para que la persona elija otra.
 */
data class ExpenseCorrection(
    val amount: Money,
    val categoryId: Long?,
    val description: String?,
    val date: BusinessDate,
    val categoryLeftOut: Boolean,
)

/** Arma la corrección de un gasto guardado. No escribe nada. */
object ExpenseCorrections {
    /** La corrección de [expense], cuya categoría de hoy es [category], o `null` si no existe. */
    fun of(expense: NewExpense, category: ExpenseCategory?): ExpenseCorrection {
        val usable = category?.takeIf { !it.isArchived }
        return ExpenseCorrection(
            amount = expense.amount,
            categoryId = usable?.id,
            description = expense.description,
            date = expense.businessDate,
            categoryLeftOut = usable == null,
        )
    }
}

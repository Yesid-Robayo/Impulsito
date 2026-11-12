package io.github.yesidrobayo.shopledger.domain.model.expense

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money

/**
 * Lo que la persona pide registrar como gasto, tal como lo escribió.
 *
 * [amount] va en unidad menor. [categoryId] es obligatorio: la pantalla propone la categoría de
 * otros gastos, que siempre existe y nunca se archiva. [description] es opcional; en blanco no se
 * guarda. [date] es la fecha de negocio elegida, o `null` para hoy: puede ser pasada, nunca futura.
 */
data class ExpenseRequest(
    val amount: Money,
    val categoryId: Long,
    val description: String? = null,
    val date: BusinessDate? = null,
)

/** Lo que impide registrar un gasto tal como se escribió. */
enum class ExpenseInputError {
    /** El valor es cero o negativo: un gasto saca dinero, nunca lo deja igual ni lo devuelve. */
    AMOUNT_NOT_POSITIVE,

    /** El valor pasa del tope de un documento, que protege las sumas de los reportes. */
    AMOUNT_TOO_LARGE,

    /** La descripción pasa de 200 caracteres. */
    DESCRIPTION_TOO_LONG,
}

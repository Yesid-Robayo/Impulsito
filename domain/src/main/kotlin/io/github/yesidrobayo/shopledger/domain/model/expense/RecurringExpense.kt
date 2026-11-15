package io.github.yesidrobayo.shopledger.domain.model.expense

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import kotlin.time.Instant

/**
 * Cada cuánto se repite un gasto recurrente. Los nombres se guardan en
 * `recurring_expenses.recurrence` y no cambian.
 */
enum class Recurrence {
    /** Cada semana, el mismo día de la semana. */
    WEEKLY,

    /** Cada mes, el mismo día, o el último si el mes es más corto. */
    MONTHLY,

    /** Cada año, el mismo mes y día, o el último del mes si no existe. */
    YEARLY,
}

/**
 * Lo que guarda un gasto recurrente: la plantilla de un gasto que se repite, como el alquiler.
 *
 * - [amount] va en unidad menor de la moneda del negocio, mayor que cero.
 * - [dayOfPeriod] depende de [recurrence]: de 1 (lunes) a 7 (domingo) cada semana, de 1 a 31 cada
 *   mes, y de 1 a 366 cada año, contado sobre un año bisiesto: 60 es el 29 de febrero y 366 el 31
 *   de diciembre. Así cada día del año tiene un número fijo, aunque el año no sea bisiesto.
 * - [nextDueDate] es la fecha en que la app propone el gasto. Nunca se registra solo: la persona
 *   lo confirma o lo omite, y recién entonces pasa a la siguiente.
 * - [moneyAccountId] es la cuenta de la que suele salir, si la persona lleva cuentas.
 */
data class RecurringExpenseDetails(
    val expenseCategoryId: Long,
    val amount: Money,
    val description: String?,
    val recurrence: Recurrence,
    val dayOfPeriod: Int,
    val nextDueDate: BusinessDate,
    val moneyAccountId: Long?,
    val reminderEnabled: Boolean,
)

/** Un gasto recurrente guardado. Archivado, deja de proponerse; sus gastos no cambian. */
data class RecurringExpense(
    val id: Long,
    val details: RecurringExpenseDetails,
    val archivedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val isArchived: Boolean get() = archivedAt != null

    /** Si ya toca confirmarlo u omitirlo: su fecha llegó o pasó, y sigue vigente. */
    fun isDue(today: BusinessDate): Boolean = !isArchived && details.nextDueDate <= today
}

/**
 * Lo que la persona escribe al crear o editar un gasto recurrente, tal como lo escribió. La
 * descripción en blanco no se guarda.
 */
data class RecurringExpenseDraft(
    val amount: Money,
    val categoryId: Long,
    val description: String?,
    val recurrence: Recurrence,
    val dayOfPeriod: Int,
    val nextDueDate: BusinessDate,
    val moneyAccountId: Long? = null,
    val reminderEnabled: Boolean = false,
)

/** Lo que impide guardar un gasto recurrente tal como se escribió. */
enum class RecurringExpenseInputError {
    /** El valor es cero o negativo. */
    AMOUNT_NOT_POSITIVE,

    /** El valor pasa del tope de un documento. */
    AMOUNT_TOO_LARGE,

    /** La descripción pasa de 200 caracteres. */
    DESCRIPTION_TOO_LONG,

    /** El día no existe en la repetición: 8 cada semana, 32 cada mes o 367 cada año. */
    DAY_OUT_OF_RANGE,

    /** La próxima fecha no cae en el día elegido, contando el último del mes si es más corto. */
    NEXT_DATE_MISMATCH,
}

/**
 * Un vencimiento de un gasto recurrente: el de [recurringExpenseId] en [dueDate]. Confirmarlo u
 * omitirlo exige que la plantilla siga en esa fecha: si otra pantalla ya lo hizo, no se repite.
 */
data class RecurringOccurrence(val recurringExpenseId: Long, val dueDate: BusinessDate)

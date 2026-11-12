package io.github.yesidrobayo.shopledger.domain.rule.expense

import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseCategoryDetails
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseCategoryDraft
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseCategoryInputError
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseInputError
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseRequest
import io.github.yesidrobayo.shopledger.domain.rule.catalog.CatalogName
import io.github.yesidrobayo.shopledger.domain.rule.catalog.CatalogText

/**
 * Revisa un gasto, su anulación y una categoría de gasto antes de escribirlos, y devuelve todos los
 * motivos a la vez, para que la hoja marque cada campo de una sola pasada.
 *
 * Los topes son los de las guardas de `expenses` y `expense_categories`, que quedan como última
 * defensa: un rechazo de la base no dice qué campo corregir. Los largos se cuentan en caracteres,
 * como la base, y no en unidades de UTF-16.
 */
object ExpenseRules {
    /** Largo máximo de la descripción de un gasto. */
    const val DESCRIPTION_MAX: Int = 200

    /** Largo máximo de la nota de una anulación. */
    const val VOID_NOTE_MAX: Int = 200

    /** Largo máximo del nombre de una categoría de gasto. */
    const val CATEGORY_NAME_MAX: Int = 40

    /** Revisa el valor y la descripción de [request]. La categoría y la fecha piden leer la base. */
    fun check(request: ExpenseRequest): ExpenseCheck {
        val errors = mutableSetOf<ExpenseInputError>()
        val amount = request.amount
        if (amount <= Money.ZERO) errors += ExpenseInputError.AMOUNT_NOT_POSITIVE
        if (!Limit.DOCUMENT_TOTAL.allows(amount.minor)) errors += ExpenseInputError.AMOUNT_TOO_LARGE
        val description = optionalText(request.description)
        if (description != null && CatalogText.length(description) > DESCRIPTION_MAX) {
            errors += ExpenseInputError.DESCRIPTION_TOO_LONG
        }
        return if (errors.isEmpty()) {
            ExpenseCheck.Valid(amount, description)
        } else {
            ExpenseCheck.Invalid(errors)
        }
    }

    /**
     * La nota de una anulación recortada, `null` si queda en blanco, o [VoidNoteCheck.TooLong] si
     * pasa de su tope.
     */
    fun voidNote(raw: String?): VoidNoteCheck {
        val note = optionalText(raw)
        return if (note != null && CatalogText.length(note) > VOID_NOTE_MAX) {
            VoidNoteCheck.TooLong
        } else {
            VoidNoteCheck.Valid(note)
        }
    }

    /**
     * Revisa [draft]. Con [isInitial], la categoría es una de las iniciales: sin nombre conserva el
     * traducido. Cualquier otra necesita un nombre.
     */
    fun checkCategory(draft: ExpenseCategoryDraft, isInitial: Boolean): ExpenseCategoryCheck {
        val name = when (val checked = CatalogText.name(draft.name, CATEGORY_NAME_MAX)) {
            CatalogName.Empty -> if (isInitial) {
                null
            } else {
                return ExpenseCategoryCheck.Invalid(setOf(ExpenseCategoryInputError.NAME_EMPTY))
            }

            CatalogName.TooLong ->
                return ExpenseCategoryCheck.Invalid(setOf(ExpenseCategoryInputError.NAME_TOO_LONG))

            is CatalogName.Valid -> checked
        }
        return ExpenseCategoryCheck.Valid(
            ExpenseCategoryDetails(
                name = name?.name,
                normalizedName = name?.normalizedName,
                isFixed = draft.isFixed,
            ),
        )
    }

    /** [raw] recortado, o `null` si no hay nada que guardar. */
    private fun optionalText(raw: String?): String? =
        raw?.let { text -> text.trim().ifEmpty { null } }
}

/** Resultado de [ExpenseRules.check]. */
sealed interface ExpenseCheck {
    /** Se puede registrar: el valor y la descripción recortada, o `null` sin descripción. */
    data class Valid(val amount: Money, val description: String?) : ExpenseCheck

    /** No se registra nada: cada error va en su campo. */
    data class Invalid(val errors: Set<ExpenseInputError>) : ExpenseCheck
}

/** Resultado de [ExpenseRules.voidNote]. */
sealed interface VoidNoteCheck {
    /** La nota que se guarda, recortada, o `null` sin nota. */
    data class Valid(val note: String?) : VoidNoteCheck

    /** La nota pasa de su largo máximo. */
    data object TooLong : VoidNoteCheck
}

/** Resultado de [ExpenseRules.checkCategory]. */
sealed interface ExpenseCategoryCheck {
    /** Se puede guardar: [details] ya va recortado y normalizado. */
    data class Valid(val details: ExpenseCategoryDetails) : ExpenseCategoryCheck

    /** No se guarda nada: cada error va en su campo. */
    data class Invalid(val errors: Set<ExpenseCategoryInputError>) : ExpenseCategoryCheck
}

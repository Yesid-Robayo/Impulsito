package io.github.yesidrobayo.shopledger.domain.rule.money

import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.money.ManualMoneyInputError
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementDraft

/** Resultado de [ManualMoneyRules.check]. */
sealed interface ManualMoneyCheck {
    /** Se puede registrar: el monto positivo y la nota recortada, o `null` sin nota. */
    data class Valid(val amount: Money, val note: String?) : ManualMoneyCheck

    /** No se registra nada: cada error va en su campo. */
    data class Invalid(val errors: Set<ManualMoneyInputError>) : ManualMoneyCheck
}

/** Lo que se revisa del dinero que la persona registra a mano en una cuenta. */
object ManualMoneyRules {
    /**
     * Revisa el monto y la nota. El monto va de 1 al tope de un documento: el sentido lo da el tipo.
     * Una nota en blanco no se guarda.
     */
    fun check(amount: Money, note: String?): ManualMoneyCheck {
        val errors = mutableSetOf<ManualMoneyInputError>()
        if (amount <= Money.ZERO) errors += ManualMoneyInputError.AMOUNT_NOT_POSITIVE
        if (!Limit.DOCUMENT_TOTAL.allows(amount.minor)) {
            errors +=
                ManualMoneyInputError.AMOUNT_TOO_LARGE
        }
        val trimmed = note?.let { text -> text.trim().ifEmpty { null } }
        if (trimmed != null && trimmed.length > MoneyMovementDraft.NOTE_MAX) {
            errors += ManualMoneyInputError.NOTE_TOO_LONG
        }
        if (errors.isNotEmpty()) return ManualMoneyCheck.Invalid(errors)
        return ManualMoneyCheck.Valid(amount, trimmed)
    }
}

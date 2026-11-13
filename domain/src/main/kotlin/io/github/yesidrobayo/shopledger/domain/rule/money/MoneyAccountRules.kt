package io.github.yesidrobayo.shopledger.domain.rule.money

import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountDetails
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountDraft
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountInputError
import io.github.yesidrobayo.shopledger.domain.rule.catalog.CatalogName
import io.github.yesidrobayo.shopledger.domain.rule.catalog.CatalogText

/** Resultado de [MoneyAccountRules.check]. */
sealed interface MoneyAccountCheck {
    /**
     * Se puede guardar: lo que se escribe de la cuenta y el saldo inicial que entra como su primer
     * movimiento, o `null` si nace sin movimientos.
     */
    data class Valid(val details: MoneyAccountDetails, val openingBalance: Money?) :
        MoneyAccountCheck

    /** No se guarda nada: cada error va en su campo. */
    data class Invalid(val errors: Set<MoneyAccountInputError>) : MoneyAccountCheck
}

/** Lo que se revisa de una cuenta de dinero antes de guardarla. */
object MoneyAccountRules {
    /** Los caracteres más largos del nombre de una cuenta. */
    const val NAME_MAX: Int = 40

    /**
     * Revisa [draft]. [isInitial] dice si la cuenta tiene nombre traducido al que volver: entonces un
     * nombre en blanco no es un error.
     *
     * El nombre se recorta y se normaliza para compararlo sin mayúsculas ni tildes. Un saldo inicial
     * de cero no escribe nada: un movimiento de cero no existe.
     */
    fun check(draft: MoneyAccountDraft, isInitial: Boolean): MoneyAccountCheck {
        val errors = mutableSetOf<MoneyAccountInputError>()
        val name = when (val checked = CatalogText.name(draft.name, NAME_MAX)) {
            CatalogName.Empty -> {
                if (!isInitial) errors += MoneyAccountInputError.NAME_EMPTY
                null
            }

            CatalogName.TooLong -> {
                errors += MoneyAccountInputError.NAME_TOO_LONG
                null
            }

            is CatalogName.Valid -> checked
        }
        val opening = draft.openingBalance?.takeIf { it != Money.ZERO }
        if (opening != null && !withinDocumentTotal(opening)) {
            errors += MoneyAccountInputError.OPENING_BALANCE_TOO_LARGE
        }
        if (errors.isNotEmpty()) return MoneyAccountCheck.Invalid(errors)
        return MoneyAccountCheck.Valid(
            MoneyAccountDetails(
                name = name?.name,
                normalizedName = name?.normalizedName,
                kind = draft.kind,
            ),
            opening,
        )
    }

    /**
     * Si un monto con signo cabe en el tope de un documento, hacia los dos lados. Se compara sin
     * sacar el valor absoluto, que no existe para el `Long` más negativo.
     */
    fun withinDocumentTotal(amount: Money): Boolean =
        amount.minor <= Limit.DOCUMENT_TOTAL.max && amount.minor >= -Limit.DOCUMENT_TOTAL.max
}

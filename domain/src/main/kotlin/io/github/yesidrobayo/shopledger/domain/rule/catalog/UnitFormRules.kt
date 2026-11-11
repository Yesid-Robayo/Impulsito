package io.github.yesidrobayo.shopledger.domain.rule.catalog

import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitDraft
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitInputError

/**
 * Revisa lo que la persona escribió de una unidad y devuelve todos los motivos a la vez, para que la
 * hoja marque cada campo de una sola pasada.
 *
 * Los largos son los de la guarda de `units`, que queda como última defensa: un rechazo de la base
 * no dice qué campo corregir.
 */
object UnitFormRules {
    /** Largo máximo del nombre de una unidad. */
    const val NAME_MAX: Int = 40

    /** Largo máximo del símbolo de una unidad. */
    const val SYMBOL_MAX: Int = 10

    /**
     * Revisa [draft]. Con [isInitial], la unidad es una de las iniciales: sin nombre ni símbolo
     * conserva los traducidos, y sigue el idioma de la interfaz.
     */
    fun check(draft: UnitDraft, isInitial: Boolean): UnitFormCheck {
        val errors = mutableSetOf<UnitInputError>()
        val name =
            when (val checked = CatalogText.name(draft.name, NAME_MAX)) {
                CatalogName.Empty -> {
                    if (!isInitial) errors += UnitInputError.NAME_EMPTY
                    null
                }

                CatalogName.TooLong -> {
                    errors += UnitInputError.NAME_TOO_LONG
                    null
                }

                is CatalogName.Valid -> checked
            }
        val symbol = draft.symbol.trim().ifEmpty { null }
        if (symbol != null && CatalogText.length(symbol) > SYMBOL_MAX) {
            errors += UnitInputError.SYMBOL_TOO_LONG
        }
        if (errors.isNotEmpty()) return UnitFormCheck.Invalid(errors)
        return UnitFormCheck.Valid(
            UnitDetails(
                name = name?.name,
                normalizedName = name?.normalizedName,
                symbol = symbol,
                kind = draft.kind,
                allowsFraction = draft.allowsFraction,
            ),
        )
    }
}

/** Resultado de [UnitFormRules.check]. */
sealed interface UnitFormCheck {
    /** Se puede guardar: [details] ya va recortado y normalizado. */
    data class Valid(val details: UnitDetails) : UnitFormCheck

    /** No se guarda nada: cada error va en su campo. */
    data class Invalid(val errors: Set<UnitInputError>) : UnitFormCheck
}

package io.github.yesidrobayo.shopledger.domain.rule.pricing

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListDetails
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListDraft
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListInputError
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListKind
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListPricing
import io.github.yesidrobayo.shopledger.domain.rule.catalog.CatalogName
import io.github.yesidrobayo.shopledger.domain.rule.catalog.CatalogText

/**
 * Revisa lo que la persona escribió de una lista de precios y devuelve todos los motivos a la vez,
 * para que la hoja marque cada campo de una sola pasada.
 *
 * Los largos y los topes son los de la guarda de `price_lists`, que queda como última defensa: un
 * rechazo de la base no dice qué campo corregir. Que la base elegida exista y no forme un ciclo lo
 * revisa el caso de uso, porque necesita leer las otras listas.
 */
object PriceListFormRules {
    /** Largo máximo del nombre de una lista. */
    const val NAME_MAX: Int = 40

    /** El ajuste más bajo: −100 %, que deja todos los precios en cero. */
    val MIN_ADJUSTMENT: BasisPoints = BasisPoints(-10_000L)

    /** El ajuste más alto: +10.000 %. */
    val MAX_ADJUSTMENT: BasisPoints = BasisPoints(1_000_000L)

    /** Revisa [draft], de una lista que crea o edita la persona: la inicial no se edita. */
    fun check(draft: PriceListDraft): PriceListFormCheck {
        val errors = mutableSetOf<PriceListInputError>()
        val name =
            when (val checked = CatalogText.name(draft.name, NAME_MAX)) {
                CatalogName.Empty -> {
                    errors += PriceListInputError.NAME_EMPTY
                    null
                }

                CatalogName.TooLong -> {
                    errors += PriceListInputError.NAME_TOO_LONG
                    null
                }

                is CatalogName.Valid -> checked
            }
        val pricing =
            when (draft.kind) {
                PriceListKind.FIXED -> PriceListPricing.Fixed
                PriceListKind.RELATIVE -> relative(draft, errors)
            }
        // Sin nombre o sin parte relativa ya hay un error anotado; con los dos, el ajuste puede
        // estar fuera de rango.
        if (pricing == null || name == null) return PriceListFormCheck.Invalid(errors)
        if (errors.isNotEmpty()) return PriceListFormCheck.Invalid(errors)
        return PriceListFormCheck.Valid(PriceListDetails(name.name, name.normalizedName, pricing))
    }

    /** La parte relativa de [draft], o `null` si le falta algo; cada falta queda en [errors]. */
    private fun relative(
        draft: PriceListDraft,
        errors: MutableSet<PriceListInputError>,
    ): PriceListPricing.Relative? {
        val base = draft.basePriceListId
        val adjustment = draft.adjustment
        if (base == null) errors += PriceListInputError.BASE_MISSING
        if (adjustment == null) {
            errors += PriceListInputError.ADJUSTMENT_MISSING
        } else if (adjustment < MIN_ADJUSTMENT || adjustment > MAX_ADJUSTMENT) {
            errors += PriceListInputError.ADJUSTMENT_OUT_OF_RANGE
        }
        if (base == null || adjustment == null) return null
        return PriceListPricing.Relative(base, adjustment)
    }
}

/** Resultado de [PriceListFormRules.check]. */
sealed interface PriceListFormCheck {
    /** Se puede guardar: [details] ya va recortado y normalizado. */
    data class Valid(val details: PriceListDetails) : PriceListFormCheck

    /** No se guarda nada: cada error va en su campo. */
    data class Invalid(val errors: Set<PriceListInputError>) : PriceListFormCheck
}

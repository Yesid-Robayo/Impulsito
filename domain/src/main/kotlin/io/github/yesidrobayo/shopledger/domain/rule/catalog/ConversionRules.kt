package io.github.yesidrobayo.shopledger.domain.rule.catalog

import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDraft
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionInputError

/**
 * Revisa una conversión antes de guardarla y devuelve todos los motivos a la vez.
 *
 * El factor sigue la regla de [UnitConversion]: al menos una milésima de la unidad base, y no más
 * que la cantidad máxima de una línea. Una conversión une dos unidades distintas: la base consigo
 * misma ya vale mil milésimas y no se guarda. Los topes son los de la guarda de `product_units`.
 */
object ConversionRules {
    /** Revisa [draft] para un producto cuya unidad base es [baseUnitId]. */
    fun check(draft: ConversionDraft, baseUnitId: Long): ConversionCheck {
        val errors = mutableSetOf<ConversionInputError>()
        when {
            draft.factorMilli < 1L -> errors += ConversionInputError.FACTOR_NOT_POSITIVE

            !UnitConversion.isValidFactor(draft.factorMilli) ->
                errors += ConversionInputError.FACTOR_TOO_HIGH
        }
        if (draft.unitId == baseUnitId) errors += ConversionInputError.SAME_AS_BASE
        val price = draft.salePrice
        if (price != null && (price.minor < 0L || !Limit.UNIT_MONEY.allows(price.minor))) {
            errors += ConversionInputError.PRICE_TOO_HIGH
        }
        val barcode = CatalogText.barcode(draft.barcode)
        if (barcode != null && CatalogText.length(barcode) > CatalogText.BARCODE_MAX) {
            errors += ConversionInputError.BARCODE_TOO_LONG
        }
        if (errors.isNotEmpty()) return ConversionCheck.Invalid(errors)
        return ConversionCheck.Valid(
            ConversionDetails(
                unitId = draft.unitId,
                factorMilli = draft.factorMilli,
                salePrice = price,
                barcode = barcode,
                forSale = draft.forSale,
                forPurchase = draft.forPurchase,
            ),
        )
    }
}

/** Resultado de [ConversionRules.check]. */
sealed interface ConversionCheck {
    /** Se puede guardar: el código ya va recortado, y nulo si quedó en blanco. */
    data class Valid(val details: ConversionDetails) : ConversionCheck

    /** No se guarda nada: cada error va en su campo. */
    data class Invalid(val errors: Set<ConversionInputError>) : ConversionCheck
}

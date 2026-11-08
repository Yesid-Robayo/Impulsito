package io.github.yesidrobayo.shopledger.domain.rule.tax

import io.github.yesidrobayo.shopledger.domain.model.tax.TaxRateDetails
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxRateInputError

/** Lo que dijo [TaxRateRules.check] de lo escrito en una tasa. */
sealed interface TaxRateCheck {
    /**
     * Cada campo está en su rango, y [details] es lo que se guardaría. Falta lo que solo sabe la
     * base: que el nombre no lo use otra vigente.
     */
    data class Valid(val details: TaxRateDetails) : TaxRateCheck

    /** Uno o más campos fuera de regla. Nunca viene vacío. */
    data class Invalid(val errors: Set<TaxRateInputError>) : TaxRateCheck
}

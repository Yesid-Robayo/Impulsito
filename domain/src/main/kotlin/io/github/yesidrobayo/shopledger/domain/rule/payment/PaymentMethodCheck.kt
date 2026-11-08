package io.github.yesidrobayo.shopledger.domain.rule.payment

import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodDetails
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodInputError

/** Lo que dijo [PaymentMethodRules.check] de lo escrito en un método de pago. */
sealed interface PaymentMethodCheck {
    /**
     * Cada campo está en su rango, y [details] es lo que se guardaría, sin imagen de cobro. Falta
     * lo que solo sabe la base: que el nombre no lo use otro vigente y que la cuenta exista.
     */
    data class Valid(val details: PaymentMethodDetails) : PaymentMethodCheck

    /** Uno o más campos fuera de regla. Nunca viene vacío. */
    data class Invalid(val errors: Set<PaymentMethodInputError>) : PaymentMethodCheck
}

package io.github.yesidrobayo.shopledger.domain.rule.customer

import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.settings.LoyaltySettings

/** Lo que impide guardar el programa de puntos tal como se escribió. */
enum class LoyaltyProgramIssue {
    /** Falta el monto para ganar un punto, y el programa queda activo. */
    AMOUNT_PER_POINT_REQUIRED,

    /** Falta el descuento por punto, y el programa queda activo. */
    POINT_VALUE_REQUIRED,

    /** El monto para ganar un punto es cero, negativo o pasa del tope de un precio. */
    AMOUNT_PER_POINT_OUT_OF_RANGE,

    /** El descuento por punto es cero, negativo o pasa del tope de un precio. */
    POINT_VALUE_OUT_OF_RANGE,
}

/** Resultado de [LoyaltyProgramRules.check]. */
sealed interface LoyaltyProgramCheck {
    data class Valid(val settings: LoyaltySettings) : LoyaltyProgramCheck

    data class Invalid(val issues: Set<LoyaltyProgramIssue>) : LoyaltyProgramCheck
}

/**
 * El programa de puntos de los clientes. Los puntos no son dinero: no se cambian por efectivo, no
 * entran en ninguna cuenta, no son saldo a favor y no cuentan como deuda. Por eso son enteros y
 * viven en su propio libro, y nada de aquí escribe en el libro del cliente ni en el de dinero.
 *
 * Activo, los dos montos son obligatorios. Apagado, se conservan los de la última vez, para no
 * pedirlos de nuevo al volver a activarlo.
 */
object LoyaltyProgramRules {
    fun check(settings: LoyaltySettings): LoyaltyProgramCheck {
        val issues = mutableSetOf<LoyaltyProgramIssue>()
        val perPoint = settings.amountPerPoint
        val value = settings.pointValue
        when {
            perPoint == null && settings.enabled ->
                issues +=
                    LoyaltyProgramIssue.AMOUNT_PER_POINT_REQUIRED

            perPoint != null && !inRange(perPoint) ->
                issues +=
                    LoyaltyProgramIssue.AMOUNT_PER_POINT_OUT_OF_RANGE
        }
        when {
            value == null && settings.enabled -> issues += LoyaltyProgramIssue.POINT_VALUE_REQUIRED

            value != null && !inRange(value) ->
                issues +=
                    LoyaltyProgramIssue.POINT_VALUE_OUT_OF_RANGE
        }
        return if (issues.isEmpty()) {
            LoyaltyProgramCheck.Valid(settings)
        } else {
            LoyaltyProgramCheck.Invalid(issues)
        }
    }

    /**
     * Los puntos que gana una venta con [netTotal] después del canje: la división entera por
     * [amountPerPoint], que trunca. Una venta en cero o negativa no gana puntos.
     */
    fun pointsEarned(netTotal: Money, amountPerPoint: Money): Long =
        if (netTotal <= Money.ZERO) 0L else netTotal.minor / amountPerPoint.minor

    /** El descuento de canjear [points] a [pointValue] cada uno, o por qué no cabe. */
    fun discountFor(points: Long, pointValue: Money): Checked<Money> = try {
        val discount = Math.multiplyExact(points, pointValue.minor)
        if (Limit.DOCUMENT_TOTAL.allows(discount)) {
            Checked.Value(Money(discount))
        } else {
            Checked.AboveLimit(Limit.DOCUMENT_TOTAL)
        }
    } catch (overflow: ArithmeticException) {
        Checked.Overflow
    }

    private fun inRange(amount: Money): Boolean =
        amount > Money.ZERO && Limit.UNIT_MONEY.allows(amount.minor)
}

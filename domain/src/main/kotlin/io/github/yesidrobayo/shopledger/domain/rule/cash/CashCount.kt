package io.github.yesidrobayo.shopledger.domain.rule.cash

import io.github.yesidrobayo.shopledger.domain.model.Money

/**
 * Las cuentas de cerrar una caja: lo esperado y la diferencia con lo contado.
 *
 * ```text
 * expected_amount   = opening_amount + suma de los movimientos de la sesión, sin CASH_DIFFERENCE
 * difference_amount = counted_amount - expected_amount
 * ```
 *
 * Una diferencia negativa es un faltante, y una positiva un sobrante. Todo va en la moneda de la
 * cuenta, con suma exacta: un monto que no cabe se rechaza en vez de dar la vuelta.
 */
object CashCount {
    /** Lo esperado: la apertura más lo que movió la cuenta en la sesión, o `null` si no cabe. */
    fun expected(opening: Money, movementsTotal: Money): Money? = try {
        opening + movementsTotal
    } catch (overflow: ArithmeticException) {
        null
    }

    /** Lo contado menos lo esperado, o `null` si no cabe. */
    fun difference(counted: Money, expected: Money): Money? = try {
        counted - expected
    } catch (overflow: ArithmeticException) {
        null
    }
}

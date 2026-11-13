package io.github.yesidrobayo.shopledger.domain.model.money

import io.github.yesidrobayo.shopledger.domain.model.settings.BusinessCurrency
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode

/**
 * La moneda de una cuenta de dinero: su código y cuántos decimales tiene su unidad menor.
 *
 * Puede no ser la del negocio. Cada monto de la cuenta va en la unidad menor de esta moneda, así que
 * dos cuentas solo se suman si tienen el mismo código **y** los mismos decimales: el mismo entero
 * vale distinto con otros decimales.
 *
 * Lanza [IllegalArgumentException] con decimales fuera de 0 a 4, que la base no admite.
 */
data class AccountCurrency(val code: CurrencyCode, val decimals: Int) {
    init {
        require(decimals in 0..MAX_DECIMALS) { "Decimales fuera de rango" }
    }

    companion object {
        /** Los decimales más largos que admite una moneda guardada. */
        const val MAX_DECIMALS: Int = 4

        /** La moneda del negocio, como moneda de una cuenta. */
        fun of(business: BusinessCurrency): AccountCurrency =
            AccountCurrency(business.code, business.decimals)
    }
}

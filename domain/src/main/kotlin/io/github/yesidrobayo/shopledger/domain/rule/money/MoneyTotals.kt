package io.github.yesidrobayo.shopledger.domain.rule.money

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.money.AccountCurrency
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccount
import io.github.yesidrobayo.shopledger.domain.model.sumOrNull

/**
 * El dinero de las cuentas, separado por moneda.
 *
 * - [inBookCurrency] son las cuentas en la moneda del negocio, con el mismo código y los mismos
 *   decimales, y [available] es la suma de sus saldos, o `null` si desborda.
 * - [inOtherCurrencies] son las demás, cada una con su saldo en su moneda. Nunca entran en
 *   [available]: sumar unidades de monedas distintas daría una cifra falsa.
 */
data class MoneyOverview(
    val bookCurrency: AccountCurrency,
    val available: Money?,
    val inBookCurrency: List<MoneyAccount>,
    val inOtherCurrencies: List<MoneyAccount>,
)

/** Las sumas de dinero entre cuentas. */
object MoneyTotals {
    /**
     * El dinero de [accounts] frente a la moneda del negocio [bookCurrency], en el orden recibido.
     * Una cuenta solo se suma si su moneda es la del negocio con los mismos decimales.
     */
    fun overview(accounts: List<MoneyAccount>, bookCurrency: AccountCurrency): MoneyOverview {
        val (same, other) = accounts.partition { it.currency == bookCurrency }
        return MoneyOverview(
            bookCurrency = bookCurrency,
            available = same.map { it.balance }.sumOrNull(),
            inBookCurrency = same,
            inOtherCurrencies = other,
        )
    }
}

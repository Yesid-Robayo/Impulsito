package io.github.yesidrobayo.shopledger.domain.model.money

import io.github.yesidrobayo.shopledger.domain.model.Money
import kotlin.time.Instant

/**
 * Una cuenta de dinero guardada: la caja, un banco o una billetera.
 *
 * - [systemKey] es la clave estable, en inglés, de la cuenta que nace con la base, `CASH`.
 * - [balance] va en la unidad menor de [currency], no en la del negocio. Es la suma de sus
 *   movimientos, y solo la mueve la transacción que agrega uno.
 * - Archivada deja de ofrecerse para registrar dinero, pero su libro sigue completo.
 *
 * Lanza [IllegalArgumentException] si no tiene ni nombre ni clave: no habría con qué mostrarla.
 */
data class MoneyAccount(
    val id: Long,
    val systemKey: String?,
    val details: MoneyAccountDetails,
    val currency: AccountCurrency,
    val balance: Money,
    val archivedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(details.name != null || systemKey != null) {
            "Una cuenta sin nombre necesita su clave inicial"
        }
    }

    /** Si está archivada: no recibe movimientos nuevos de la persona. */
    val isArchived: Boolean get() = archivedAt != null
}

/** Una cuenta por crear, sin saldo: el saldo inicial entra como su primer movimiento. */
data class NewMoneyAccount(
    val details: MoneyAccountDetails,
    val currency: AccountCurrency,
    val createdAt: Instant,
)

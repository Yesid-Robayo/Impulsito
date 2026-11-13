package io.github.yesidrobayo.shopledger.domain.model.money

import io.github.yesidrobayo.shopledger.domain.model.Money

/**
 * Lo que la persona escribe en la hoja de una cuenta de dinero, tal cual, antes de revisarlo.
 *
 * - En la cuenta inicial de efectivo, [name] en blanco conserva el nombre traducido.
 * - [currency] es la moneda de una cuenta nueva; nula, la del negocio. Una cuenta guardada no cambia
 *   de moneda: sus montos quedarían en otra unidad.
 * - [openingBalance] es el dinero que la cuenta nueva ya tiene, en la unidad menor de su moneda. Puede
 *   ser negativo, como un sobregiro. Nulo o cero, la cuenta nace sin movimientos. Al editar no se usa.
 */
data class MoneyAccountDraft(
    val name: String,
    val kind: MoneyAccountKind,
    val currency: AccountCurrency? = null,
    val openingBalance: Money? = null,
)

/** Lo que impide guardar una cuenta de dinero tal como se escribió. */
enum class MoneyAccountInputError {
    /** El nombre queda vacío en una cuenta que no tiene nombre traducido al que volver. */
    NAME_EMPTY,

    /** El nombre pasa de 40 caracteres. */
    NAME_TOO_LONG,

    /** El saldo inicial pasa del tope de un documento, hacia arriba o hacia abajo. */
    OPENING_BALANCE_TOO_LARGE,
}

package io.github.yesidrobayo.shopledger.domain.model.money

import io.github.yesidrobayo.shopledger.domain.model.Money

/**
 * Lo que la persona escribe al mover dinero entre dos de sus cuentas, tal cual, antes de revisarlo.
 *
 * [amount] es lo que sale de [fromAccountId], positivo y en la unidad menor de su moneda. Entre
 * cuentas de monedas distintas, [fxRateMicro] es la cotización que escribió la persona: unidades
 * enteras de la moneda de destino por una unidad entera de la de origen, por un millón. Entre cuentas
 * de la misma moneda no se usa.
 */
data class TransferRequest(
    val fromAccountId: Long,
    val toAccountId: Long,
    val amount: Money,
    val fxRateMicro: Long? = null,
    val note: String? = null,
)

/** Lo que impide transferir tal como se escribió. */
enum class TransferInputError {
    /** El origen y el destino son la misma cuenta. */
    SAME_ACCOUNT,

    /** El monto es cero o negativo. */
    AMOUNT_NOT_POSITIVE,

    /** El monto pasa del tope de un documento. */
    AMOUNT_TOO_LARGE,

    /** Las cuentas tienen monedas distintas y falta la cotización. */
    RATE_REQUIRED,

    /** La cotización es cero, negativa o pasa del tope. */
    RATE_OUT_OF_RANGE,

    /** Con esa cotización, lo que entra pasa del tope de un documento. */
    RECEIVED_TOO_LARGE,

    /** Con esa cotización, lo que entra se redondea a cero: no habría movimiento que escribir. */
    RECEIVED_ZERO,

    /** La nota pasa de 200 caracteres. */
    NOTE_TOO_LONG,
}

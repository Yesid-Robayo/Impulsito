package io.github.yesidrobayo.shopledger.domain.model.money

import io.github.yesidrobayo.shopledger.domain.model.Money

/**
 * Lo que la persona registra a mano en una cuenta, sin documento: dinero que saca o pone ella misma.
 * Ninguno es una venta ni un gasto, así que ninguno toca la ganancia.
 */
enum class ManualMoneyKind(val type: MoneyMovementType) {
    /** Dinero que la persona dueña saca del negocio para su uso. Baja la cuenta, nunca la ganancia. */
    OWNER_DRAW(MoneyMovementType.OWNER_DRAW),

    /** Dinero que la persona dueña pone en el negocio. Sube la cuenta, y no es una venta. */
    OWNER_CONTRIBUTION(MoneyMovementType.OWNER_CONTRIBUTION),
}

/**
 * Lo que la persona escribe al registrar dinero a mano, tal cual, antes de revisarlo.
 *
 * [amount] es lo que se escribió, siempre positivo y en la unidad menor de la moneda de la cuenta:
 * el signo lo pone [kind]. [note] se recorta, y en blanco no se guarda.
 */
data class ManualMoneyRequest(
    val accountId: Long,
    val kind: ManualMoneyKind,
    val amount: Money,
    val note: String? = null,
)

/** Lo que impide registrar dinero a mano tal como se escribió. */
enum class ManualMoneyInputError {
    /** El monto es cero o negativo: el sentido lo da el tipo, no el signo. */
    AMOUNT_NOT_POSITIVE,

    /** El monto pasa del tope de un documento. */
    AMOUNT_TOO_LARGE,

    /** La nota pasa de 200 caracteres. */
    NOTE_TOO_LONG,
}

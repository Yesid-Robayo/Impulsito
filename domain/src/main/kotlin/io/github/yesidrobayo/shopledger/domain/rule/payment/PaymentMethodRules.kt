package io.github.yesidrobayo.shopledger.domain.rule.payment

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodDetails
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodDraft
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodInputError
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer

/**
 * Las reglas de lo que la persona escribe en un método de pago. Son los mismos rangos que imponen
 * las guardas de la base: aquí se revisan antes, para mostrar el error en su campo en vez de una
 * falla de la base.
 *
 * Ninguna regla conoce una marca, una tarifa ni un país: la comisión, los días y el recargo son
 * datos de la persona, y solo se acotan para que las cuentas no se desborden.
 */
object PaymentMethodRules {
    /** Caracteres de un nombre, como los cuenta la base. */
    const val MAX_NAME_LENGTH: Int = 40

    /** Caracteres de las instrucciones de cobro. */
    const val MAX_INSTRUCTIONS_LENGTH: Int = 500

    /** Días para acreditar: hasta diez años. */
    const val MAX_SETTLEMENT_DAYS: Int = 3_650

    /** La comisión en porcentaje va de 0 % a 100 %. */
    val FEE_RATE_RANGE: ClosedRange<BasisPoints> =
        BasisPoints.ZERO..BasisPoints.ONE_HUNDRED_PERCENT

    /** El recargo o descuento va de −100 % a +100 %. */
    val PRICE_ADJUSTMENT_RANGE: ClosedRange<BasisPoints> =
        BasisPoints(-BasisPoints.ONE_HUNDRED_PERCENT.bp)..BasisPoints.ONE_HUNDRED_PERCENT

    /**
     * Revisa [draft] y arma lo que se guardaría.
     *
     * - El nombre y las instrucciones se recortan. Unas instrucciones vacías quedan sin texto.
     * - Un nombre `null` solo vale en un método inicial ([isInitial]): conserva su texto
     *   traducido. En uno de la persona es un nombre vacío.
     * - **A crédito** no tiene comisión, días para acreditar ni cuenta: lo cobrado queda como deuda
     *   de quien compra, no entra a ninguna cuenta. Esos campos se ocultan en la pantalla, así que
     *   aquí se guardan en cero y sin cuenta, aunque traigan lo que se escribió antes de cambiar el
     *   tipo, y no dan error.
     */
    fun check(draft: PaymentMethodDraft, isInitial: Boolean): PaymentMethodCheck {
        val errors = mutableSetOf<PaymentMethodInputError>()
        val name = draft.name?.trim()
        val normalizedName = normalizedNameOf(draft.name)
        val nameError = when {
            name != null -> nameErrorOf(name)
            isInitial -> null
            else -> PaymentMethodInputError.NAME_BLANK
        }
        if (nameError != null) errors += nameError
        val onAccount = draft.kind == PaymentMethodKind.ON_ACCOUNT
        val feeRate = if (onAccount) BasisPoints.ZERO else draft.feeRate
        val feeFixed = if (onAccount) Money.ZERO else draft.feeFixed
        val settlementDays = if (onAccount) 0 else draft.settlementDays
        if (feeRate !in FEE_RATE_RANGE) errors += PaymentMethodInputError.FEE_RATE_OUT_OF_RANGE
        if (feeFixed < Money.ZERO || !Limit.UNIT_MONEY.allows(feeFixed.minor)) {
            errors += PaymentMethodInputError.FEE_FIXED_OUT_OF_RANGE
        }
        if (settlementDays !in 0..MAX_SETTLEMENT_DAYS) {
            errors += PaymentMethodInputError.SETTLEMENT_DAYS_OUT_OF_RANGE
        }
        if (draft.priceAdjustment !in PRICE_ADJUSTMENT_RANGE) {
            errors += PaymentMethodInputError.PRICE_ADJUSTMENT_OUT_OF_RANGE
        }
        val instructions = draft.instructionsText?.let { it.trim().takeIf(String::isNotEmpty) }
        if (instructions != null && codePointCount(instructions) > MAX_INSTRUCTIONS_LENGTH) {
            errors += PaymentMethodInputError.INSTRUCTIONS_TOO_LONG
        }
        if (errors.isNotEmpty()) return PaymentMethodCheck.Invalid(errors)
        return PaymentMethodCheck.Valid(
            PaymentMethodDetails(
                name = name,
                normalizedName = normalizedName,
                kind = draft.kind,
                feeRate = feeRate,
                feeFixed = feeFixed,
                settlementDays = settlementDays,
                priceAdjustment = draft.priceAdjustment,
                moneyAccountId = if (onAccount) null else draft.moneyAccountId,
                instructionsText = instructions,
                instructionsMediaId = null,
            ),
        )
    }

    /**
     * La forma con que se compara el nombre escrito [name], o `null` si no trae nombre o si queda
     * vacío. Es la que se guarda junto al nombre, y la que no se repite entre los vigentes.
     */
    fun normalizedNameOf(name: String?): String? =
        name?.let { TextNormalizer.normalize(it).takeIf(String::isNotEmpty) }

    /** El error de un nombre ya recortado, o `null` si sirve. */
    private fun nameErrorOf(name: String): PaymentMethodInputError? = when {
        TextNormalizer.normalize(name).isEmpty() -> PaymentMethodInputError.NAME_BLANK
        codePointCount(name) > MAX_NAME_LENGTH -> PaymentMethodInputError.NAME_TOO_LONG
        else -> null
    }

    /**
     * Cuántos caracteres cuenta la base en [text]: un emoji o una letra fuera del plano básico son
     * uno, aunque ocupen dos unidades de `String`. Contar unidades rechazaría nombres que la base
     * acepta.
     */
    private fun codePointCount(text: String): Int {
        var count = 0
        var index = 0
        while (index < text.length) {
            val pair = text[index].isHighSurrogate() &&
                index + 1 < text.length &&
                text[index + 1].isLowSurrogate()
            index += if (pair) 2 else 1
            count++
        }
        return count
    }
}

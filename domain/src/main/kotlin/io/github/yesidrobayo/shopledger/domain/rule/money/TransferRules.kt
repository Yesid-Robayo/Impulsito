package io.github.yesidrobayo.shopledger.domain.rule.money

import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.Scale
import io.github.yesidrobayo.shopledger.domain.model.map
import io.github.yesidrobayo.shopledger.domain.model.money.AccountCurrency
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.money.TransferInputError
import io.github.yesidrobayo.shopledger.domain.rule.mulDivRound

/** Resultado de [TransferRules.check]. */
sealed interface TransferCheck {
    /**
     * Se puede transferir: [sent] sale de la cuenta de origen y [received] entra en la de destino,
     * cada uno en la moneda de su cuenta. [fxRateMicro] es la cotización, solo entre monedas
     * distintas, y [note] la nota recortada.
     */
    data class Valid(
        val sent: Money,
        val received: Money,
        val fxRateMicro: Long?,
        val note: String?,
    ) : TransferCheck

    /** No se transfiere nada: cada error va en su campo. */
    data class Invalid(val errors: Set<TransferInputError>) : TransferCheck
}

/**
 * Lo que se revisa de una transferencia entre dos cuentas, y cuánto entra en la de destino.
 *
 * Entre cuentas de la misma moneda, con los mismos decimales, entra lo mismo que sale y la cotización
 * no se usa. Entre monedas distintas la cotización es obligatoria: unidades enteras de la moneda de
 * destino por una unidad entera de la de origen, por un millón, como en el cobro en otra moneda.
 */
object TransferRules {
    /**
     * Revisa [amount], lo que sale de [from], y calcula lo que entra en [to]. [sameAccount] dice si la
     * persona eligió la misma cuenta en los dos lados.
     */
    fun check(
        from: AccountCurrency,
        to: AccountCurrency,
        sameAccount: Boolean,
        amount: Money,
        fxRateMicro: Long?,
        note: String?,
    ): TransferCheck {
        val errors = mutableSetOf<TransferInputError>()
        if (sameAccount) errors += TransferInputError.SAME_ACCOUNT
        if (amount <= Money.ZERO) errors += TransferInputError.AMOUNT_NOT_POSITIVE
        if (!Limit.DOCUMENT_TOTAL.allows(amount.minor)) {
            errors +=
                TransferInputError.AMOUNT_TOO_LARGE
        }
        val trimmed = note?.let { text -> text.trim().ifEmpty { null } }
        if (trimmed != null && trimmed.length > MoneyMovementDraft.NOTE_MAX) {
            errors += TransferInputError.NOTE_TOO_LONG
        }
        val rate = if (from == to) null else fxRateMicro
        if (from != to) {
            when {
                rate == null -> errors += TransferInputError.RATE_REQUIRED

                rate < 1 || !Limit.DOCUMENT_TOTAL.allows(rate) ->
                    errors += TransferInputError.RATE_OUT_OF_RANGE
            }
        }
        if (errors.isNotEmpty()) return TransferCheck.Invalid(errors)
        val received = if (rate == null) {
            amount
        } else {
            when (val converted = convert(amount, from.decimals, to.decimals, rate)) {
                is Checked.Value -> converted.value

                Checked.Overflow, is Checked.AboveLimit ->
                    return TransferCheck.Invalid(setOf(TransferInputError.RECEIVED_TOO_LARGE))
            }
        }
        if (received <= Money.ZERO) {
            return TransferCheck.Invalid(setOf(TransferInputError.RECEIVED_ZERO))
        }
        return TransferCheck.Valid(amount, received, rate, trimmed)
    }

    /**
     * [amount] en unidad menor de una moneda con [fromDecimals], pasado a una con [toDecimals] a la
     * cotización [fxRateMicro], redondeado:
     * `mulDivRound(amount × 10^toDecimals, fxRateMicro, 10^fromDecimals × 1000000)`. Rechaza lo que
     * no cabe o pasa del tope de un documento.
     */
    fun convert(
        amount: Money,
        fromDecimals: Int,
        toDecimals: Int,
        fxRateMicro: Long,
    ): Checked<Money> {
        val scaled = try {
            Math.multiplyExact(amount.minor, powerOfTen(toDecimals))
        } catch (_: ArithmeticException) {
            return Checked.Overflow
        }
        val divisor = powerOfTen(fromDecimals) * Scale.MICRO
        return mulDivRound(scaled, fxRateMicro, divisor, Limit.DOCUMENT_TOTAL).map(::Money)
    }

    /** 10 elevado a [decimals], de 0 a 4: los decimales que admite una moneda guardada. */
    private fun powerOfTen(decimals: Int): Long {
        var result = 1L
        repeat(decimals) { result *= DECIMAL_BASE }
        return result
    }

    private const val DECIMAL_BASE = 10L
}

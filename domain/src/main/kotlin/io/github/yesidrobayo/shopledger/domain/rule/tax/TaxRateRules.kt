package io.github.yesidrobayo.shopledger.domain.rule.tax

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxRateDetails
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxRateDraft
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxRateInputError
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer

/**
 * Las reglas de lo que la persona escribe en una tasa. Son los mismos rangos que impone la guarda
 * de la base: aquí se revisan antes, para mostrar el error en su campo en vez de una falla de la
 * base.
 *
 * Ninguna regla conoce un impuesto ni un país: el nombre y el porcentaje son de la persona, y solo
 * se acotan a lo que tiene sentido para cualquier impuesto.
 */
object TaxRateRules {
    /** Caracteres de un nombre, como los cuenta la base. */
    const val MAX_NAME_LENGTH: Int = 40

    /** Una tasa va de 0 %, para productos exentos, a 100 %. */
    val RATE_RANGE: ClosedRange<BasisPoints> = BasisPoints.ZERO..BasisPoints.ONE_HUNDRED_PERCENT

    /** Revisa [draft] y arma lo que se guardaría, con el nombre recortado. */
    fun check(draft: TaxRateDraft): TaxRateCheck {
        val errors = mutableSetOf<TaxRateInputError>()
        val name = draft.name.trim()
        val normalizedName = TextNormalizer.normalize(name)
        when {
            normalizedName.isEmpty() -> errors += TaxRateInputError.NAME_BLANK
            codePointCount(name) > MAX_NAME_LENGTH -> errors += TaxRateInputError.NAME_TOO_LONG
        }
        if (draft.rate !in RATE_RANGE) errors += TaxRateInputError.RATE_OUT_OF_RANGE
        if (errors.isNotEmpty()) return TaxRateCheck.Invalid(errors)
        return TaxRateCheck.Valid(TaxRateDetails(name, normalizedName, draft.rate))
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

package io.github.yesidrobayo.shopledger.domain.rule.pricing

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.model.settings.ReferenceCurrency
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.rule.money.TransferRules
import io.github.yesidrobayo.shopledger.domain.rule.mulDivRound

/**
 * La moneda de referencia y su cotización manual. La cotización la escribe siempre la persona:
 * nada la descarga, y ningún precio se recalcula solo al escribirla.
 *
 * ```text
 * N = mulDivRound(reference_price × 10^currency_decimals, rate_micro, 10^reference_currency_decimals × 1000000)
 * ```
 *
 * Es la misma cuenta que pasa un monto de una moneda a otra en una transferencia o en un cobro en
 * otra moneda, así que la comparten.
 */
object ReferenceRateRules {
    /** Lo más largo que puede ser la nota de una cotización. */
    const val NOTE_MAX = 200

    /** Lo más que tiene una moneda guardada en decimales. */
    const val MAX_DECIMALS = 4

    private const val BASIS_POINTS = 10_000L

    /** Por qué no se puede elegir una moneda de referencia. */
    enum class ReferenceIssue {
        /** Es la moneda del negocio: no es otra moneda. */
        SAME_AS_BUSINESS,

        /** Los decimales no van de 0 a 4. */
        DECIMALS_OUT_OF_RANGE,
    }

    /** Lo que impide usar [reference] con un negocio en [business], o `null` si se puede. */
    fun referenceIssue(reference: ReferenceCurrency, business: CurrencyCode): ReferenceIssue? =
        when {
            reference.code == business -> ReferenceIssue.SAME_AS_BUSINESS
            reference.decimals !in 0..MAX_DECIMALS -> ReferenceIssue.DECIMALS_OUT_OF_RANGE
            else -> null
        }

    /** Si [rateMicro] es una cotización que se puede guardar: mayor que cero y bajo el tope. */
    fun isValidRate(rateMicro: Long): Boolean =
        rateMicro >= 1L && Limit.DOCUMENT_TOTAL.allows(rateMicro)

    /**
     * Cuánto cambió [current] frente a [previous], en puntos básicos y con signo: de 4000 a 4150
     * son +375. `null` sin anterior.
     */
    fun variation(previous: Long?, current: Long): BasisPoints? {
        if (previous == null || previous <= 0L) return null
        return mulDivRound(
            current - previous,
            BASIS_POINTS,
            previous,
        ).valueOrNull()?.let(::BasisPoints)
    }

    /**
     * Si [current] se aleja más de la mitad de [previous]: casi siempre un cero de más o de menos.
     * Solo avisa antes de guardar, nunca bloquea.
     */
    fun isLargeJump(previous: Long?, current: Long): Boolean {
        if (previous == null || previous <= 0L) return false
        return Math.multiplyExact(kotlin.math.abs(current - previous), 2L) > previous
    }

    /** La nota sin espacios de sobra, o `null` si queda vacía. */
    fun note(text: String?): String? {
        if (text == null) return null
        val trimmed = text.trim()
        return if (trimmed.isEmpty()) null else trimmed
    }

    /**
     * [referencePrice], en unidad menor de [reference], llevado a la moneda del negocio, que tiene
     * [bookDecimals], con la cotización [rateMicro]. Con `1250` de 2 decimales, cotización 4150,50
     * y libro de 0 decimales da `51881`. Rechaza lo que no cabe o pasa del tope de un documento.
     */
    fun toBook(
        referencePrice: Money,
        reference: ReferenceCurrency,
        bookDecimals: Int,
        rateMicro: Long,
    ): Checked<Money> =
        TransferRules.convert(referencePrice, reference.decimals, bookDecimals, rateMicro)
}

package io.github.yesidrobayo.shopledger.domain.rule.report

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.Scale
import io.github.yesidrobayo.shopledger.domain.model.report.RealValue
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.rule.mulDivRound
import kotlinx.datetime.YearMonth
import kotlinx.datetime.plusMonth

/**
 * Llevar una cifra a precios de otro mes con el índice que escribe la persona.
 *
 * Para llevar un valor a precios del mes base, se aplica cada mes posterior, en orden, hasta el
 * mes del valor: `real = mulDivRound(real, 10000, 10000 + cambio del mes)`. Con base `1000000`,
 * comparado `1100000` y un mes de `+3,00 %`, el real es `1067961`, y la variación pasa de 1000 a
 * 680 puntos básicos. Si falta un mes, no se supone nada: se dice cuál falta.
 */
object RealTermsRules {
    /**
     * El cambio más bajo que se acepta, sin incluirlo: `−100 %`. Con él, los precios valdrían cero
     * y no habría por qué dividir.
     */
    val MIN_CHANGE_EXCLUSIVE: BasisPoints = BasisPoints(-10_000L)

    /** El cambio más alto que se acepta: `+10.000 %`, el tope de la tabla. */
    val MAX_CHANGE: BasisPoints = BasisPoints(1_000_000L)

    /** Lo más que admite la fuente de un mes. */
    const val NOTE_MAX: Int = 120

    /** Si [change] es un cambio de mes que se puede guardar y usar. */
    fun isValidChange(change: BasisPoints): Boolean =
        change > MIN_CHANGE_EXCLUSIVE && change <= MAX_CHANGE

    /**
     * [nominal], del mes [month], llevado a precios de [base], con los cambios de [index]. Si
     * [month] no es posterior a [base], no hay nada que descontar.
     */
    fun deflate(
        nominal: Money,
        base: YearMonth,
        month: YearMonth,
        index: Map<YearMonth, BasisPoints>,
    ): RealValue {
        val after = monthsAfter(base, month)
        val missing = after.filter { index[it]?.let(::isValidChange) != true }
        if (missing.isNotEmpty()) return RealValue.MissingMonths(missing)
        var real = nominal.minor
        for (each in after) {
            val divisor = Scale.BASIS_POINTS + index.getValue(each).bp
            real = mulDivRound(real, Scale.BASIS_POINTS, divisor).valueOrNull()
                ?: return RealValue.OutOfRange
        }
        return RealValue.Estimated(Money(real), null)
    }

    /**
     * [nominal] de [month] frente a [baseValue] de [base]: la estimación real con su variación, o
     * por qué no hay.
     */
    fun compare(
        baseValue: Money,
        nominal: Money,
        base: YearMonth,
        month: YearMonth,
        index: Map<YearMonth, BasisPoints>,
    ): RealValue = when (val real = deflate(nominal, base, month, index)) {
        is RealValue.Estimated ->
            real.copy(variation = PeriodComparison.variation(real.value, baseValue))

        is RealValue.MissingMonths, RealValue.OutOfRange -> real
    }

    /**
     * Los meses sin cambio escrito entre el más antiguo y el más reciente de [months], del más
     * antiguo al más reciente. Antes del primero y después del último no falta nada.
     */
    fun missingBetween(months: Collection<YearMonth>): List<YearMonth> {
        val first = months.minOrNull() ?: return emptyList()
        val last = months.max()
        val known = months.toSet()
        return monthsAfter(first, last).filter { it !in known }
    }

    /** Los meses después de [from], en orden, hasta [to] incluido. */
    private fun monthsAfter(from: YearMonth, to: YearMonth): List<YearMonth> {
        val out = mutableListOf<YearMonth>()
        var each = from.plusMonth()
        while (each <= to) {
            out += each
            each = each.plusMonth()
        }
        return out
    }
}

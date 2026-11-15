package io.github.yesidrobayo.shopledger.domain.rule.report

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.Scale
import io.github.yesidrobayo.shopledger.domain.model.report.LimitLevel
import io.github.yesidrobayo.shopledger.domain.model.report.LimitNoticeMark
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.rule.mulDivRound
import kotlinx.datetime.YearMonth
import kotlinx.datetime.minusMonth

/**
 * El límite anual de ingresos que escribe la persona: qué meses suma, cuánto se lleva y a qué
 * nivel de aviso llegó.
 *
 * El monto y los meses son de la persona: la app no conoce ningún tope, régimen ni ley, y el aviso
 * es una orientación. Todo es entero, sin decimales binarios.
 */
object RevenueLimitRules {
    /** El primer aviso: 80 % del límite. */
    val FIRST_NOTICE: BasisPoints = BasisPoints(8_000L)

    /** El segundo aviso: 95 % del límite. */
    val SECOND_NOTICE: BasisPoints = BasisPoints(9_500L)

    /** Lo menos que suma una ventana: el mes en curso. */
    const val MIN_MONTHS: Int = 1

    /** Lo más que se escribe en la hoja: cinco años. Valor de diseño; la base admite hasta 120. */
    const val MAX_MONTHS: Int = 60

    /** El monto más alto que admite un límite, el mismo tope que la base: `MAX_STOCK_VALUE`. */
    val MAX_AMOUNT: Money = Money(Limit.STOCK_VALUE.max)

    /**
     * Los [months] meses que suma la ventana, incluido [current]: del primer día del más viejo al
     * último de [current]. Con 12 en noviembre de 2026, de 2025-12-01 a 2026-11-30.
     */
    fun window(current: YearMonth, months: Int): ReportPeriod.Range {
        require(months >= MIN_MONTHS) { "Una ventana suma al menos un mes" }
        var first = current
        repeat(months - 1) { first = first.minusMonth() }
        return ReportPeriod.Range(BusinessDate(first.firstDay), BusinessDate(current.lastDay))
    }

    /**
     * Cuánto de [limit] se lleva con [accumulated], en puntos básicos. `null` sin lo acumulado,
     * con un límite que no es positivo o si la cuenta no cabe en `Long`.
     */
    fun used(accumulated: Money?, limit: Money): BasisPoints? {
        if (accumulated == null || limit <= Money.ZERO) return null
        return mulDivRound(accumulated.minor, Scale.BASIS_POINTS, limit.minor)
            .valueOrNull()
            ?.let(::BasisPoints)
    }

    /** El nivel al que llega [used]. Sin valor no se avisa nada. */
    fun level(used: BasisPoints?): LimitLevel = when {
        used == null -> LimitLevel.BELOW
        used > BasisPoints.ONE_HUNDRED_PERCENT -> LimitLevel.EXCEEDED
        used >= SECOND_NOTICE -> LimitLevel.SECOND_NOTICE
        used >= FIRST_NOTICE -> LimitLevel.FIRST_NOTICE
        else -> LimitLevel.BELOW
    }

    /**
     * Si hay que avisar [current] como notificación, frente al último aviso [last].
     *
     * Cada nivel avisa una sola vez por ventana: en la misma ventana, solo un nivel más alto que
     * el ya avisado. Otra ventana, otro monto u otros meses empiezan de cero. Bajar de nivel no
     * avisa.
     */
    fun shouldNotify(current: LimitNoticeMark, last: LimitNoticeMark?): Boolean {
        if (current.level == LimitLevel.BELOW) return false
        if (last == null) return true
        val sameWindow =
            last.windowStart == current.windowStart &&
                last.amount == current.amount &&
                last.months == current.months
        return !sameWindow || current.level > last.level
    }

    /** Si [amount] se puede guardar como límite: mayor que cero y sin pasar del tope. */
    fun isValidAmount(amount: Money): Boolean = amount > Money.ZERO && amount <= MAX_AMOUNT

    /** Si [months] se puede escribir en la hoja: de 1 a 60. */
    fun isValidMonths(months: Int): Boolean = months in MIN_MONTHS..MAX_MONTHS
}

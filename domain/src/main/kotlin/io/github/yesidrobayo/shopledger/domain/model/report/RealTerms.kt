package io.github.yesidrobayo.shopledger.domain.model.report

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import kotlinx.datetime.YearMonth

// La comparación en términos reales: el cambio de precios de cada mes lo escribe o lo importa la
// persona, y nunca se descarga. Una cifra real es una estimación, y se rotula como tal.

/**
 * El cambio de precios de un mes, escrito o importado por la persona.
 *
 * @param change la variación del mes, con signo, en puntos básicos: `+1,9 %` son 190.
 * @param sourceNote de dónde la sacó la persona, con sus palabras, o `null`.
 */
data class PriceIndexEntry(val month: YearMonth, val change: BasisPoints, val sourceNote: String?)

/**
 * El índice para `Índice de precios`: los meses, del más reciente al más antiguo, y los que faltan
 * entre el primero y el último, del más antiguo al más reciente.
 */
data class PriceIndexView(val entries: List<PriceIndexEntry>, val missing: List<YearMonth>)

/**
 * Un mes de ventas frente a su comparable, en moneda corriente y a precios del mes del comparable.
 *
 * @param period el mes que se mira.
 * @param comparedTo con qué se compara: el mes anterior o, si el mes está en curso, sus mismos
 *   días.
 * @param base lo vendido sin impuesto en [comparedTo].
 * @param nominal lo vendido sin impuesto en [period].
 * @param nominalVariation la variación de [nominal] frente a [base], o `null` si [base] es cero.
 * @param real [nominal] llevado a precios del mes de [comparedTo], o por qué no se puede.
 */
data class RealComparison(
    val period: ReportPeriod.Month,
    val comparedTo: ReportPeriod,
    val base: Money,
    val nominal: Money,
    val nominalVariation: BasisPoints?,
    val real: RealValue,
)

/** Una cifra llevada a precios de otro mes. */
sealed interface RealValue {
    /**
     * La estimación, con su variación frente a la base, o `null` si la base es cero. Es una
     * estimación: depende del índice que escribió la persona.
     */
    data class Estimated(val value: Money, val variation: BasisPoints?) : RealValue

    /**
     * Faltan, o no sirven, los cambios de [months], del más antiguo al más reciente. Nunca se
     * supone un valor: la pantalla dice cuáles faltan.
     */
    data class MissingMonths(val months: List<YearMonth>) : RealValue

    /** La cifra no cabe en `Long`. La pantalla muestra `—`. */
    data object OutOfRange : RealValue
}

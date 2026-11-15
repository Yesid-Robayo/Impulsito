package io.github.yesidrobayo.shopledger.domain.rule.report

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ProfitSummary
import io.github.yesidrobayo.shopledger.domain.model.Scale
import io.github.yesidrobayo.shopledger.domain.model.report.BreakEven
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.rule.mulDivRound

/**
 * El punto de equilibrio: `roundDiv(gastos fijos × 10000, margen)`, con el margen real del
 * período.
 *
 * Se divide en enteros, con el producto en 128 bits para que unos gastos enormes no desborden. Con
 * margen cero o negativo, o sin ventas, no se divide: ninguna cifra de ventas cubre los gastos con
 * ese margen.
 */
object BreakEvenRules {
    /**
     * El punto de equilibrio con [fixedExpenses] y la ganancia del período, [profit], o `null` si
     * no hay con qué: ni ventas ni gastos fijos, y la herramienta no aparece. [profit] es `null`
     * si una suma del período no cabe en `Long`.
     */
    fun of(fixedExpenses: Money, profit: ProfitSummary?): BreakEven? {
        if (profit == null) return BreakEven.Unavailable
        if (fixedExpenses <= Money.ZERO) {
            return if (profit.netSales > Money.ZERO) BreakEven.NoFixedExpenses else null
        }
        return when (val margin = profit.grossMargin) {
            is Margin.Defined -> calculated(fixedExpenses, margin.value, profit.netSales)
            Margin.NoData -> BreakEven.NoMargin(fixedExpenses)
            Margin.OutOfRange -> BreakEven.Unavailable
        }
    }

    private fun calculated(fixed: Money, margin: BasisPoints, netSales: Money): BreakEven {
        if (margin <= BasisPoints.ZERO) return BreakEven.NoMargin(fixed)
        val sales = mulDivRound(fixed.minor, Scale.BASIS_POINTS, margin.bp).valueOrNull()
            ?: return BreakEven.Unavailable
        val breakEven = Money(sales)
        // Con margen positivo hubo ventas: lo que falta no pasa de lo que hay que vender.
        val remaining = if (netSales >= breakEven) Money.ZERO else breakEven - netSales
        return BreakEven.Calculated(fixed, margin, breakEven, netSales, remaining)
    }
}

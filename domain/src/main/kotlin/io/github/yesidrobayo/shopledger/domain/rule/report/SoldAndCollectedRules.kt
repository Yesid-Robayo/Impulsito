package io.github.yesidrobayo.shopledger.domain.rule.report

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.report.DayCollection
import io.github.yesidrobayo.shopledger.domain.model.report.DayPoint
import io.github.yesidrobayo.shopledger.domain.model.report.SoldAndCollected

/**
 * Las cuentas de vendido y cobrado: la suma del período y lo cobrado de cada día de la gráfica. Si
 * una suma no cabe en `Long`, solo con cifras absurdas, devuelven `null` y la pantalla no muestra
 * la cifra en vez de caerse.
 */
object SoldAndCollectedRules {
    /** La suma de [days], o `null` si desborda. */
    fun total(days: List<DayCollection>): SoldAndCollected? = try {
        val sum = days.fold(SoldAndCollected.ZERO) { sum, day ->
            SoldAndCollected(
                sold = sum.sold + day.sold,
                collectedAtSale = sum.collectedAtSale + day.collectedAtSale,
                collectedOnAccount = sum.collectedOnAccount + day.collectedOnAccount,
            )
        }
        // Lo cobrado se suma aquí una vez: si desborda, el resumen no se muestra.
        sum.also { it.collected }
    } catch (overflow: ArithmeticException) {
        null
    }

    /**
     * Los puntos de [points] con lo cobrado de cada día de [days]; un día sin cobros queda en cero.
     * Devuelve `null` si un día desborda.
     */
    fun withCollected(points: List<DayPoint>, days: List<DayCollection>): List<DayPoint>? = try {
        val byDate = days.associateBy { it.date }
        points.map { point ->
            val day = byDate[point.date]
            point.copy(
                collected = day?.let { it.collectedAtSale + it.collectedOnAccount } ?: Money.ZERO,
            )
        }
    } catch (overflow: ArithmeticException) {
        null
    }
}

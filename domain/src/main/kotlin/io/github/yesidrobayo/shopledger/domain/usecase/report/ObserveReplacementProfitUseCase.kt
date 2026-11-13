package io.github.yesidrobayo.shopledger.domain.usecase.report

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.ReplacementProfit
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.port.PeriodReportRepository
import io.github.yesidrobayo.shopledger.domain.port.ReplacementReportRepository
import io.github.yesidrobayo.shopledger.domain.rule.MarginCalculator
import io.github.yesidrobayo.shopledger.domain.rule.report.ReplacementProfitRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Las dos ganancias de un período, una junto a la otra: la ganancia en ventas, con el costo
 * promedio, que es la verdad del libro, y la ganancia si se repone, con lo que costaría volver a
 * comprar lo vendido. Vuelven a emitir con cada venta, devolución o anulación confirmadas.
 *
 * La ganancia en ventas sale de la misma cuenta del reporte del período, así que es la misma cifra
 * con o sin reposición: la reposición se lee aparte y nunca entra en ella.
 */
class ObserveReplacementProfitUseCase(
    private val reports: PeriodReportRepository,
    private val replacement: ReplacementReportRepository,
) {
    operator fun invoke(period: ReportPeriod): Flow<ReplacementReport> = combine(
        reports.observeTotals(period),
        replacement.observeTotals(period),
    ) { totals, replacementTotals ->
        val sales = MarginCalculator.profit(totals.profit).valueOrNull()
        ReplacementReport(
            period = period,
            grossProfit = sales?.grossProfit,
            replacement = sales?.let {
                ReplacementProfitRules.profit(it.netSales, replacementTotals).valueOrNull()
            },
        )
    }.distinctUntilChanged()
}

/**
 * Las dos ganancias de [period]. [grossProfit] es la ganancia en ventas, y [replacement] la ganancia
 * si se repone; cada una es `null` solo si una cifra no cabe en `Long`, y entonces la pantalla
 * muestra «sin valor».
 */
data class ReplacementReport(
    val period: ReportPeriod,
    val grossProfit: Money?,
    val replacement: ReplacementProfit?,
)

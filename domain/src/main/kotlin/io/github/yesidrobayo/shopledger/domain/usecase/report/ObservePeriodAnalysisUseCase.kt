package io.github.yesidrobayo.shopledger.domain.usecase.report

import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodAnalysis
import io.github.yesidrobayo.shopledger.domain.port.AnalysisReportRepository
import io.github.yesidrobayo.shopledger.domain.port.PeriodBreakdownRepository
import io.github.yesidrobayo.shopledger.domain.rule.report.ProfitConcentrationRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * El análisis de un período: los productos que dejan el 80 % de la ganancia en ventas,
 * los que tienen stock y no se vendieron, y los mejores clientes. Vuelve a emitir con cada cambio
 * confirmado en ventas, devoluciones, productos, clientes o el libro de stock.
 *
 * Lo vendido por producto es la misma suma de `Lo que más se vendió`, con lo devuelto ya restado:
 * así las dos partes de la pantalla nunca dicen cifras distintas.
 */
class ObservePeriodAnalysisUseCase(
    private val breakdowns: PeriodBreakdownRepository,
    private val analysis: AnalysisReportRepository,
) {
    operator fun invoke(period: ReportPeriod): Flow<PeriodAnalysis> = combine(
        breakdowns.observeProductSales(period),
        analysis.observeIdleProducts(period, LIST_LIMIT),
        analysis.observeTopCustomers(period, LIST_LIMIT),
    ) { products, idle, customers ->
        PeriodAnalysis(ProfitConcentrationRules.of(products), idle, customers)
    }.distinctUntilChanged()

    companion object {
        /** Cuántos productos quietos y cuántos clientes se listan, como los rankings. */
        const val LIST_LIMIT: Int = 10
    }
}

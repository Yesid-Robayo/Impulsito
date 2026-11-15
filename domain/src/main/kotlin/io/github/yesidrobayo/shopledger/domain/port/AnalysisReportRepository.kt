package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.CustomerSales
import io.github.yesidrobayo.shopledger.domain.model.report.IdleProducts
import kotlinx.coroutines.flow.Flow

/**
 * Lo que el análisis de `Números` lee de un período, además de lo vendido por producto, que ya lee
 * [PeriodBreakdownRepository]. Solo lee, con sumas y conteos en la base: nunca carga las ventas
 * del período a memoria.
 */
interface AnalysisReportRepository {
    /**
     * Los productos vigentes con stock que no tuvieron ninguna salida por venta sin anular en
     * [period], los primeros [limit] por valor del stock, y cuántos son en total. Los insumos no
     * cuentan: no se venden. Vuelve a emitir con cada cambio en productos o en el libro de stock.
     */
    fun observeIdleProducts(period: ReportPeriod, limit: Int): Flow<IdleProducts>

    /**
     * Los primeros [limit] clientes por lo vendido sin impuesto en las ventas activas de
     * [period], de mayor a menor, con un empate por id. Vuelve a emitir con cada cambio en ventas
     * o clientes.
     */
    fun observeTopCustomers(period: ReportPeriod, limit: Int): Flow<List<CustomerSales>>
}

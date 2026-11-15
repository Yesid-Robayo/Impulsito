package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.CustomerSales
import io.github.yesidrobayo.shopledger.domain.model.report.IdleProduct
import io.github.yesidrobayo.shopledger.domain.model.report.IdleProducts
import io.github.yesidrobayo.shopledger.domain.port.AnalysisReportRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Lo que el análisis de `Números` lee de un período, sobre Room. Solo lee: cada consulta vuelve a
 * emitir con cada cambio confirmado en las tablas que lee, y no repite un resultado igual.
 */
internal class RoomAnalysisReportRepository(database: AppDatabase) : AnalysisReportRepository {
    private val reports = database.analysisReportDao()

    override fun observeIdleProducts(period: ReportPeriod, limit: Int): Flow<IdleProducts> =
        combine(
            reports.observeIdleProducts(period.storedStart, period.storedEnd, limit),
            reports.observeIdleProductsCount(period.storedStart, period.storedEnd),
        ) { rows, total ->
            IdleProducts(
                products = rows.map { row ->
                    IdleProduct(
                        productId = row.productId,
                        name = row.name,
                        baseUnitId = row.baseUnitId,
                        onHand = QuantityMilli(row.onHandMilli),
                        stockValue = Money(row.stockValue),
                    )
                },
                total = total,
            )
        }.distinctUntilChanged()

    override fun observeTopCustomers(period: ReportPeriod, limit: Int): Flow<List<CustomerSales>> =
        reports.observeTopCustomers(period.storedStart, period.storedEnd, limit)
            .map { rows ->
                rows.map { row ->
                    CustomerSales(
                        customerId = row.customerId,
                        name = row.name,
                        salesCount = row.salesCount,
                        netSales = Money(row.netSales),
                    )
                }
            }
            .distinctUntilChanged()
}

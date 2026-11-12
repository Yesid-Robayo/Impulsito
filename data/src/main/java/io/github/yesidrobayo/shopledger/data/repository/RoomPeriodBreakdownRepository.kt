package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.report.ExpenseCategoryTotal
import io.github.yesidrobayo.shopledger.domain.model.report.PaymentMethodTotal
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodNoticeCounts
import io.github.yesidrobayo.shopledger.domain.model.report.ProductSales
import io.github.yesidrobayo.shopledger.domain.port.PeriodBreakdownRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Los desgloses de un período, sobre Room. Solo lee: cada consulta vuelve a emitir con cada cambio
 * confirmado en las tablas que lee, y no repite un resultado igual al anterior.
 */
internal class RoomPeriodBreakdownRepository(database: AppDatabase) : PeriodBreakdownRepository {
    private val breakdowns = database.periodBreakdownDao()

    override fun observeProductSales(period: ReportPeriod): Flow<List<ProductSales>> = breakdowns
        .observeProductSales(period.storedStart, period.storedEnd)
        .map { rows ->
            rows.map {
                ProductSales(
                    productId = it.productId,
                    name = it.name,
                    baseUnitId = it.baseUnitId,
                    quantity = QuantityMilli(it.quantityMilli),
                    netSales = Money(it.netSales),
                    cost = Money(it.cost),
                )
            }
        }
        .distinctUntilChanged()

    override fun observePaymentMethods(period: ReportPeriod): Flow<List<PaymentMethodTotal>> =
        breakdowns
            .observePaymentMethods(period.storedStart, period.storedEnd)
            .map { rows ->
                rows.map {
                    PaymentMethodTotal(
                        name = it.methodName,
                        // Un tipo desconocido, de una copia más nueva, cuenta como otro.
                        kind = PaymentMethodKind.fromStoredOrOther(it.methodKind),
                        salesCount = it.salesCount,
                        amount = Money(it.amount),
                        fees = Money(it.fees),
                    )
                }
            }
            .distinctUntilChanged()

    override fun observeExpenseCategories(period: ReportPeriod): Flow<List<ExpenseCategoryTotal>> =
        breakdowns
            .observeExpenseCategories(period.storedStart, period.storedEnd)
            .map { rows ->
                rows.map {
                    ExpenseCategoryTotal(it.categoryId, it.name, it.systemKey, Money(it.amount))
                }
            }
            .distinctUntilChanged()

    override fun observeNoticeCounts(
        period: ReportPeriod,
        periodEnd: Instant,
    ): Flow<PeriodNoticeCounts> = breakdowns
        .observeNoticeCounts(period.storedStart, period.storedEnd, periodEnd.toStoredMillis())
        .map {
            PeriodNoticeCounts(
                salesWithoutCost = it.salesWithoutCost,
                negativeStock = it.negativeStock,
                voidedAfterPeriod = it.voidedAfterPeriod,
                lateRecords = it.lateRecords,
                voidedCount = it.voidedCount,
            )
        }
        .distinctUntilChanged()
}

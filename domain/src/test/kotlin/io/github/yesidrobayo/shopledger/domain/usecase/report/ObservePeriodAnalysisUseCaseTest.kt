package io.github.yesidrobayo.shopledger.domain.usecase.report

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.CustomerSales
import io.github.yesidrobayo.shopledger.domain.model.report.ExpenseCategoryTotal
import io.github.yesidrobayo.shopledger.domain.model.report.IdleProduct
import io.github.yesidrobayo.shopledger.domain.model.report.IdleProducts
import io.github.yesidrobayo.shopledger.domain.model.report.PaymentMethodTotal
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodNoticeCounts
import io.github.yesidrobayo.shopledger.domain.model.report.ProductSales
import io.github.yesidrobayo.shopledger.domain.model.report.ProfitConcentration
import io.github.yesidrobayo.shopledger.domain.port.AnalysisReportRepository
import io.github.yesidrobayo.shopledger.domain.port.PeriodBreakdownRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * El análisis junta lo vendido por producto, los productos quietos y los mejores clientes del
 * período pedido, con los límites de la pantalla. Nombres y montos inventados.
 */
class ObservePeriodAnalysisUseCaseTest {
    private val period = ReportPeriod.Day(BusinessDate(LocalDate(2026, 11, 2)))
    private val products = MutableStateFlow(emptyList<ProductSales>())
    private val asked = mutableListOf<Pair<ReportPeriod, Int>>()

    private val breakdowns =
        object : PeriodBreakdownRepository {
            override fun observeProductSales(period: ReportPeriod): Flow<List<ProductSales>> =
                products

            override fun observePaymentMethods(period: ReportPeriod) =
                flowOf(emptyList<PaymentMethodTotal>())

            override fun observeExpenseCategories(period: ReportPeriod) =
                flowOf(emptyList<ExpenseCategoryTotal>())

            override fun observeNoticeCounts(period: ReportPeriod, periodEnd: Instant) =
                flowOf(PeriodNoticeCounts.NONE)
        }

    private val analysis =
        object : AnalysisReportRepository {
            override fun observeIdleProducts(period: ReportPeriod, limit: Int) =
                flowOf(IDLE).also { asked += period to limit }

            override fun observeTopCustomers(period: ReportPeriod, limit: Int) =
                flowOf(listOf(BUYER)).also { asked += period to limit }
        }

    private val useCase = ObservePeriodAnalysisUseCase(breakdowns, analysis)

    @Test
    fun `the analysis joins the three parts of the period with the list limit`() = runTest {
        products.value =
            listOf(
                ProductSales(1, "Café", 1, QuantityMilli(1_000), Money(9_000), Money(1_000)),
                ProductSales(2, "Té", 1, QuantityMilli(1_000), Money(3_000), Money(1_000)),
            )

        val found = useCase(period).first()

        // 8000 de 10000 dejan el café: llega solo al 80 %.
        val leaders = found.concentration as ProfitConcentration.Leaders
        assertEquals(listOf(1L), leaders.leaders.map { it.productId })
        assertEquals(BasisPoints(8_000), leaders.reachedShare)
        assertEquals(IDLE, found.idle)
        assertEquals(listOf(BUYER), found.customers)
        assertEquals(List(2) { period to 10 }, asked)
    }

    @Test
    fun `a period without sales says so and keeps the other parts`() = runTest {
        val found = useCase(period).map { it.concentration }.first()

        assertEquals(ProfitConcentration.NoSales, found)
    }

    private companion object {
        val IDLE = IdleProducts(
            listOf(IdleProduct(3, "Yerba", 1, QuantityMilli(4_000), Money(8_000))),
            total = 1,
        )
        val BUYER = CustomerSales(7, "Cliente de prueba", 2, Money(12_000))
    }
}

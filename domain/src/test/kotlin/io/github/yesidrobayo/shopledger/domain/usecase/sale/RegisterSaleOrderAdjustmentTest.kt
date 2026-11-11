package io.github.yesidrobayo.shopledger.domain.usecase.sale

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSale
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSaleLine
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePaymentRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.rule.MarginCalculator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El descuento o recargo que la persona escribe para toda la venta: se reparte entre las líneas en
 * proporción a lo que cobra cada una, paga impuesto con ellas y los pagos lo cubren. Los ejemplos
 * de las reglas de negocio, escritos a mano, y en cada venta las partes suman el ajuste exacto y el
 * total es lo neto más el impuesto.
 */
class RegisterSaleOrderAdjustmentTest {
    private val world = SaleWorld().apply {
        addMethod(method())
        addMethod(
            method(id = CARD, kind = PaymentMethodKind.CARD, systemKey = "CARD", feeBp = 275),
        )
        addMethod(
            method(
                id = SURCHARGED,
                kind = PaymentMethodKind.CARD,
                systemKey = null,
                name = "Invented card",
                priceAdjustmentBp = 500,
            ),
        )
    }

    private fun untracked(id: Long, price: Long, unitCost: Long = 0L, taxRateId: Long? = null) =
        world.addProduct(
            product(
                id,
                salePrice = price,
                stockMode = ProductStockMode.UNTRACKED,
                fixedUnitCost = unitCost,
                taxRateId = taxRateId,
            ),
        )

    /** Una unidad de cada producto, a su precio. */
    private fun sale(
        vararg productIds: Long,
        adjustment: Long,
        methodId: Long = SaleWorld.CASH,
        payments: List<Pair<Long, Long>> = emptyList(),
    ) = SaleRequest(
        lines = productIds.map { SaleLineRequest(it, QuantityMilli.ofUnits(1L)) },
        paymentMethodId = methodId,
        payments = payments.map { (method, amount) -> SalePaymentRequest(method, Money(amount)) },
        orderAdjustment = Money(adjustment),
    )

    private suspend fun register(request: SaleRequest): Long {
        val result = world.registerSale()(request)
        return (result as RegisterSaleResult.Registered).saleId
    }

    private suspend fun rejectionOf(request: SaleRequest): RegisterSaleResult {
        val result = world.registerSale()(request)
        assertEquals("Un rechazo no confirma nada", 0, world.commits)
        assertTrue(world.tables.sales.isEmpty())
        assertTrue(world.tables.lines.isEmpty())
        assertTrue(world.tables.payments.isEmpty())
        return result
    }

    private fun headerOf(saleId: Long): NewSale = world.tables.sales.getValue(saleId).content

    private fun linesOf(saleId: Long): List<NewSaleLine> = world.tables.lines.values
        .filter { it.saleId == saleId }
        .sortedBy { it.id }
        .map { it.content }

    private fun paymentsOf(saleId: Long): List<Long> = world.tables.payments.values
        .filter { it.saleId == saleId }
        .sortedBy { it.id }
        .map { it.content.amount.minor }

    /**
     * Que las partes sumen el ajuste de la cabecera, cada total sea la suma de sus líneas con el
     * total igual a lo neto más el impuesto, y los pagos sumen lo que se cobra.
     */
    private fun assertSaleAddsUp(saleId: Long) {
        val sale = headerOf(saleId)
        val lines = linesOf(saleId)
        fun sum(of: (NewSaleLine) -> Money) = lines.fold(Money.ZERO) { total, it -> total + of(it) }
        assertEquals(sale.orderAdjustment, sum { it.orderAdjustmentShare })
        assertEquals(sale.netTotal, sum { it.netAmount })
        assertEquals(sale.taxTotal, sum { it.taxAmount })
        assertEquals(sale.total, sum { it.lineTotal })
        assertEquals(sale.total, sale.netTotal + sale.taxTotal)
        assertEquals(sale.amountDue.minor, paymentsOf(saleId).sum())
    }

    @Test
    fun theWorkedExampleSharesTheDiscountInProportionToEachLine() = runTest {
        untracked(1, price = 7_000, unitCost = 3_000)
        untracked(2, price = 6_000)
        untracked(3, price = 18_000)

        // −1000 sobre 7000, 6000 y 18000: −225,8 da −226 y −193,5 da −194, y la línea de mayor
        // base absorbe el resto, −580.
        val saleId = register(sale(1, 2, 3, adjustment = -1_000))

        val lines = linesOf(saleId)
        assertEquals(listOf(-226L, -194L, -580L), lines.map { it.orderAdjustmentShare.minor })
        assertEquals(listOf(6_774L, 5_806L, 17_420L), lines.map { it.lineTotal.minor })
        val sale = headerOf(saleId)
        assertEquals(Money(-1_000), sale.orderAdjustment)
        assertEquals(Money(30_000), sale.total)
        assertEquals(listOf(30_000L), paymentsOf(saleId))
        // El margen de la línea se mide sobre lo que de verdad cobró: (6774 − 3000) / 6774.
        assertEquals(
            Margin.Defined(BasisPoints(5_571)),
            MarginCalculator.margin(lines[0].netAmount, lines[0].lineCost),
        )
        assertSaleAddsUp(saleId)
    }

    @Test
    fun aSurchargeIsSharedTheSameWayAndPaysTax() = runTest {
        world.taxRates = mapOf(1L to taxRate(1, "Rate A", 1_000))
        world.setTaxes(TaxMode.ADDED, defaultTaxRateId = 1)
        untracked(1, price = 7_000)
        untracked(2, price = 6_000)
        untracked(3, price = 18_000)

        // +1000: 226, 194 y 580 dejan 7226, 6194 y 18580, y el 10 % añadido de cada una es 722,6,
        // 619,4 y 1858.
        val saleId = register(sale(1, 2, 3, adjustment = 1_000))

        val lines = linesOf(saleId)
        assertEquals(listOf(226L, 194L, 580L), lines.map { it.orderAdjustmentShare.minor })
        assertEquals(listOf(7_226L, 6_194L, 18_580L), lines.map { it.netAmount.minor })
        assertEquals(listOf(723L, 619L, 1_858L), lines.map { it.taxAmount.minor })
        val sale = headerOf(saleId)
        assertEquals(Money(32_000), sale.netTotal)
        assertEquals(Money(3_200), sale.taxTotal)
        assertEquals(Money(35_200), sale.total)
        assertSaleAddsUp(saleId)
    }

    @Test
    fun theCompleteExampleWithIncludedTaxAndTwoPaymentsAddsUp() = runTest {
        // El ejemplo de la suma de pagos: 19 % incluido, 2 unidades a 1190 y 1,500 kg a 2000, un
        // descuento de toda la venta de 100 y costo total 2500. El redondeo del efectivo todavía no
        // se calcula, así que el efectivo paga 2279 y no 2280.
        world.taxRates = mapOf(1L to taxRate(1, "Rate A", 1_900))
        world.setTaxes(TaxMode.INCLUDED, defaultTaxRateId = 1)
        untracked(1, price = 1_190, unitCost = 500)
        world.addProduct(
            product(
                2,
                salePrice = 2_000,
                stockMode = ProductStockMode.UNTRACKED,
                unitId = SaleWorld.KILOGRAM,
                fixedUnitCost = 1_000,
            ),
        )

        val saleId = register(
            SaleRequest(
                lines = listOf(
                    SaleLineRequest(1, QuantityMilli(2_000)),
                    SaleLineRequest(2, QuantityMilli(1_500)),
                ),
                paymentMethodId = SaleWorld.CASH,
                payments = listOf(
                    SalePaymentRequest(CARD, Money(3_001)),
                    SalePaymentRequest(SaleWorld.CASH, Money(2_279)),
                ),
                orderAdjustment = Money(-100),
            ),
        )

        val lines = linesOf(saleId)
        // −100 sobre 2380 y 3000: −44,2 da −44, y la de mayor base absorbe −56.
        assertEquals(listOf(-44L, -56L), lines.map { it.orderAdjustmentShare.minor })
        assertEquals(listOf(1_963L, 2_474L), lines.map { it.netAmount.minor })
        assertEquals(listOf(373L, 470L), lines.map { it.taxAmount.minor })
        assertEquals(listOf(2_336L, 2_944L), lines.map { it.lineTotal.minor })
        val sale = headerOf(saleId)
        assertEquals(Money(5_280), sale.total)
        assertEquals(Money(4_437), sale.netTotal)
        assertEquals(Money(843), sale.taxTotal)
        assertEquals(Money(2_500), sale.totalCost)
        // La ganancia en ventas, 4437 − 2500, y la comisión de la tarjeta al 2,75 % de 3001.
        assertEquals(Money(1_937), sale.netTotal - sale.totalCost)
        assertEquals(
            Money(83),
            world.tables.payments.values.single { it.content.amount == Money(3_001) }
                .content.feeAmount,
        )
        assertSaleAddsUp(saleId)
    }

    @Test
    fun theWrittenPaymentsMustCoverTheAdjustedTotal() = runTest {
        untracked(1, price = 7_000)
        untracked(2, price = 6_000)
        untracked(3, price = 18_000)

        // Pagar el total sin el descuento no cuadra.
        assertEquals(
            RegisterSaleResult.PaymentsDoNotMatch(Money(30_000), Money(31_000)),
            rejectionOf(
                sale(
                    1,
                    2,
                    3,
                    adjustment = -1_000,
                    payments = listOf(SaleWorld.CASH to 11_000, CARD to 20_000),
                ),
            ),
        )

        val saleId = register(
            sale(
                1,
                2,
                3,
                adjustment = -1_000,
                payments = listOf(SaleWorld.CASH to 10_000, CARD to 20_000),
            ),
        )

        assertEquals(listOf(10_000L, 20_000L), paymentsOf(saleId))
        assertSaleAddsUp(saleId)
    }

    @Test
    fun theWrittenAdjustmentAndAMethodSurchargeAreSharedTogether() = runTest {
        untracked(1, price = 10_000)

        // Con −1000 se cobran 9000, y la tarjeta suma su 5 % sobre eso: 450. El ajuste de la venta
        // es −1000 + 450 = −550, y el pago cubre 9450.
        val saleId = register(sale(1, adjustment = -1_000, methodId = SURCHARGED))

        val sale = headerOf(saleId)
        assertEquals(Money(-550), sale.orderAdjustment)
        assertEquals(Money(-550), linesOf(saleId).single().orderAdjustmentShare)
        assertEquals(Money(9_450), sale.total)
        assertEquals(listOf(9_450L), paymentsOf(saleId))
        assertSaleAddsUp(saleId)
    }

    @Test
    fun aDiscountEqualToTheLinesLeavesTheSaleAtZero() = runTest {
        untracked(1, price = 7_000)
        untracked(2, price = 6_000)
        untracked(3, price = 18_000)

        val saleId = register(sale(1, 2, 3, adjustment = -31_000))

        assertEquals(
            listOf(-7_000L, -6_000L, -18_000L),
            linesOf(saleId).map { it.orderAdjustmentShare.minor },
        )
        assertEquals(Money.ZERO, headerOf(saleId).total)
        assertSaleAddsUp(saleId)
    }

    @Test
    fun aDiscountAboveTheLinesIsRejected() = runTest {
        untracked(1, price = 7_000)
        untracked(2, price = 6_000)
        untracked(3, price = 18_000)

        assertEquals(
            RegisterSaleResult.OrderDiscountTooLarge,
            rejectionOf(sale(1, 2, 3, adjustment = -31_001)),
        )
        // Un descuento más allá del tope de un documento también.
        assertEquals(
            RegisterSaleResult.OrderDiscountTooLarge,
            rejectionOf(sale(1, adjustment = -Limit.DOCUMENT_TOTAL.max - 1)),
        )
        assertEquals(
            RegisterSaleResult.OrderDiscountTooLarge,
            rejectionOf(sale(1, adjustment = Long.MIN_VALUE)),
        )
    }

    @Test
    fun aSurchargeAboveTheDocumentLimitIsRejected() = runTest {
        untracked(1, price = 7_000)

        assertEquals(
            RegisterSaleResult.SaleTotalTooLarge,
            rejectionOf(sale(1, adjustment = Limit.DOCUMENT_TOTAL.max)),
        )
        assertEquals(
            RegisterSaleResult.SaleTotalTooLarge,
            rejectionOf(sale(1, adjustment = Long.MAX_VALUE)),
        )
    }

    @Test
    fun aSurchargeOnFreeLinesGoesToTheLastLine() = runTest {
        untracked(1, price = 0)
        untracked(2, price = 0)

        val saleId = register(sale(1, 2, adjustment = 500))

        assertEquals(listOf(0L, 500L), linesOf(saleId).map { it.orderAdjustmentShare.minor })
        assertEquals(Money(500), headerOf(saleId).total)
        assertSaleAddsUp(saleId)
    }

    @Test
    fun aDiscountOnFreeLinesIsRejected() = runTest {
        untracked(1, price = 0)

        assertEquals(
            RegisterSaleResult.OrderDiscountTooLarge,
            rejectionOf(sale(1, adjustment = -1)),
        )
    }

    private companion object {
        const val CARD = 2L
        const val SURCHARGED = 3L
    }
}

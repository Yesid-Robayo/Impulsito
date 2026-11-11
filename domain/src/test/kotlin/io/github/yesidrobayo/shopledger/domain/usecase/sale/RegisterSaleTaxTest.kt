package io.github.yesidrobayo.shopledger.domain.usecase.sale

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.sale.LineDiscount
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSale
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSaleLine
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePaymentRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.rule.MarginCalculator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El impuesto de una venta, línea por línea: los ejemplos de las reglas de negocio escritos a mano,
 * con los tres modos y con productos de tasas distintas, y en cada venta el total igual a lo neto
 * más el impuesto, con cada total igual a la suma de sus líneas.
 */
class RegisterSaleTaxTest {
    private val world = SaleWorld().apply { addMethod(method()) }

    private suspend fun sell(vararg lines: SaleLineRequest): Long {
        val result = world.registerSale()(SaleRequest(lines.toList(), SaleWorld.CASH))
        return (result as RegisterSaleResult.Registered).saleId
    }

    private fun line(
        productId: Long,
        milli: Long = 1_000,
        unitPrice: Long? = null,
        discount: LineDiscount = LineDiscount.None,
    ) = SaleLineRequest(productId, QuantityMilli(milli), unitPrice?.let(::Money), discount)

    private fun untracked(id: Long, price: Long, taxRateId: Long? = null, unitCost: Long = 0L) =
        world.addProduct(
            product(
                id,
                salePrice = price,
                stockMode = ProductStockMode.UNTRACKED,
                fixedUnitCost = unitCost,
                taxRateId = taxRateId,
            ),
        )

    private fun headerOf(saleId: Long): NewSale = world.tables.sales.getValue(saleId).content

    private fun linesOf(saleId: Long): List<NewSaleLine> = world.tables.lines.values
        .filter { it.saleId == saleId }
        .sortedBy { it.id }
        .map { it.content }

    /** Neto, impuesto y total de cada línea, en orden. */
    private fun splitsOf(saleId: Long): List<List<Long>> = linesOf(saleId).map {
        listOf(it.netAmount.minor, it.taxAmount.minor, it.lineTotal.minor)
    }

    /** Que la cabecera sea la suma de sus líneas, y su total lo neto más el impuesto. */
    private fun assertTotalsAddUp(saleId: Long) {
        val sale = headerOf(saleId)
        val lines = linesOf(saleId)
        assertEquals(sale.total, sale.netTotal + sale.taxTotal)
        assertEquals(lines.fold(Money.ZERO) { sum, it -> sum + it.netAmount }, sale.netTotal)
        assertEquals(lines.fold(Money.ZERO) { sum, it -> sum + it.taxAmount }, sale.taxTotal)
        assertEquals(lines.fold(Money.ZERO) { sum, it -> sum + it.lineTotal }, sale.total)
        assertEquals(lines.fold(Money.ZERO) { sum, it -> sum + it.lineCost }, sale.totalCost)
        for (line in lines) assertEquals(line.lineTotal, line.netAmount + line.taxAmount)
    }

    @Test
    fun theWorkedExamplesOfTheLineTaxGiveTheirNetTaxAndTotal() = runTest {
        // Las filas de la regla de cálculo por línea. Los decimales de la moneda no cambian la
        // cuenta: todo va en unidad menor.
        val rows = listOf(
            Example(TaxMode.INCLUDED, lineAfter = 12_100, rateBp = 2_100, 10_000, 2_100, 12_100),
            Example(TaxMode.ADDED, lineAfter = 10_000, rateBp = 2_100, 10_000, 2_100, 12_100),
            Example(TaxMode.INCLUDED, lineAfter = 999, rateBp = 1_900, 839, 160, 999),
            Example(TaxMode.ADDED, lineAfter = 1_999, rateBp = 1_900, 1_999, 380, 2_379),
            Example(TaxMode.ADDED, lineAfter = 1_999, rateBp = 825, 1_999, 165, 2_164),
            Example(TaxMode.ADDED, lineAfter = 1_999, rateBp = 500, 1_999, 100, 2_099),
        )
        for ((index, row) in rows.withIndex()) {
            val rateId = index + 1L
            val rate = taxRate(rateId, "Rate $rateId", row.rateBp)
            world.taxRates = world.taxRates + (rateId to rate)
            world.setTaxes(row.mode)
            untracked(id = rateId, price = row.lineAfter, taxRateId = rateId)

            val saleId = sell(line(rateId))

            val stored = linesOf(saleId).single()
            val expected = listOf(row.net, row.tax, row.total)
            assertEquals("Fila $index", expected, splitsOf(saleId).single())
            assertEquals(BasisPoints(row.rateBp), stored.taxRate)
            assertEquals("Rate $rateId", stored.taxNameSnapshot)
            assertEquals(Money(row.total), headerOf(saleId).amountDue)
            assertEquals(Money(row.total), world.tables.payments.values.last().content.amount)
            assertTotalsAddUp(saleId)
        }
    }

    @Test
    fun addedTaxWithDifferentRatesAddsEachLineOnItsOwn() = runTest {
        world.taxRates = mapOf(
            1L to taxRate(1, "Rate A", 2_100),
            2L to taxRate(2, "Rate B", 500),
            3L to taxRate(3, "Exempt", 0),
        )
        world.setTaxes(TaxMode.ADDED, defaultTaxRateId = 2)
        untracked(1, price = 10_000, taxRateId = 1)
        // Sin tasa propia, paga la tasa por defecto.
        untracked(2, price = 1_999)
        untracked(3, price = 3_000, taxRateId = 3)

        val saleId = sell(line(1), line(2), line(3))

        assertEquals(
            listOf(
                listOf(10_000L, 2_100L, 12_100L),
                listOf(1_999L, 100L, 2_099L),
                listOf(3_000L, 0L, 3_000L),
            ),
            splitsOf(saleId),
        )
        val lines = linesOf(saleId)
        assertEquals(listOf(2_100L, 500L, 0L), lines.map { it.taxRate.bp })
        assertEquals(listOf("Rate A", "Rate B", "Exempt"), lines.map { it.taxNameSnapshot })
        val sale = headerOf(saleId)
        assertEquals(Money(14_999), sale.netTotal)
        assertEquals(Money(2_200), sale.taxTotal)
        assertEquals(Money(17_199), sale.total)
        assertEquals(Money(17_199), sale.amountDue)
        assertEquals(Money(17_199), world.tables.payments.values.single().content.amount)
        assertTotalsAddUp(saleId)
    }

    @Test
    fun includedTaxWithDifferentRatesKeepsEachWrittenPrice() = runTest {
        world.taxRates = mapOf(
            1L to taxRate(1, "Rate A", 2_100),
            2L to taxRate(2, "Rate B", 1_900),
            3L to taxRate(3, "Exempt", 0),
        )
        world.setTaxes(TaxMode.INCLUDED, defaultTaxRateId = 2)
        untracked(1, price = 12_100, taxRateId = 1)
        untracked(2, price = 999)
        untracked(3, price = 3_000, taxRateId = 3)
        world.addProduct(
            product(
                4,
                salePrice = 2_000,
                stockMode = ProductStockMode.UNTRACKED,
                unitId = SaleWorld.KILOGRAM,
                taxRateId = 2,
            ),
        )

        // 1,500 kg a 2000 son 3000, y su neto sale hacia atrás: 3000 / 1,19 es 2521,008.
        val saleId = sell(line(1), line(2), line(3), line(4, milli = 1_500))

        assertEquals(
            listOf(
                listOf(10_000L, 2_100L, 12_100L),
                listOf(839L, 160L, 999L),
                listOf(3_000L, 0L, 3_000L),
                listOf(2_521L, 479L, 3_000L),
            ),
            splitsOf(saleId),
        )
        val sale = headerOf(saleId)
        assertEquals(Money(16_360), sale.netTotal)
        assertEquals(Money(2_739), sale.taxTotal)
        assertEquals(Money(19_099), sale.total)
        assertTotalsAddUp(saleId)
    }

    @Test
    fun theTaxIsCalculatedOnTheLineAfterItsDiscount() = runTest {
        world.taxRates = mapOf(1L to taxRate(1, "Rate A", 2_100))
        world.setTaxes(TaxMode.INCLUDED)
        untracked(1, price = 12_100, taxRateId = 1)

        // 2 a 12100 son 24200; con 2000 de descuento quedan 22200, y 22200 / 1,21 es 18347,1.
        val saleId = sell(line(1, milli = 2_000, discount = LineDiscount.Amount(Money(2_000))))

        assertEquals(listOf(listOf(18_347L, 3_853L, 22_200L)), splitsOf(saleId))
        assertEquals(Money(2_000), linesOf(saleId).single().lineDiscount)
        assertTotalsAddUp(saleId)
    }

    @Test
    fun withoutTaxTheLineCopiesNoRateEvenIfTheProductHasOne() = runTest {
        world.taxRates = mapOf(1L to taxRate(1, "Rate A", 2_100))
        world.setTaxes(TaxMode.NONE, defaultTaxRateId = 1)
        untracked(1, price = 12_100, taxRateId = 1)
        untracked(2, price = 500)

        val saleId = sell(line(1), line(2))

        assertEquals(listOf(listOf(12_100L, 0L, 12_100L), listOf(500L, 0L, 500L)), splitsOf(saleId))
        assertTrue(linesOf(saleId).all { it.taxRate == BasisPoints.ZERO })
        assertTrue(linesOf(saleId).all { it.taxNameSnapshot == null })
        assertEquals(Money.ZERO, headerOf(saleId).taxTotal)
        // Sin impuesto no hace falta leer ninguna tasa.
        assertEquals(0, world.taxRateReads)
        assertTotalsAddUp(saleId)
    }

    @Test
    fun aProductWithoutRateAndNoDefaultPaysNoTax() = runTest {
        world.setTaxes(TaxMode.ADDED)
        untracked(1, price = 1_999)

        val saleId = sell(line(1))

        val stored = linesOf(saleId).single()
        assertEquals(listOf(1_999L, 0L, 1_999L), splitsOf(saleId).single())
        assertEquals(BasisPoints.ZERO, stored.taxRate)
        assertNull(stored.taxNameSnapshot)
        assertEquals(0, world.taxRateReads)
        assertTotalsAddUp(saleId)
    }

    @Test
    fun theMarginOfASaleWithTaxIncludedIsMeasuredWithoutTheTax() = runTest {
        world.taxRates = mapOf(1L to taxRate(1, "Rate A", 2_100))
        world.setTaxes(TaxMode.INCLUDED)
        untracked(1, price = 12_100, taxRateId = 1, unitCost = 8_000)

        val saleId = sell(line(1))

        val sale = headerOf(saleId)
        assertEquals(Money(10_000), sale.netTotal)
        assertEquals(Money(8_000), sale.totalCost)
        // Sobre la base sin impuesto es 20,0 %; sobre el total sería 33,9 %, una ganancia falsa.
        assertEquals(
            Margin.Defined(BasisPoints(2_000)),
            MarginCalculator.margin(sale.netTotal, sale.totalCost),
        )
        assertEquals(
            Margin.Defined(BasisPoints(3_388)),
            MarginCalculator.margin(sale.total, sale.totalCost),
        )
    }

    @Test
    fun anArchivedRateStillAppliesToTheProductThatUsesIt() = runTest {
        world.taxRates = mapOf(1L to taxRate(1, "Old rate", 1_000, archived = true))
        world.setTaxes(TaxMode.ADDED)
        untracked(1, price = 5_000, taxRateId = 1)

        val saleId = sell(line(1))

        assertEquals(listOf(5_000L, 500L, 5_500L), splitsOf(saleId).single())
        assertEquals("Old rate", linesOf(saleId).single().taxNameSnapshot)
    }

    @Test
    fun eachRateIsReadOncePerSale() = runTest {
        world.taxRates = mapOf(1L to taxRate(1, "Rate A", 2_100), 2L to taxRate(2, "Rate B", 500))
        world.setTaxes(TaxMode.ADDED, defaultTaxRateId = 2)
        untracked(1, price = 100, taxRateId = 1)
        untracked(2, price = 100, taxRateId = 1)
        untracked(3, price = 100)
        untracked(4, price = 100)

        sell(line(1), line(2), line(3), line(4), line(1))

        assertEquals(2, world.taxRateReads)
    }

    @Test
    fun theFrozenRateDoesNotFollowLaterChangesToTheRate() = runTest {
        world.taxRates = mapOf(1L to taxRate(1, "Rate A", 2_100))
        world.setTaxes(TaxMode.ADDED)
        untracked(1, price = 10_000, taxRateId = 1)
        val saleId = sell(line(1))

        world.taxRates = mapOf(1L to taxRate(1, "Renamed", 500))
        val later = sell(line(1))

        assertEquals("Rate A", linesOf(saleId).single().taxNameSnapshot)
        assertEquals(Money(2_100), headerOf(saleId).taxTotal)
        assertEquals("Renamed", linesOf(later).single().taxNameSnapshot)
        assertEquals(Money(500), headerOf(later).taxTotal)
    }

    @Test
    fun splitPaymentsMustAddUpToTheTotalWithTax() = runTest {
        world.addMethod(method(id = 2, kind = PaymentMethodKind.CARD, systemKey = "CARD"))
        world.taxRates = mapOf(1L to taxRate(1, "Rate A", 2_100))
        world.setTaxes(TaxMode.ADDED)
        untracked(1, price = 10_000, taxRateId = 1)
        fun paying(vararg amounts: Pair<Long, Long>) = SaleRequest(
            listOf(line(1)),
            SaleWorld.CASH,
            payments =
                amounts.map { (method, amount) -> SalePaymentRequest(method, Money(amount)) },
        )

        // Pagar solo lo neto deja fuera el impuesto: no se confirma.
        assertEquals(
            RegisterSaleResult.PaymentsDoNotMatch(Money(12_100), Money(10_000)),
            world.registerSale()(paying(SaleWorld.CASH to 6_000, 2L to 4_000)),
        )
        assertTrue(world.tables.sales.isEmpty())

        val result = world.registerSale()(paying(SaleWorld.CASH to 6_000, 2L to 6_100))

        val saleId = (result as RegisterSaleResult.Registered).saleId
        assertEquals(Money(12_100), headerOf(saleId).amountDue)
        assertEquals(
            listOf(6_000L, 6_100L),
            world.tables.payments.values.map { it.content.amount.minor },
        )
        assertTotalsAddUp(saleId)
    }

    @Test
    fun aMethodSurchargePaysTaxAndEachPaymentCoversWhatItsSurchargeAdds() = runTest {
        // El ejemplo de la acreditación: una línea de 10000 con 19 % añadido da 11900, pagado con
        // 7000 con tarjeta a +5,00 % y 4900 con billetera a −2,00 %. Recargos 350 y −98: con 350
        // la línea cobra 10350, su impuesto 1967 y el total 12317, y la tarjeta cubre 7417; con
        // 252, 10252, 1948 y 12200, y la billetera cubre 4783.
        world.addMethod(method(id = 2, kind = PaymentMethodKind.CARD, priceAdjustmentBp = 500))
        world.addMethod(method(id = 3, kind = PaymentMethodKind.WALLET, priceAdjustmentBp = -200))
        world.taxRates = mapOf(1L to taxRate(1, "Rate A", 1_900))
        world.setTaxes(TaxMode.ADDED)
        untracked(1, price = 10_000, taxRateId = 1)

        val result = world.registerSale()(
            SaleRequest(
                listOf(line(1)),
                SaleWorld.CASH,
                payments = listOf(
                    SalePaymentRequest(2, Money(7_000)),
                    SalePaymentRequest(3, Money(4_900)),
                ),
            ),
        )

        val saleId = (result as RegisterSaleResult.Registered).saleId
        val stored = linesOf(saleId).single()
        assertEquals(Money(252), stored.orderAdjustmentShare)
        assertEquals(listOf(10_252L, 1_948L, 12_200L), splitsOf(saleId).single())
        val sale = headerOf(saleId)
        assertEquals(Money(252), sale.orderAdjustment)
        assertEquals(Money(12_200), sale.amountDue)
        assertEquals(
            listOf(7_417L, 4_783L),
            world.tables.payments.values.map { it.content.amount.minor },
        )
        assertTotalsAddUp(saleId)
    }

    @Test
    fun aMissingRateIsADamagedDatabase() = runTest {
        world.setTaxes(TaxMode.ADDED, defaultTaxRateId = 9)
        untracked(1, price = 100)

        val result = world.registerSale()(SaleRequest(listOf(line(1)), SaleWorld.CASH))

        assertEquals(RegisterSaleResult.StorageFailed(StorageFailure.INVARIANT_VIOLATION), result)
        assertTrue(world.tables.sales.isEmpty())
        assertEquals(0, world.commits)
    }

    @Test
    fun addedTaxThatPushesALineAboveTheLimitIsRejected() = runTest {
        world.taxRates = mapOf(1L to taxRate(1, "Rate A", 1))
        world.setTaxes(TaxMode.ADDED)
        world.addProduct(
            product(
                1,
                stockMode = ProductStockMode.UNTRACKED,
                unitId = SaleWorld.KILOGRAM,
                taxRateId = 1,
            ),
        )
        world.addProduct(product(2, salePrice = 100, stockMode = ProductStockMode.UNTRACKED))

        // La primera línea llega justo al tope sin impuesto, y su impuesto la pasa.
        val result = world.registerSale()(
            SaleRequest(listOf(line(2), line(1, 1_000_000_000, 1_000_000_000)), SaleWorld.CASH),
        )

        assertEquals(RegisterSaleResult.LineRejected(1, LineRejection.AMOUNT_TOO_LARGE), result)
        assertTrue(world.tables.sales.isEmpty())
    }

    @Test
    fun addedTaxThatPushesTheSaleAboveTheLimitIsRejected() = runTest {
        world.taxRates = mapOf(1L to taxRate(1, "Rate A", 1_000))
        world.setTaxes(TaxMode.ADDED)
        world.addProduct(
            product(
                1,
                stockMode = ProductStockMode.UNTRACKED,
                unitId = SaleWorld.KILOGRAM,
                taxRateId = 1,
            ),
        )
        // Dos netos que juntos llegan justo al tope: el impuesto pasa el total por encima.
        val half = line(1, 500_000_000, 1_000_000_000)

        val result = world.registerSale()(SaleRequest(listOf(half, half), SaleWorld.CASH))

        assertEquals(RegisterSaleResult.SaleTotalTooLarge, result)
        assertTrue(world.tables.sales.isEmpty())
    }

    /** Una fila de los ejemplos del cálculo por línea, con su resultado escrito a mano. */
    private data class Example(
        val mode: TaxMode,
        val lineAfter: Long,
        val rateBp: Long,
        val net: Long,
        val tax: Long,
        val total: Long,
    )
}

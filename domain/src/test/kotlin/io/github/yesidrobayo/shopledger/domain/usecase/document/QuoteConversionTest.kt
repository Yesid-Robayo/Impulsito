package io.github.yesidrobayo.shopledger.domain.usecase.document

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductUsage
import io.github.yesidrobayo.shopledger.domain.model.document.QuoteStatus
import io.github.yesidrobayo.shopledger.domain.model.sale.LineDiscount
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.testing.FixedClock
import io.github.yesidrobayo.shopledger.domain.usecase.sale.InjectedSaleFailure
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.SaleWorld
import io.github.yesidrobayo.shopledger.domain.usecase.sale.method
import io.github.yesidrobayo.shopledger.domain.usecase.sale.product
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * De cotización a venta: preparar la conversión avisa del precio que cambió, de lo archivado y de
 * la vigencia vencida, y registrar la venta la deja convertida en la misma transacción, una sola
 * vez. Nombres y montos inventados.
 */
class QuoteConversionTest {
    private val world = SaleWorld().apply {
        addMethod(method())
        addProduct(product(1, salePrice = 2_500, onHandMilli = 10_000, averageCost = 900))
        addProduct(product(2, salePrice = 500, onHandMilli = 5_000, averageCost = 200))
        addCustomer(CUSTOMER)
    }

    private fun line(productId: Long, milli: Long) =
        SaleLineRequest(productId, QuantityMilli(milli))

    private suspend fun quote(
        vararg lines: SaleLineRequest,
        customerId: Long? = CUSTOMER,
        validUntil: BusinessDate? = null,
    ): Long {
        val saved = world.saveQuote()(QuoteRequest(lines.toList(), customerId, validUntil))
        return (saved as SaveQuoteResult.Saved).quoteId
    }

    private suspend fun prepare(id: Long) = world.prepareQuoteConversion()(id)

    private suspend fun ready(id: Long) = (prepare(id) as QuoteConversionResult.Ready).conversion

    private suspend fun sell(requests: List<SaleLineRequest>, quoteId: Long?, customer: Long?) =
        world.registerSale()(
            SaleRequest(requests, SaleWorld.CASH, customerId = customer, quoteId = quoteId),
        )

    @Test
    fun anUnchangedQuoteConvertsWithItsLinesAndCustomer() = runTest {
        val id = quote(
            line(1, 2_000).copy(discount = LineDiscount.Amount(Money(300))),
            line(2, 1_000),
        )

        val conversion = ready(id)

        assertEquals(CUSTOMER, conversion.customerId)
        assertEquals(0, conversion.changedPrices)
        assertEquals(0, conversion.archivedLines)
        assertNull(conversion.expiredOn)
        assertEquals(
            listOf(
                SaleLineRequest(
                    1,
                    QuantityMilli(2_000),
                    Money(2_500),
                    LineDiscount.Amount(Money(300)),
                    unitId = SaleWorld.UNIT,
                ),
                SaleLineRequest(2, QuantityMilli(1_000), Money(500), unitId = SaleWorld.UNIT),
            ),
            conversion.requests(useCurrentPrices = false),
        )
    }

    @Test
    fun aChangedPriceIsCountedAndThePersonChoosesWhichPriceToSell() = runTest {
        val id = quote(line(1, 1_000), line(2, 1_000))
        world.addProduct(product(1, salePrice = 2_800, onHandMilli = 10_000, averageCost = 900))

        val conversion = ready(id)

        assertEquals(1, conversion.changedPrices)
        val changed = conversion.lines.first()
        assertEquals(Money(2_500), changed.quotedPrice)
        assertEquals(Money(2_800), changed.currentPrice)
        // Con el precio cotizado la venta lleva el congelado; con el de hoy, ninguno escrito.
        assertEquals(Money(2_500), conversion.requests(false).first().unitPrice)
        assertNull(conversion.requests(true).first().unitPrice)

        assertTrue(sell(conversion.requests(false), id, CUSTOMER) is RegisterSaleResult.Registered)
        assertEquals(Money(3_000), world.tables.sales.values.single().content.total)
    }

    @Test
    fun archivedProductsAreLeftOutAndCounted() = runTest {
        val id = quote(line(1, 1_000), line(2, 1_000))
        world.addProduct(product(2, salePrice = 500, archived = true))

        val conversion = ready(id)

        assertEquals(1, conversion.archivedLines)
        assertEquals(listOf(1L), conversion.lines.map { it.quoted.productId })

        world.addProduct(product(1, salePrice = 2_500, archived = true))
        assertEquals(QuoteConversionResult.NothingToConvert, prepare(id))
    }

    @Test
    fun aProductThatIsNowAnInputIsLeftOutAndCountedApart() = runTest {
        // Se cotizó cuando se vendía; después pasó a insumo. La cotización guardada no cambia.
        val id = quote(line(1, 1_000), line(2, 1_000))
        world.addProduct(product(2, salePrice = 500, usage = ProductUsage.INPUT))

        val conversion = ready(id)

        assertEquals(0, conversion.archivedLines)
        assertEquals(1, conversion.notSoldLines)
        assertEquals(listOf(1L), conversion.lines.map { it.quoted.productId })
        assertEquals(2, world.tables.quoteLines.size)
        assertTrue(sell(conversion.requests(false), id, CUSTOMER) is RegisterSaleResult.Registered)

        // Con todo archivado o insumo no queda nada que vender.
        val other = quote(line(1, 1_000))
        world.addProduct(product(1, salePrice = 2_500, usage = ProductUsage.INPUT))
        assertEquals(QuoteConversionResult.NothingToConvert, prepare(other))
    }

    @Test
    fun anExpiredQuoteStillConvertsAfterTheNotice() = runTest {
        val validUntil = BusinessDate(LocalDate(2026, 11, 3))
        val id = quote(line(1, 1_000), validUntil = validUntil)

        assertNull(ready(id).expiredOn)
        world.clock = FixedClock.atEpochMillis(SaleWorld.NOW_MILLIS + 2 * DAY_MILLIS)

        assertEquals(validUntil, ready(id).expiredOn)
    }

    @Test
    fun anArchivedCustomerIsDroppedAndTheSaleGoesWithoutOne() = runTest {
        val id = quote(line(1, 1_000))
        world.addCustomer(CUSTOMER, archived = true)

        val conversion = ready(id)

        assertNull(conversion.customerId)
        assertTrue(conversion.customerDropped)
    }

    @Test
    fun registeringTheSaleConvertsTheQuoteInTheSameTransaction() = runTest {
        val id = quote(line(1, 3_000))
        val before = world.tables.changeSeq

        val result = sell(ready(id).requests(false), id, CUSTOMER)

        val saleId = (result as RegisterSaleResult.Registered).saleId
        val sale = world.tables.sales.getValue(saleId)
        assertEquals(id, sale.content.quoteId)
        val converted = world.tables.quotes.getValue(id)
        assertEquals(QuoteStatus.CONVERTED, converted.status)
        assertEquals(saleId, converted.convertedSaleId)
        // La venta es la que mueve el stock, no la cotización.
        assertEquals(QuantityMilli(7_000), world.tables.products.getValue(1).onHand)
        assertEquals(before + 1, world.tables.changeSeq)
    }

    @Test
    fun aQuoteConvertsOnlyOnce() = runTest {
        val id = quote(line(1, 1_000))
        val requests = ready(id).requests(false)
        sell(requests, id, CUSTOMER)

        assertEquals(QuoteConversionResult.NotOpen(QuoteStatus.CONVERTED), prepare(id))
        assertEquals(RegisterSaleResult.QuoteUnavailable, sell(requests, id, CUSTOMER))
        assertEquals(1, world.tables.sales.size)
        assertEquals(QuantityMilli(9_000), world.tables.products.getValue(1).onHand)
    }

    @Test
    fun aCancelledOrMissingQuoteIsNotConverted() = runTest {
        val id = quote(line(1, 1_000))
        val requests = ready(id).requests(false)
        world.cancelQuote()(id)

        assertEquals(QuoteConversionResult.NotOpen(QuoteStatus.CANCELLED), prepare(id))
        assertEquals(QuoteConversionResult.NotFound, prepare(99))
        assertEquals(RegisterSaleResult.QuoteUnavailable, sell(requests, id, CUSTOMER))
        assertEquals(RegisterSaleResult.QuoteUnavailable, sell(requests, 99, CUSTOMER))
        assertTrue(world.tables.sales.isEmpty())
        assertTrue(world.tables.movements.isEmpty())
    }

    @Test
    fun aFailureInAnyWriteUndoesTheSaleAndKeepsTheQuoteOpen() = runTest {
        val id = quote(line(1, 1_000))
        val requests = ready(id).requests(false)
        var failAt = 1
        while (true) {
            world.failAtWrite = failAt
            val failed = runCatching { sell(requests, id, CUSTOMER) }.exceptionOrNull()
            if (failed !is InjectedSaleFailure) break
            assertTrue(world.tables.sales.isEmpty())
            assertEquals(QuoteStatus.OPEN, world.tables.quotes.getValue(id).status)
            assertEquals(QuantityMilli(10_000), world.tables.products.getValue(1).onHand)
            failAt++
        }
        // La cotización se marca en la última escritura: también esa falla se probó.
        assertTrue(failAt > 1)
        assertEquals(QuoteStatus.CONVERTED, world.tables.quotes.getValue(id).status)
        assertFalse(world.tables.sales.isEmpty())
    }
    private companion object {
        const val CUSTOMER = 30L
        const val DAY_MILLIS = 86_400_000L
    }
}

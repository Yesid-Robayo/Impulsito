package io.github.yesidrobayo.shopledger.domain.usecase.document

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductConversion
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.document.QuoteStatus
import io.github.yesidrobayo.shopledger.domain.model.sale.LineDiscount
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.testing.FixedClock
import io.github.yesidrobayo.shopledger.domain.usecase.sale.InjectedSaleFailure
import io.github.yesidrobayo.shopledger.domain.usecase.sale.SaleWorld
import io.github.yesidrobayo.shopledger.domain.usecase.sale.measureUnit
import io.github.yesidrobayo.shopledger.domain.usecase.sale.product
import io.github.yesidrobayo.shopledger.domain.usecase.sale.taxRate
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guardar una cotización: congela precios e impuesto como la venta los calcularía hoy, sin tocar
 * stock, dinero ni reportes, y rechaza lo que no puede guardar con su motivo. Los valores esperados
 * están calculados a mano. Nombres y montos inventados.
 */
class SaveQuoteUseCaseTest {
    private val world = SaleWorld().apply {
        addProduct(product(1, salePrice = 2_500, onHandMilli = 10_000, averageCost = 900))
        addProduct(product(2, salePrice = 500))
        addCustomer(CUSTOMER)
    }

    private fun line(productId: Long, milli: Long, price: Long? = null) =
        SaleLineRequest(productId, QuantityMilli(milli), price?.let(::Money))

    private suspend fun save(request: QuoteRequest) = world.saveQuote()(request)

    private fun assertNothingWritten() {
        assertTrue(world.tables.quotes.isEmpty())
        assertTrue(world.tables.quoteLines.isEmpty())
        assertEquals(0L, world.tables.changeSeq)
    }

    @Test
    fun aQuoteFreezesPricesAndTaxWithoutTouchingStockMoneyOrReports() = runTest {
        world.taxRates = mapOf(7L to taxRate(7, "Rate A", 1_900))
        world.setTaxes(TaxMode.ADDED, defaultTaxRateId = 7)

        val result = save(QuoteRequest(listOf(line(1, 3_000)), customerId = CUSTOMER))

        assertEquals(SaveQuoteResult.Saved(1), result)
        val quote = world.tables.quotes.getValue(1)
        assertEquals(QuoteStatus.OPEN, quote.status)
        assertEquals(CUSTOMER, quote.customerId)
        // 3 × 2500 = 7500 neto; 19 % añadido = 1425; total 8925.
        assertEquals(Money(7_500), quote.totals.net)
        assertEquals(Money(1_425), quote.totals.tax)
        assertEquals(Money(8_925), quote.totals.total)
        assertEquals(BusinessDate(LocalDate(2026, 11, 2)), quote.businessDate)
        val line = world.tables.quoteLines.values.single().content
        assertEquals(Money(2_500), line.unitPrice)
        assertEquals(QuantityMilli(3_000), line.quantity)
        assertEquals(BasisPoints(1_900), line.taxRate)
        assertEquals("Rate A", line.taxNameSnapshot)
        assertEquals("Product 1", line.productNameSnapshot)
        // Ni stock, ni ventas, ni pagos, ni libros.
        assertTrue(world.tables.movements.isEmpty())
        assertTrue(world.tables.sales.isEmpty())
        assertTrue(world.tables.payments.isEmpty())
        assertTrue(world.tables.customerEntries.isEmpty())
        assertTrue(world.tables.moneyMovements.isEmpty())
        assertEquals(QuantityMilli(10_000), world.tables.products.getValue(1).onHand)
        // Es un hecho del negocio: cuenta como cambio sin respaldar.
        assertEquals(1L, world.tables.changeSeq)
    }

    @Test
    fun includedTaxIsTakenOutOfTheWrittenPrice() = runTest {
        world.taxRates = mapOf(7L to taxRate(7, "Rate A", 1_900))
        world.setTaxes(TaxMode.INCLUDED, defaultTaxRateId = 7)

        save(QuoteRequest(listOf(line(1, 1_000, price = 11_900))))

        // 11900 × 10000 / 11900 = 10000 neto, y 1900 de impuesto.
        val quote = world.tables.quotes.getValue(1)
        assertEquals(Money(10_000), quote.totals.net)
        assertEquals(Money(1_900), quote.totals.tax)
        assertEquals(Money(11_900), quote.totals.total)
    }

    @Test
    fun aBoxIsPricedWithItsFactorAndReservesBaseUnits() = runTest {
        world.units = world.units + (BOX to measureUnit(BOX, "BOX", UnitKind.COUNT, false))
        world.conversions = listOf(
            ProductConversion(201, 2, ConversionDetails(BOX, 24_000, null, null, true, true)),
        )

        save(QuoteRequest(listOf(SaleLineRequest(2, QuantityMilli(2_000), unitId = BOX))))

        // Una caja de 24 a 500 cuesta 12000; dos cajas son 48 unidades y 24000.
        val line = world.tables.quoteLines.values.single().content
        assertEquals(BOX, line.unitId)
        assertEquals(QuantityMilli(24_000), line.unitFactor)
        assertEquals(QuantityMilli(2_000), line.enteredQuantity)
        assertEquals(QuantityMilli(48_000), line.quantity)
        assertEquals(Money(12_000), line.unitPrice)
        assertEquals(Money(24_000), line.lineTotal)
    }

    @Test
    fun aPercentDiscountIsKeptAsAnAmountAndAWrittenPriceWins() = runTest {
        val request = QuoteRequest(
            listOf(
                line(1, 3_000).copy(discount = LineDiscount.Percent(BasisPoints(1_000))),
                line(2, 1_000, price = 450),
            ),
        )

        save(request)

        val lines = world.tables.quoteLines.values.sortedBy { it.id }.map { it.content }
        // El 10 % de 7500 es 750: la línea cobra 6750.
        assertEquals(Money(750), lines[0].lineDiscount)
        assertEquals(Money(6_750), lines[0].lineTotal)
        assertEquals(Money(450), lines[1].unitPrice)
        assertEquals(Money(7_200), world.tables.quotes.getValue(1).totals.total)
    }

    @Test
    fun combosAndProductsWithLotsAreQuotedByTheirPriceWithoutReadingStock() = runTest {
        world.withCombos = false
        world.withLots = false
        world.addProduct(product(3, salePrice = 4_000, stockMode = ProductStockMode.COMPONENTS))
        world.addProduct(product(4, salePrice = 1_200, tracksLots = true))

        val result = save(QuoteRequest(listOf(line(3, 1_000), line(4, 2_000))))

        assertEquals(SaveQuoteResult.Saved(1), result)
        assertEquals(Money(6_400), world.tables.quotes.getValue(1).totals.total)
    }

    @Test
    fun anExtraOrAChosenLotIsRejectedWithItsReason() = runTest {
        val extra = QuoteRequest(listOf(line(1, 1_000), line(2, 1_000).copy(parentIndex = 0)))
        val lot = QuoteRequest(listOf(line(1, 1_000).copy(lotId = 5)))

        assertEquals(
            SaveQuoteResult.LineRejected(1, DocumentLineRejection.EXTRA_NOT_SUPPORTED),
            save(extra),
        )
        assertEquals(
            SaveQuoteResult.LineRejected(0, DocumentLineRejection.LOT_NOT_SUPPORTED),
            save(lot),
        )
        assertNothingWritten()
    }

    @Test
    fun eachLineRuleRejectsWithItsReasonAndWritesNothing() = runTest {
        world.addProduct(product(5, archived = true))
        world.addProduct(product(6, salePrice = 100))
        world.addProduct(
            product(7, salePrice = 100).let {
                it.copy(details = it.details.copy(parentProductId = 6))
            },
        )

        val cases = mapOf(
            line(99, 1_000) to DocumentLineRejection.PRODUCT_NOT_FOUND,
            line(5, 1_000) to DocumentLineRejection.PRODUCT_ARCHIVED,
            line(6, 1_000) to DocumentLineRejection.PRODUCT_HAS_VARIANTS,
            line(1, 1_500) to DocumentLineRejection.FRACTION_NOT_ALLOWED,
            line(1, 0) to DocumentLineRejection.INVALID_QUANTITY,
            line(1, 1_000, price = -1) to DocumentLineRejection.INVALID_PRICE,
            line(1, 1_000).copy(discount = LineDiscount.Amount(Money(2_501))) to
                DocumentLineRejection.INVALID_DISCOUNT,
            SaleLineRequest(1, QuantityMilli(1_000), unitId = BOX) to
                DocumentLineRejection.UNIT_UNAVAILABLE,
        )
        for ((request, reason) in cases) {
            assertEquals(
                "$reason",
                SaveQuoteResult.LineRejected(0, reason),
                save(QuoteRequest(listOf(request))),
            )
        }
        assertNothingWritten()
    }

    @Test
    fun theShapeAndTheHeaderAreCheckedBeforeWriting() = runTest {
        val yesterday = BusinessDate(LocalDate(2026, 11, 1))
        world.addCustomer(ARCHIVED_CUSTOMER, archived = true)

        assertEquals(SaveQuoteResult.EmptyQuote, save(QuoteRequest(emptyList())))
        assertEquals(
            SaveQuoteResult.TooManyLines,
            save(QuoteRequest(List(201) { line(1, 1_000) })),
        )
        assertEquals(
            SaveQuoteResult.NoteTooLong,
            save(QuoteRequest(listOf(line(1, 1_000)), note = "n".repeat(201))),
        )
        assertEquals(
            SaveQuoteResult.ValidUntilInPast,
            save(QuoteRequest(listOf(line(1, 1_000)), validUntil = yesterday)),
        )
        assertEquals(
            SaveQuoteResult.CustomerUnavailable,
            save(QuoteRequest(listOf(line(1, 1_000)), customerId = ARCHIVED_CUSTOMER)),
        )
        assertEquals(
            SaveQuoteResult.PriceListUnavailable,
            save(QuoteRequest(listOf(line(1, 1_000)), priceListId = 42)),
        )
        assertEquals(
            SaveQuoteResult.QuoteNotOpen,
            save(QuoteRequest(listOf(line(1, 1_000)), quoteId = 9)),
        )
        assertNothingWritten()
    }

    @Test
    fun todayIsAValidValidityAndABlankNoteIsNotSaved() = runTest {
        val today = BusinessDate(LocalDate(2026, 11, 2))

        save(QuoteRequest(listOf(line(1, 1_000)), validUntil = today, note = "  "))

        val quote = world.tables.quotes.getValue(1)
        assertEquals(today, quote.validUntil)
        assertNull(quote.note)
    }

    @Test
    fun theFirstQuoteLocksTheCurrencyAndAnUnconfirmedOneRejects() = runTest {
        val unlocked = SaleWorld(currencyLocked = false)
        unlocked.addProduct(product(1, salePrice = 100))
        unlocked.saveQuote()(QuoteRequest(listOf(line(1, 1_000))))
        assertNotNull(unlocked.settings.settings.value.regional.currency.lockedAt)

        val unconfirmed = SaleWorld(currencyConfirmed = false)
        unconfirmed.addProduct(product(1, salePrice = 100))
        assertEquals(
            SaveQuoteResult.CurrencyNotConfirmed,
            unconfirmed.saveQuote()(QuoteRequest(listOf(line(1, 1_000)))),
        )
        assertTrue(unconfirmed.tables.quotes.isEmpty())
    }

    @Test
    fun editingAnOpenQuoteRewritesItsLinesAndKeepsWhenItWasQuoted() = runTest {
        save(QuoteRequest(listOf(line(1, 1_000), line(2, 1_000))))
        val first = world.tables.quotes.getValue(1)
        world.clock = FixedClock.atEpochMillis(SaleWorld.NOW_MILLIS + DAY_MILLIS)

        val result = save(
            QuoteRequest(listOf(line(2, 4_000)), customerId = CUSTOMER, quoteId = 1),
        )

        assertEquals(SaveQuoteResult.Saved(1), result)
        val edited = world.tables.quotes.getValue(1)
        assertEquals(first.businessDate, edited.businessDate)
        assertEquals(first.occurredAt, edited.occurredAt)
        assertEquals(CUSTOMER, edited.customerId)
        assertEquals(Money(2_000), edited.totals.total)
        assertEquals(listOf(2L), world.tables.quoteLines.values.map { it.content.productId })
    }

    @Test
    fun aConvertedOrCancelledQuoteIsNotEdited() = runTest {
        save(QuoteRequest(listOf(line(1, 1_000))))
        world.cancelQuote()(1)

        assertEquals(
            SaveQuoteResult.QuoteNotOpen,
            save(QuoteRequest(listOf(line(2, 1_000)), quoteId = 1)),
        )
        assertEquals(listOf(1L), world.tables.quoteLines.values.map { it.content.productId })
    }

    @Test
    fun aFailureInAnyWriteLeavesNothing() = runTest {
        val request = QuoteRequest(listOf(line(1, 1_000), line(2, 1_000)))
        // Una cabecera, su id y dos líneas con sus ids.
        for (failAt in 1..6) {
            world.failAtWrite = failAt
            val failed = runCatching { save(request) }
            assertTrue("Escritura $failAt", failed.exceptionOrNull() is InjectedSaleFailure)
            assertNothingWritten()
        }
        world.failAtWrite = 1
        world.failure = { StorageFailureException(StorageFailure.DISK_FULL) }
        assertEquals(SaveQuoteResult.StorageFailed(StorageFailure.DISK_FULL), save(request))
        assertNothingWritten()
    }

    @Test
    fun cancellingKeepsTheQuoteInTheHistoryOnlyOnce() = runTest {
        save(QuoteRequest(listOf(line(1, 1_000))))

        assertEquals(CancelQuoteResult.Cancelled, world.cancelQuote()(1))
        assertEquals(CancelQuoteResult.NotOpen, world.cancelQuote()(1))
        assertEquals(CancelQuoteResult.NotOpen, world.cancelQuote()(9))
        assertEquals(QuoteStatus.CANCELLED, world.tables.quotes.getValue(1).status)
        assertEquals(1, world.tables.quoteLines.size)
    }

    private companion object {
        const val CUSTOMER = 30L
        const val ARCHIVED_CUSTOMER = 31L
        const val BOX = 9L
        const val DAY_MILLIS = 86_400_000L
    }
}

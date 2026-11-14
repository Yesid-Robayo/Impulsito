package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.document.QuoteStatus
import io.github.yesidrobayo.shopledger.domain.model.sale.LineDiscount
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodNames
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.document.CancelQuoteResult
import io.github.yesidrobayo.shopledger.domain.usecase.document.CancelQuoteUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.document.PrepareQuoteConversionUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.document.QuoteConversionResult
import io.github.yesidrobayo.shopledger.domain.usecase.document.QuoteRequest
import io.github.yesidrobayo.shopledger.domain.usecase.document.SaveQuoteResult
import io.github.yesidrobayo.shopledger.domain.usecase.document.SaveQuoteUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleUseCase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las cotizaciones sobre Room, con sus guardas y el runner de la app: guardar no toca el libro de
 * stock ni el de dinero, editar rehace las líneas, y la venta que sale de una cotización la deja
 * convertida en su misma transacción, una sola vez. Productos y montos inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class QuotesOnDatabaseTest : RepositoryTest() {
    private val products by lazy { RoomProductRepository(database) }
    private val quotes by lazy { RoomQuoteRepository(database) }
    private val customers by lazy { RoomCustomerRepository(database) }
    private val settings by lazy { RoomAppSettingsRepository(database, RestoreLock { false }) }
    private val clock = Clock { AT }

    private val save by lazy {
        SaveQuoteUseCase(
            transactions = runner,
            settings = settings,
            quotes = quotes,
            customers = customers,
            clock = clock,
            products = products,
            units = RoomUnitRepository(database),
            taxRates = RoomTaxRateRepository(database),
            systemRows = RoomSystemRowRepository(database),
        )
    }
    private val cancel by lazy { CancelQuoteUseCase(runner, quotes, clock) }
    private val prepare by lazy {
        PrepareQuoteConversionUseCase(runner, settings, quotes, products, customers, clock)
    }
    private val register by lazy {
        RegisterSaleUseCase(
            transactions = runner,
            settings = settings,
            products = products,
            sales = RoomSaleRepository(database),
            payments = RoomSalePaymentRepository(database),
            units = RoomUnitRepository(database),
            systemRows = RoomSystemRowRepository(database),
            ledger = RoomStockLedgerRepository(database),
            methodNames = PaymentMethodNames { key -> "Initial ${key.lowercase()}" },
            taxRates = RoomTaxRateRepository(database),
            clock = clock,
            customers = customers,
            quotes = quotes,
        )
    }

    private suspend fun stocked(name: String): Long = commit {
        val id = products.insert(newProduct(name))
        RoomStockLedgerRepository(database).append(openingRow(id, units = 10))
        id
    }

    private suspend fun quoted(vararg lines: SaleLineRequest): Long =
        (save(QuoteRequest(lines.toList())) as SaveQuoteResult.Saved).quoteId

    private suspend fun cash(): Long =
        count("SELECT id FROM payment_methods WHERE system_key = 'CASH'")

    @Test
    fun aSavedQuoteComesBackFrozenAndTouchesNoLedger() = runBlocking {
        val soap = stocked("Jabón inventado")
        val movements = count("SELECT COUNT(*) FROM stock_movements")
        val before = changeSeq()

        val id = quoted(
            SaleLineRequest(soap, QuantityMilli(3_000), discount = LineDiscount.Amount(Money(500))),
        )

        val quote = requireNotNull(quotes.findById(id))
        assertEquals(QuoteStatus.OPEN, quote.status)
        // 3 × 2500 = 7500, menos 500 de descuento: 7000, sin impuesto.
        assertEquals(Money(7_000), quote.totals.total)
        val line = quotes.findLines(id).single().content
        assertEquals(Money(2_500), line.unitPrice)
        assertEquals(Money(500), line.lineDiscount)
        assertEquals("Jabón inventado", line.productNameSnapshot)
        assertEquals(movements, count("SELECT COUNT(*) FROM stock_movements"))
        assertEquals(0L, count("SELECT COUNT(*) FROM money_movements"))
        assertEquals(0L, count("SELECT COUNT(*) FROM sales"))
        assertEquals(10_000L, count("SELECT on_hand_milli FROM products WHERE id = $soap"))
        assertEquals(before + 1, changeSeq())
    }

    @Test
    fun editingAnOpenQuoteRewritesItsLines() = runBlocking {
        val soap = stocked("Jabón inventado")
        val tea = stocked("Té inventado")
        val id = quoted(SaleLineRequest(soap, QuantityMilli(1_000)))

        val edited = save(
            QuoteRequest(listOf(SaleLineRequest(tea, QuantityMilli(2_000))), quoteId = id),
        )

        assertEquals(SaveQuoteResult.Saved(id), edited)
        assertEquals(listOf(tea), quotes.findLines(id).map { it.content.productId })
        assertEquals(Money(5_000), requireNotNull(quotes.findById(id)).totals.total)
        assertEquals(1L, count("SELECT COUNT(*) FROM quote_lines"))
    }

    @Test
    fun theSaleConvertsTheQuoteInItsOwnTransactionOnlyOnce() = runBlocking {
        val soap = stocked("Jabón inventado")
        val id = quoted(SaleLineRequest(soap, QuantityMilli(2_000)))
        val conversion = (prepare(id) as QuoteConversionResult.Ready).conversion
        val request = SaleRequest(conversion.requests(false), cash(), quoteId = id)

        val result = register(request)

        val saleId = (result as RegisterSaleResult.Registered).saleId
        assertEquals(id, count("SELECT quote_id FROM sales WHERE id = $saleId"))
        assertEquals(saleId, count("SELECT converted_sale_id FROM quotes WHERE id = $id"))
        assertEquals(QuoteStatus.CONVERTED, requireNotNull(quotes.findById(id)).status)
        assertEquals(8_000L, count("SELECT on_hand_milli FROM products WHERE id = $soap"))

        assertEquals(RegisterSaleResult.QuoteUnavailable, register(request))
        assertEquals(1L, count("SELECT COUNT(*) FROM sales"))
        assertEquals(8_000L, count("SELECT on_hand_milli FROM products WHERE id = $soap"))
    }

    @Test
    fun aCancelledQuoteStaysWithItsLinesAndCannotChange() = runBlocking {
        val soap = stocked("Jabón inventado")
        val id = quoted(SaleLineRequest(soap, QuantityMilli(1_000)))

        assertEquals(CancelQuoteResult.Cancelled, cancel(id))

        assertEquals(CancelQuoteResult.NotOpen, cancel(id))
        assertEquals(
            SaveQuoteResult.QuoteNotOpen,
            save(QuoteRequest(listOf(SaleLineRequest(soap, QuantityMilli(1_000))), quoteId = id)),
        )
        assertEquals(1L, count("SELECT COUNT(*) FROM quote_lines"))
        // Ni siquiera por fuera del caso de uso: la guarda no deja borrar sus líneas.
        val failure = storageFailureOf { commit { database.quoteDao().deleteLinesOf(id) } }
        assertEquals(StorageFailure.INVARIANT_VIOLATION, failure)
        assertEquals(1L, count("SELECT COUNT(*) FROM quote_lines"))
    }

    @Test
    fun theListOfEachStatusFollowsEveryChange() = runBlocking {
        val soap = stocked("Jabón inventado")
        quotes.observeByStatus(QuoteStatus.OPEN).test {
            assertEquals(emptyList<Any>(), awaitItem())
            val id = quoted(SaleLineRequest(soap, QuantityMilli(1_000)))
            assertEquals(listOf(id), awaitItem().map { it.id })
            cancel(id)
            assertEquals(emptyList<Any>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(quotes.findById(1)?.status == QuoteStatus.CANCELLED)
    }
}

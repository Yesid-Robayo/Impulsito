package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.PriceChangeSource
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodNames
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ChangePriceResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ChangePriceUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleUseCase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cambiar el precio sobre la base real: deja su fila de historial como un solo cambio, y una venta
 * registrada antes conserva su precio, su total y su costo.
 *
 * Los productos y montos son inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class PriceChangeOnDatabaseTest : RepositoryTest() {
    private val products by lazy { RoomProductRepository(database) }
    private val ledger by lazy { RoomStockLedgerRepository(database) }
    private val sales by lazy { RoomSaleRepository(database) }
    private val history by lazy { RoomPriceHistoryRepository(database) }
    private val settings by lazy { RoomAppSettingsRepository(database, RestoreLock { false }) }

    private val registerSale by lazy {
        RegisterSaleUseCase(
            transactions = runner,
            settings = settings,
            products = products,
            sales = sales,
            payments = RoomSalePaymentRepository(database),
            units = RoomUnitRepository(database),
            systemRows = RoomSystemRowRepository(database),
            ledger = ledger,
            methodNames = PaymentMethodNames { key -> "Initial ${key.lowercase()}" },
            taxRates = RoomTaxRateRepository(database),
            clock = Clock { AT },
        )
    }

    private val changePrice by lazy {
        ChangePriceUseCase(
            runner,
            products,
            RoomSystemRowRepository(database),
            history,
            BusinessCalendarSource(settings),
            CurrencyLock(settings),
            Clock { LATER_AT },
        )
    }

    @Test
    fun aPastSaleKeepsItsPriceWhenThePriceChanges() = runBlocking {
        val id = commit { products.insert(newProduct("Jabón inventado")) }
        commit { ledger.append(openingRow(id, units = 10)) }
        val method = count("SELECT id FROM payment_methods WHERE system_key = 'CASH'")
        val sale =
            registerSale(SaleRequest(listOf(SaleLineRequest(id, QuantityMilli(2_000))), method))
        val saleId = (sale as RegisterSaleResult.Registered).saleId
        val lineBefore = sales.findLines(saleId).single()
        val saleBefore = requireNotNull(sales.findById(saleId))
        val before = changeSeq()

        val result = changePrice(id, Money(3_800))

        val historyId = (result as ChangePriceResult.Changed).historyId
        assertEquals(before + 1, changeSeq())
        assertEquals(Money(3_800), requireNotNull(products.findById(id)).salePrice)
        val row = history.findByProduct(id).single { it.id == historyId }.content
        assertEquals(PriceChangeSource.MANUAL, row.source)
        assertEquals(Money(2_500), row.oldPrice)
        assertEquals(Money(3_800), row.newPrice)
        assertEquals(lineBefore, sales.findLines(saleId).single())
        assertEquals(saleBefore, sales.findById(saleId))
        assertEquals(Money(2_500), lineBefore.content.unitPrice)
    }

    @Test
    fun theSamePriceWritesNothing() = runBlocking {
        val id = commit { products.insert(newProduct("Cepillo inventado")) }
        val before = changeSeq()

        assertEquals(ChangePriceResult.Unchanged, changePrice(id, Money(2_500)))
        assertEquals(before, changeSeq())
    }
}

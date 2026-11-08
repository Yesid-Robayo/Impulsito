package io.github.yesidrobayo.shopledger.data.catalog

import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.data.local.TEST_BUSINESS_CURRENCY
import io.github.yesidrobayo.shopledger.data.local.TEST_BUSINESS_DECIMALS
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.catalog.CatalogDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.FIXED_CLOCK
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductForm
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CreateProductUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveProductResult
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Crear un producto con dinero sobre una base que nace sin moneda confirmada, como la de una
 * instalación nueva. Todos los nombres y montos son inventados.
 */
internal class CatalogCurrencyRoomTest : CatalogDatabaseTest() {
    override val confirmsCurrency: Boolean = false

    private fun createUseCase(): CreateProductUseCase {
        val ports = DataPorts(database, file, Dispatchers.IO, FIXED_CLOCK, RestoreLock { false })
        return CreateProductUseCase(
            ports.transactions, ports.products, ports.categories, ports.suppliers, ports.units,
            ports.systemRows, ports.priceHistory, ports.stockLedger, ports.lowStockAlerts,
            BusinessCalendarSource(ports.settings), CurrencyLock(ports.settings), FIXED_CLOCK,
        )
    }

    @Test
    fun aPriceWaitsForTheCurrencyAndTheFirstOneLocksIt() = runBlocking {
        val create = createUseCase()
        val priced = ProductForm(name = "Jabón", salePrice = Money(3_500L), cost = Money(1_200L))

        assertEquals(SaveProductResult.CurrencyUnconfirmed, create(priced, QuantityMilli(2_000L)))
        assertEquals(0L, count("SELECT COUNT(*) FROM products"))
        assertEquals(0L, count("SELECT change_seq FROM backup_state"))
        // Sin dinero no hace falta moneda, y nada queda bloqueado.
        assertTrue(create(ProductForm(name = "Bolsa")) is SaveProductResult.Saved)
        assertNull(database.appSettingsDao().get()?.currencyLockedAt)

        // La persona confirma la moneda: el primer producto con precio la bloquea en su transacción.
        database.appSettingsDao().updateCurrency(
            TEST_BUSINESS_CURRENCY,
            TEST_BUSINESS_DECIMALS,
            CREATED_AT,
        )
        assertTrue(create(priced, QuantityMilli(2_000L)) is SaveProductResult.Saved)
        assertEquals(CREATED_AT, database.appSettingsDao().get()?.currencyLockedAt)
    }
}

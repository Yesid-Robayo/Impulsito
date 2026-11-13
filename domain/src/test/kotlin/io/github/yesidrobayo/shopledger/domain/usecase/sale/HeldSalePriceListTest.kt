package io.github.yesidrobayo.shopledger.domain.usecase.sale

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceList
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListPricing
import io.github.yesidrobayo.shopledger.domain.model.sale.LineDiscount
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.testing.FakeHeldSaleRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakePriceHistoryRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakePriceListRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeProductRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeTransactionRunner
import io.github.yesidrobayo.shopledger.domain.testing.FixedClock
import io.github.yesidrobayo.shopledger.domain.testing.InMemoryDatabase
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La lista de precios de una venta en espera: se guarda con ella, convierte el descuento en
 * porcentaje con el precio de esa lista y vuelve al retomarla. Si la lista se archivó en el
 * intermedio, la venta sigue con la inicial y lo dice como precios que cambiaron. Montos inventados.
 */
class HeldSalePriceListTest {
    private val database = InMemoryDatabase()
    private val transactions = FakeTransactionRunner(database)
    private val clock = FixedClock.atEpochMillis(SaleWorld.NOW_MILLIS)
    private val products = FakeProductRepository(database)
    private val heldSales = FakeHeldSaleRepository(database)
    private val lists = FakePriceListRepository(database)

    private val hold = HoldSaleUseCase(
        transactions,
        heldSales,
        products,
        clock,
        priceLists = lists,
        productPrices = lists,
    )
    private val resume = ResumeHeldSaleUseCase(
        transactions,
        heldSales,
        products,
        FakePriceHistoryRepository(database),
        lists,
    )

    init {
        database.seed {
            it.copy(
                products = listOf(product(1, salePrice = 12_000), product(2, salePrice = 900))
                    .associateBy { product -> product.id },
                priceLists = listOf(
                    PriceList(
                        SaleWorld.DEFAULT_PRICE_LIST,
                        "DEFAULT",
                        null,
                        PriceListPricing.Fixed,
                        null,
                    ),
                    PriceList(WHOLESALE, null, "Mayorista", PriceListPricing.Fixed, null),
                ).associateBy { list -> list.id },
                productPrices = mapOf((WHOLESALE to 1L) to Money(10_000)),
            )
        }
    }

    private suspend fun held(listId: Long?, vararg lines: SaleLineRequest, heldId: Long? = null) =
        (hold(HoldSaleRequest(lines.toList(), null, heldId, listId)) as HoldSaleResult.Held)
            .heldSaleId

    private fun tenPercentOff(productId: Long) = SaleLineRequest(
        productId,
        QuantityMilli(1_000),
        discount = LineDiscount.Percent(BasisPoints(1_000)),
    )

    @Test
    fun theListTravelsWithTheWaitAndConvertsThePercentWithItsPrice() = runTest {
        val id = held(WHOLESALE, tenPercentOff(1))

        val stored = database.tables.value.heldSales.getValue(id)
        assertEquals(WHOLESALE, stored.priceListId)
        // El 10 % del precio de la lista, 10000, y no del precio de venta, 12000.
        assertEquals(Money(1_000), stored.lines.single().discount)
        // Una espera con la lista la deja en uso: no se puede eliminar.
        assertTrue(lists.isInUse(WHOLESALE))

        val resumed = (resume(id) as ResumeHeldSaleResult.Resumed).sale
        assertEquals(WHOLESALE, resumed.priceListId)
        assertEquals(0, resumed.changedPrices)
    }

    @Test
    fun aListArchivedSinceThenResumesWithTheInitialOneAndSaysSo() = runTest {
        val id = held(
            WHOLESALE,
            SaleLineRequest(1, QuantityMilli(1_000)),
            SaleLineRequest(2, QuantityMilli(1_000), Money(500)),
        )
        database.seed {
            val list = it.priceLists.getValue(WHOLESALE)
            it.copy(priceLists = it.priceLists + (WHOLESALE to list.copy(archivedAt = ARCHIVED_AT)))
        }

        val resumed = (resume(id) as ResumeHeldSaleResult.Resumed).sale

        assertNull(resumed.priceListId)
        // Solo la línea sin precio escrito cambia de precio al volver a la inicial.
        assertEquals(1, resumed.changedPrices)
    }

    @Test
    fun savingAgainKeepsTheListChosenNow() = runTest {
        val id = held(WHOLESALE, SaleLineRequest(1, QuantityMilli(1_000)))

        held(null, SaleLineRequest(1, QuantityMilli(2_000)), heldId = id)

        assertNull(database.tables.value.heldSales.getValue(id).priceListId)
        val resumed = (resume(id) as ResumeHeldSaleResult.Resumed).sale
        assertNull(resumed.priceListId)
        assertEquals(0, resumed.changedPrices)
    }

    @Test
    fun withoutTheListsToReadThePercentUsesTheSalePrice() = runTest {
        val bare = HoldSaleUseCase(transactions, heldSales, products, clock)

        val result = bare(HoldSaleRequest(listOf(tenPercentOff(1)), null, priceListId = WHOLESALE))

        val id = (result as HoldSaleResult.Held).heldSaleId
        val stored = database.tables.value.heldSales.getValue(id)
        assertEquals(WHOLESALE, stored.priceListId)
        assertEquals(Money(1_200), stored.lines.single().discount)
        val unwired = ResumeHeldSaleUseCase(
            transactions,
            heldSales,
            products,
            FakePriceHistoryRepository(database),
        )
        assertNull((unwired(id) as ResumeHeldSaleResult.Resumed).sale.priceListId)
    }

    private companion object {
        const val WHOLESALE = 41L
        val ARCHIVED_AT: Instant = Instant.fromEpochMilliseconds(1L)
    }
}

package io.github.yesidrobayo.shopledger.domain.usecase.sale

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductConversion
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceList
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListDetails
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListPricing
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.port.PriceListRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductPriceRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vender con una lista de precios: cada línea sin precio escrito toma el de la lista, en el orden de
 * la regla, y la venta y sus líneas guardan la lista. Montos inventados.
 */
class RegisterSalePriceListTest {
    private val prices = Prices()

    private val world = SaleWorld().apply {
        units =
            units + mapOf(BOX to measureUnit(BOX, "BOX", UnitKind.COUNT, allowsFraction = false))
        addMethod(method())
        addProduct(product(1, salePrice = 10_000))
        addProduct(product(2, salePrice = 3_000))
        conversions = listOf(
            ProductConversion(
                id = 103L,
                productId = 1L,
                details = ConversionDetails(BOX, 24_000L, Money(200_000L), null, true, true),
            ),
        )
        priceListRepository = Lists
        productPriceRepository = prices
    }

    private suspend fun sell(listId: Long?, vararg lines: SaleLineRequest) =
        world.registerSale()(SaleRequest(lines.toList(), SaleWorld.CASH, priceListId = listId))

    private fun storedLines() = world.tables.lines.values.sortedBy { it.id }.map { it.content }

    @Test
    fun aRelativeListPricesTheLineAndBothKeepTheList() = runTest {
        val result = sell(RELATIVE, SaleLineRequest(1, QuantityMilli(1_000)))

        assertTrue(result is RegisterSaleResult.Registered)
        // 10000 con −15 % son 8500.
        val line = storedLines().single()
        assertEquals(Money(8_500L), line.unitPrice)
        assertEquals(RELATIVE, line.priceListId)
        assertEquals(RELATIVE, world.tables.sales.values.single().content.priceListId)
    }

    @Test
    fun aFixedListUsesItsOwnPriceOrTheSalePrice() = runTest {
        sell(
            FIXED,
            SaleLineRequest(1, QuantityMilli(1_000)),
            SaleLineRequest(2, QuantityMilli(1_000)),
        )

        assertEquals(listOf(Money(9_000L), Money(3_000L)), storedLines().map { it.unitPrice })
    }

    @Test
    fun anotherUnitTakesTheListPriceTimesTheFactorAndNotItsOwnPrice() = runTest {
        // Con la inicial, la caja tiene su precio propio; con otra lista, 8500 por 24.
        sell(null, SaleLineRequest(1, QuantityMilli(1_000), unitId = BOX))
        sell(RELATIVE, SaleLineRequest(1, QuantityMilli(1_000), unitId = BOX))

        assertEquals(listOf(Money(200_000L), Money(204_000L)), storedLines().map { it.unitPrice })
        assertEquals(
            listOf(SaleWorld.DEFAULT_PRICE_LIST, RELATIVE),
            storedLines().map {
                it.priceListId
            },
        )
    }

    @Test
    fun changingTheListLaterLeavesPastSalesAsTheyWere() = runTest {
        sell(FIXED, SaleLineRequest(1, QuantityMilli(1_000)))
        prices.own = Money(9_500L)
        sell(FIXED, SaleLineRequest(1, QuantityMilli(1_000)))

        // La primera venta conserva el precio que congeló; solo la nueva toma el de ahora.
        assertEquals(listOf(Money(9_000L), Money(9_500L)), storedLines().map { it.unitPrice })
        assertEquals(listOf(FIXED, FIXED), storedLines().map { it.priceListId })
    }

    @Test
    fun aWrittenPriceWinsOverTheList() = runTest {
        sell(RELATIVE, SaleLineRequest(1, QuantityMilli(1_000), Money(7_000L)))

        assertEquals(Money(7_000L), storedLines().single().unitPrice)
    }

    @Test
    fun theInitialListNeedsNothingElse() = runTest {
        world.priceListRepository = null
        world.productPriceRepository = null

        sell(SaleWorld.DEFAULT_PRICE_LIST, SaleLineRequest(1, QuantityMilli(1_000)))

        assertEquals(Money(10_000L), storedLines().single().unitPrice)
    }

    @Test
    fun anArchivedOrMissingListIsRefusedAndWritesNothing() = runTest {
        assertEquals(
            RegisterSaleResult.PriceListUnavailable,
            sell(ARCHIVED, SaleLineRequest(1, QuantityMilli(1_000))),
        )
        assertEquals(
            RegisterSaleResult.PriceListUnavailable,
            sell(99L, SaleLineRequest(1, QuantityMilli(1_000))),
        )
        world.productPriceRepository = null
        assertEquals(
            RegisterSaleResult.PriceListUnavailable,
            sell(RELATIVE, SaleLineRequest(1, QuantityMilli(1_000))),
        )
        world.priceListRepository = null
        assertEquals(
            RegisterSaleResult.PriceListUnavailable,
            sell(RELATIVE, SaleLineRequest(1, QuantityMilli(1_000))),
        )
        assertTrue(world.tables.sales.isEmpty())
    }

    /** Solo lo que lee la venta: todas las listas. */
    private object Lists : PriceListRepository {
        private val all = listOf(
            PriceList(SaleWorld.DEFAULT_PRICE_LIST, "DEFAULT", null, PriceListPricing.Fixed, null),
            PriceList(
                RELATIVE,
                null,
                "Especial",
                PriceListPricing.Relative(SaleWorld.DEFAULT_PRICE_LIST, BasisPoints(-1_500L)),
                null,
            ),
            PriceList(FIXED, null, "Mayorista", PriceListPricing.Fixed, null),
            PriceList(
                ARCHIVED,
                null,
                "Vieja",
                PriceListPricing.Fixed,
                Instant.fromEpochMilliseconds(1L),
            ),
        )

        override suspend fun findAll(): List<PriceList> = all

        override fun observeActive(): Flow<List<PriceList>> = unused()

        override fun observeArchived(): Flow<List<PriceList>> = unused()

        override fun observeInUse(): Flow<Set<Long>> = unused()

        override fun observeCustomerCounts(): Flow<Map<Long, Int>> = unused()

        override suspend fun findById(id: Long): PriceList? = unused()

        override suspend fun findActiveByNormalizedName(normalizedName: String): PriceList? =
            unused()

        override suspend fun wouldCreateCycle(id: Long, baseId: Long): Boolean = unused()

        override suspend fun isInUse(id: Long): Boolean = unused()

        override suspend fun insert(details: PriceListDetails, createdAt: Instant): Long = unused()

        override suspend fun update(
            id: Long,
            details: PriceListDetails,
            updatedAt: Instant,
        ): Boolean = unused()

        override suspend fun setArchivedAt(id: Long, archivedAt: Instant?, updatedAt: Instant) =
            unused()

        override suspend fun delete(id: Long) = unused()
    }

    /** Solo lo que lee la venta: los precios propios de un producto. */
    private class Prices(var own: Money = Money(9_000L)) : ProductPriceRepository {
        override suspend fun findByProduct(productId: Long): Map<Long, Money> =
            if (productId == 1L) mapOf(FIXED to own) else emptyMap()

        override fun observeAll(): Flow<Map<Long, Map<Long, Money>>> = unused()

        override fun observeByList(priceListId: Long): Flow<Map<Long, Money>> = unused()

        override suspend fun find(priceListId: Long, productId: Long): Money? = unused()

        override suspend fun hasAny(priceListId: Long): Boolean = unused()

        override suspend fun insert(priceListId: Long, productId: Long, price: Money, at: Instant) =
            unused()

        override suspend fun update(priceListId: Long, productId: Long, price: Money, at: Instant) =
            unused()

        override suspend fun delete(priceListId: Long, productId: Long) = unused()
    }

    private companion object {
        const val BOX = 3L
        const val RELATIVE = 40L
        const val FIXED = 41L
        const val ARCHIVED = 42L

        fun unused(): Nothing = error("La venta no lee esto")
    }
}

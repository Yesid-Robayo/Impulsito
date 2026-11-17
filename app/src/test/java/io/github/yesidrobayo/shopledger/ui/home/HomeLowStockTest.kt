package io.github.yesidrobayo.shopledger.ui.home

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleHistoryCursor
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleHistoryFilter
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleHistoryRow
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.SaleHistorySource
import io.github.yesidrobayo.shopledger.domain.rule.stock.InventoryRules
import io.github.yesidrobayo.shopledger.domain.usecase.home.TodaySales
import io.github.yesidrobayo.shopledger.domain.usecase.sale.SaleHistoryReader
import io.github.yesidrobayo.shopledger.domain.usecase.stock.InventoryOverview
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * La tarjeta por agotarse de Inicio: el mismo conteo y el mismo orden que el inventario, a lo sumo
 * tres productos, la cuenta del stock negativo, y nada si no hay nada que se esté acabando o si no
 * se puede leer. Productos inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeLowStockTest {
    private val dispatcher = StandardTestDispatcher()
    private val products = MutableStateFlow<List<Product>>(emptyList())

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun TestScope.viewModel(
        inventory: Flow<InventoryOverview>,
        producible: Flow<Set<Long>> = flowOf(emptySet()),
    ): HomeViewModel {
        val model = HomeViewModel(
            todaySales = { emptyFlow<TodaySales>() },
            hasProducts = MutableStateFlow(true),
            history = SaleHistoryReader(NoHistory, Clock { AT }),
            computation = dispatcher,
            inventory = inventory,
            producible = producible,
        )
        backgroundScope.launch { model.lowStock.collect {} }
        advanceUntilIdle()
        return model
    }

    @Test
    fun `nothing running low shows no card`() = runTest(dispatcher) {
        products.value = listOf(product(1L, "Arroz", 9_000L))

        assertNull(viewModel(products.map(::overviewOf)).lowStock.value)
    }

    @Test
    fun `the card counts like the inventory and shows the first three in its order`() =
        runTest(dispatcher) {
            products.value = listOf(
                product(1L, "Arroz", 2_000L),
                product(2L, "Aceite", 0L),
                product(3L, "Jabón", -1_000L),
                product(4L, "Sal", 1_000L),
                product(5L, "Té", 9_000L),
            )
            val model = viewModel(products.map(::overviewOf))

            val card = requireNotNull(model.lowStock.value)
            val inventory = overviewOf(products.value)
            assertEquals(inventory.lowStock.size, card.count)
            assertEquals(4, card.count)
            assertEquals(1, card.negativeCount)
            assertEquals(listOf(3L, 2L, 1L), card.items.map { it.id })

            products.value = listOf(product(1L, "Arroz", 9_000L))
            advanceUntilIdle()
            assertNull(model.lowStock.value)
        }

    @Test
    fun `a row of a product with a recipe is marked as produced, like in the inventory`() =
        runTest(dispatcher) {
            products.value = listOf(product(1L, "Arroz", 2_000L), product(2L, "Pan", 0L))
            val model = viewModel(products.map(::overviewOf), flowOf(setOf(2L)))

            val card = requireNotNull(model.lowStock.value)
            assertEquals(
                mapOf(2L to true, 1L to false),
                card.items.associate { it.id to it.producible },
            )
        }

    @Test
    fun `without knowing what is produced the card still shows, with every row to buy`() =
        runTest(dispatcher) {
            products.value = listOf(product(1L, "Pan", 0L))
            val model = viewModel(products.map(::overviewOf), flow { error("Falla inventada") })

            val card = requireNotNull(model.lowStock.value)
            assertEquals(listOf(false), card.items.map { it.producible })
        }

    @Test
    fun `a read failure hides the card and keeps the day`() = runTest(dispatcher) {
        val model = viewModel(flow { error("Falla inventada") })

        assertNull(model.lowStock.value)
    }

    private fun overviewOf(list: List<Product>) = InventoryOverview(
        hasProducts = list.isNotEmpty(),
        tracked = list,
        lowStock = InventoryRules.lowStock(list),
        negative = InventoryRules.negative(list),
        value = InventoryRules.value(list),
        archivedValue = null,
        hasStockWithoutCost = false,
        units = emptyMap(),
    )

    private fun product(id: Long, name: String, onHand: Long) = Product(
        id = id,
        details = ProductDetails(
            name = name,
            normalizedName = name.lowercase(),
            categoryId = null,
            defaultSupplierId = null,
            baseUnitId = 1L,
            stockMode = ProductStockMode.TRACKED,
            tracksLots = false,
            fixedUnitCost = Money.ZERO,
            minStock = QuantityMilli(5_000L),
            barcode = null,
            parentProductId = null,
            variantAxis1 = null,
            variantAxis2 = null,
            variantValue1 = null,
            variantValue2 = null,
            photoMediaId = null,
            taxRateId = null,
            targetMargin = null,
            referenceCost = null,
            referencePrice = null,
            expiryNoticeDays = null,
        ),
        salePrice = Money(1_000L),
        initialCost = Money(100L),
        averageCost = Money(100L),
        stockValue = Money.ZERO,
        onHand = QuantityMilli(onHand),
        replacementCost = Money.ZERO,
        replacementCostAt = null,
        priceReviewSince = null,
        archivedAt = null,
        createdAt = AT,
        updatedAt = AT,
    )

    /** Sin ventas: esta prueba solo mira la tarjeta por agotarse. */
    private object NoHistory : SaleHistorySource {
        override suspend fun page(
            filter: SaleHistoryFilter,
            after: SaleHistoryCursor?,
            limit: Int,
        ): List<SaleHistoryRow> = emptyList()

        override suspend fun dayTotals(
            filter: SaleHistoryFilter,
            dates: Set<BusinessDate>,
        ): Map<BusinessDate, Money> = emptyMap()

        override fun observeChanges(): Flow<String> = MutableStateFlow("0")

        override suspend fun methodsWithSales(methodIds: Set<Long>): Set<Long> = emptySet()
    }

    private companion object {
        val AT: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    }
}

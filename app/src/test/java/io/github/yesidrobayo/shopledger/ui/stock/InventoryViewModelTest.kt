package io.github.yesidrobayo.shopledger.ui.stock

import app.cash.turbine.test
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.rule.stock.InventoryRules
import io.github.yesidrobayo.shopledger.domain.rule.stock.InventoryStatus
import io.github.yesidrobayo.shopledger.domain.usecase.stock.InventoryOverview
import io.github.yesidrobayo.shopledger.navigation.InventoryFilter
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** El inventario: qué lista muestra cada chip y cómo llega cada cambio del catálogo. */
@OptIn(ExperimentalCoroutinesApi::class)
class InventoryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val products = MutableStateFlow<List<Product>>(emptyList())

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun viewModel(chip: InventoryChip = InventoryChip.LOW_STOCK) = InventoryViewModel(
        observe = { products.toOverview() },
        computation = dispatcher,
        initialChip = chip,
    )

    @Test
    fun `an empty catalog invites to create the first product`() = runTest(dispatcher) {
        viewModel().uiState.test {
            assertEquals(InventoryContent.Loading, awaitItem().content)
            assertEquals(InventoryContent.EmptyCatalog, awaitItem().content)
        }
    }

    @Test
    fun `the low stock list follows the urgent order and counts`() = runTest(dispatcher) {
        products.value = listOf(
            product(1L, "Arroz", 2_000L),
            product(2L, "Aceite", 0L),
            product(3L, "Jabón", -1_000L),
            product(4L, "Sal", 9_000L),
        )
        viewModel().uiState.test {
            skipItems(1)
            val ready = awaitItem().content as InventoryContent.Ready
            assertEquals(listOf(3L, 2L, 1L), ready.items.map { it.id })
            assertEquals(
                listOf(InventoryStatus.NEGATIVE, InventoryStatus.OUT, InventoryStatus.LOW),
                ready.items.map { it.status },
            )
            assertEquals(3, ready.lowStockCount)
            assertEquals(1, ready.negativeCount)
            assertEquals(Money(1_100L), ready.value)
        }
    }

    @Test
    fun `the other chips show the negative stock and everything`() = runTest(dispatcher) {
        products.value = listOf(product(1L, "Arroz", -2_000L), product(2L, "Sal", 9_000L))
        val model = viewModel()
        model.uiState.test {
            skipItems(2)
            model.onAction(InventoryAction.ChipChanged(InventoryChip.NEGATIVE_STOCK))
            val negative = awaitItem().content as InventoryContent.Ready
            assertEquals(listOf(1L), negative.items.map { it.id })
            model.onAction(InventoryAction.ChipChanged(InventoryChip.ALL))
            assertEquals(
                listOf(1L, 2L),
                (awaitItem().content as InventoryContent.Ready).items.map { it.id },
            )
        }
    }

    @Test
    fun `once the negative stock is fixed, the negative chip falls back to low stock`() =
        runTest(dispatcher) {
            products.value = listOf(product(1L, "Arroz", -2_000L))
            val model = viewModel(chipOf(InventoryFilter.NEGATIVE_STOCK))
            model.uiState.test {
                skipItems(1)
                assertEquals(InventoryChip.NEGATIVE_STOCK, awaitItem().chip)

                products.value = listOf(product(1L, "Arroz", 0L))

                val fixed = awaitItem()
                assertEquals(InventoryChip.LOW_STOCK, fixed.chip)
                val shown = fixed.content as InventoryContent.Ready
                assertEquals(listOf(1L), shown.items.map { it.id })
            }
        }

    @Test
    fun `a read failure shows the error and retry reads again`() = runTest(dispatcher) {
        var fail = true
        val model = InventoryViewModel(
            observe = {
                flow {
                    if (fail) error("Falla inventada")
                    emit(overviewOf(listOf(product(1L, "Arroz", 1_000L))))
                }
            },
            computation = dispatcher,
        )
        model.uiState.test {
            skipItems(1)
            assertEquals(InventoryContent.ReadError, awaitItem().content)
            fail = false
            model.onAction(InventoryAction.Retry)
            assertEquals(1, (awaitItem().content as InventoryContent.Ready).items.size)
        }
    }

    @Test
    fun `every navigation filter opens a chip that exists`() {
        assertEquals(InventoryChip.LOW_STOCK, chipOf(InventoryFilter.REVIEW))
        assertEquals(InventoryChip.LOW_STOCK, chipOf(InventoryFilter.EXPIRING))
        assertEquals(InventoryChip.ALL, chipOf(InventoryFilter.ALL))
    }

    private fun MutableStateFlow<List<Product>>.toOverview() = flow {
        collect { emit(overviewOf(it)) }
    }

    private fun overviewOf(list: List<Product>): InventoryOverview {
        val tracked = list.sortedBy { it.details.normalizedName }
        return InventoryOverview(
            hasProducts = list.isNotEmpty(),
            tracked = tracked,
            lowStock = InventoryRules.lowStock(tracked),
            negative = InventoryRules.negative(tracked),
            value = InventoryRules.value(list),
            archivedValue = null,
            hasStockWithoutCost = false,
            units = emptyMap(),
        )
    }

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
        stockValue = Money(if (onHand > 0L) onHand / 10L else 0L),
        onHand = QuantityMilli(onHand),
        replacementCost = Money.ZERO,
        replacementCostAt = null,
        priceReviewSince = null,
        archivedAt = null,
        createdAt = AT,
        updatedAt = AT,
    )

    private companion object {
        val AT: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    }
}

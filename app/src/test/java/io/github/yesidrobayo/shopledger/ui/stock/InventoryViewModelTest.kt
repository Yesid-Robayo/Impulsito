package io.github.yesidrobayo.shopledger.ui.stock

import app.cash.turbine.test
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.rule.stock.InventoryRules
import io.github.yesidrobayo.shopledger.domain.rule.stock.InventoryStatus
import io.github.yesidrobayo.shopledger.domain.usecase.pricing.DismissPriceReviewResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.InventoryOverview
import io.github.yesidrobayo.shopledger.navigation.InventoryFilter
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        assertEquals(InventoryChip.REVIEW, chipOf(InventoryFilter.REVIEW))
        assertEquals(InventoryChip.LOW_STOCK, chipOf(InventoryFilter.EXPIRING))
        assertEquals(InventoryChip.ALL, chipOf(InventoryFilter.ALL))
    }

    @Test
    fun `the review chip lists the queue and falls back when it empties`() = runTest(dispatcher) {
        products.value = listOf(product(1L, "Arroz", 2_000L))
        val queue = MutableStateFlow(listOf(product(1L, "Arroz", 2_000L)))
        val model = InventoryViewModel(
            observe = { products.toOverview() },
            computation = dispatcher,
            initialChip = InventoryChip.REVIEW,
            reviewQueue = { queue },
        )
        model.uiState.test {
            skipItems(1)
            val review = awaitItem()
            assertEquals(InventoryChip.REVIEW, review.chip)
            val ready = review.content as InventoryContent.Ready
            assertEquals(listOf(1L), ready.reviewItems.map { it.id })
            assertEquals(Money(1_000L), ready.reviewItems.single().trailing.price)
            assertEquals(emptyList<InventoryItem>(), ready.items)

            queue.value = emptyList()
            // Sin nada que revisar, el chip desaparece y se ve lo que se está acabando.
            val after = awaitItem()
            assertEquals(InventoryChip.LOW_STOCK, after.chip)
            assertEquals(listOf(1L), (after.content as InventoryContent.Ready).items.map { it.id })
        }
    }

    @Test
    fun `a failed dismiss keeps the row and says so`() = runTest(dispatcher) {
        products.value = listOf(product(1L, "Arroz", 2_000L))
        val dismissed = mutableListOf<Long>()
        var result: DismissPriceReviewResult =
            DismissPriceReviewResult.StorageFailed(StorageFailure.DISK_FULL)
        val model = InventoryViewModel(
            observe = { products.toOverview() },
            computation = dispatcher,
            initialChip = InventoryChip.REVIEW,
            reviewQueue = { flowOf(listOf(product(1L, "Arroz", 2_000L))) },
            dismissReview = {
                dismissed += it
                result
            },
        )
        model.uiState.test {
            skipItems(2)
            model.onAction(InventoryAction.DismissReview(1L))
            assertEquals(InventoryMessage.DismissFailed(outOfSpace = true), awaitItem().message)
            model.onAction(InventoryAction.MessageShown)
            assertNull(awaitItem().message)

            result = DismissPriceReviewResult.Dismissed
            model.onAction(InventoryAction.DismissReview(1L))
            advanceUntilIdle()
            expectNoEvents()
        }
        assertEquals(listOf(1L, 1L), dismissed)
    }

    @Test
    fun `a failing queue only hides the review chip`() = runTest(dispatcher) {
        products.value = listOf(product(1L, "Arroz", 2_000L))
        val model = InventoryViewModel(
            observe = { products.toOverview() },
            computation = dispatcher,
            initialChip = InventoryChip.REVIEW,
            reviewQueue = { flow { throw IllegalStateException("lectura") } },
        )
        model.uiState.test {
            skipItems(1)
            val shown = awaitItem()
            assertEquals(InventoryChip.LOW_STOCK, shown.chip)
            assertTrue((shown.content as InventoryContent.Ready).reviewItems.isEmpty())
        }
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

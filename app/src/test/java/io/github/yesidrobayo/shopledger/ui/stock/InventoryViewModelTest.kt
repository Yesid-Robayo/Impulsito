package io.github.yesidrobayo.shopledger.ui.stock

import app.cash.turbine.test
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceReview
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceReviewReason
import io.github.yesidrobayo.shopledger.domain.model.stock.DatedLotStock
import io.github.yesidrobayo.shopledger.domain.model.stock.ExpiringLot
import io.github.yesidrobayo.shopledger.domain.model.stock.Location
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationDetails
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationKind
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
import kotlinx.datetime.LocalDate
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
        assertEquals(InventoryChip.EXPIRING, chipOf(InventoryFilter.EXPIRING))
        assertEquals(InventoryChip.ALL, chipOf(InventoryFilter.ALL))
    }

    @Test
    fun `the expiring chip lists the lots and falls back when none are left`() =
        runTest(dispatcher) {
            products.value = listOf(product(1L, "Arroz", 2_000L))
            val lot = DatedLotStock(
                lotId = 7L,
                productId = 1L,
                productName = "Arroz",
                code = "A-118",
                expiryDate = BusinessDate(LocalDate(2026, 11, 20)),
                onHand = QuantityMilli(3_000L),
                baseUnitId = 1L,
                noticeDays = null,
                expiryNotifiedAt = null,
            )
            val lots = MutableStateFlow(listOf(ExpiringLot(lot, daysLeft = -2)))
            val model = InventoryViewModel(
                observe = { products.toOverview() },
                computation = dispatcher,
                initialChip = InventoryChip.EXPIRING,
                expiring = { lots },
            )
            model.uiState.test {
                skipItems(1)
                val expiring = awaitItem()
                assertEquals(InventoryChip.EXPIRING, expiring.chip)
                val item = (expiring.content as InventoryContent.Ready).expiringItems.single()
                assertEquals(7L, item.lotId)
                assertEquals(1L, item.productId)
                assertEquals(-2, item.daysLeft)
                assertEquals(QuantityMilli(3_000L), item.onHand)

                lots.value = emptyList()
                // Sin lotes por vencer, el chip desaparece y se ve lo que se está acabando.
                assertEquals(InventoryChip.LOW_STOCK, awaitItem().chip)
            }
        }

    @Test
    fun `the review chip lists the queue and falls back when it empties`() = runTest(dispatcher) {
        products.value = listOf(product(1L, "Arroz", 2_000L))
        val queue = MutableStateFlow(listOf(review(product(1L, "Arroz", 2_000L))))
        val model = InventoryViewModel(
            observe = { products.toOverview() },
            computation = dispatcher,
            initialChip = InventoryChip.REVIEW,
            review = { queue },
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
            review = { flowOf(listOf(review(product(1L, "Arroz", 2_000L)))) },
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
            review = { flow { throw IllegalStateException("lectura") } },
        )
        model.uiState.test {
            skipItems(1)
            val shown = awaitItem()
            assertEquals(InventoryChip.LOW_STOCK, shown.chip)
            assertTrue((shown.content as InventoryContent.Ready).reviewItems.isEmpty())
        }
    }

    @Test
    fun `each row carries all its reasons and only a new cost can be dismissed`() =
        runTest(dispatcher) {
            products.value = listOf(product(1L, "Arroz", 2_000L), product(2L, "Sal", 2_000L))
            val since = BusinessDate(LocalDate(2026, 8, 1))
            val model = InventoryViewModel(
                observe = { products.toOverview() },
                computation = dispatcher,
                initialChip = InventoryChip.REVIEW,
                review = {
                    flowOf(
                        listOf(
                            review(
                                product(1L, "Arroz", 2_000L),
                                listOf(PriceReviewReason.NEW_COST, PriceReviewReason.LOW_MARGIN),
                                margin = Margin.Defined(BasisPoints(1_500L)),
                            ),
                            review(
                                product(2L, "Sal", 2_000L),
                                listOf(PriceReviewReason.OLD_COST),
                                since,
                            ),
                        ),
                    )
                },
            )
            model.uiState.test {
                skipItems(1)
                val rows = (awaitItem().content as InventoryContent.Ready).reviewItems
                assertEquals(listOf(1L, 2L), rows.map { it.id })
                assertTrue(rows[0].dismissable)
                // El margen de la fila es el de la regla, sin impuesto, no el del catálogo.
                assertEquals(Margin.Defined(BasisPoints(1_500L)), rows[0].trailing.margin)
                assertEquals(listOf(PriceReviewReason.OLD_COST), rows[1].reasons)
                assertEquals(since, rows[1].costSince)
                assertTrue(!rows[1].dismissable)
            }
        }

    private fun review(
        product: Product,
        reasons: List<PriceReviewReason> = listOf(PriceReviewReason.NEW_COST),
        costSince: BusinessDate? = null,
        margin: Margin = Margin.NoData,
    ) = PriceReview(product, reasons, margin, BasisPoints(3_000L), costSince)

    @Test
    fun `with two locations one is chosen and its stock is shown`() = runTest(dispatcher) {
        products.value = listOf(product(1L, "Arroz", 9_000L))
        val stall = MutableStateFlow(listOf(product(1L, "Arroz", -1_000L)))
        val locations = MutableStateFlow(listOf(location(1L, "MAIN"), location(2L, null)))
        val model = InventoryViewModel(
            observe = { products.toOverview() },
            computation = dispatcher,
            initialChip = InventoryChip.ALL,
            locations = { locations },
            observeAt = { id -> if (id == 2L) stall.toOverview() else products.toOverview() },
        )
        model.uiState.test {
            skipItems(1)
            val all = awaitItem()
            assertEquals(listOf(1L, 2L), all.locations.map { it.id })
            assertNull(all.locationId)
            val first = all.content as InventoryContent.Ready
            assertEquals(QuantityMilli(9_000L), first.items.single().onHand)

            model.onAction(InventoryAction.LocationChanged(2L))
            advanceUntilIdle()
            val atStall = expectMostRecentItem()
            assertEquals(2L, atStall.locationId)
            val ready = atStall.content as InventoryContent.Ready
            assertEquals(QuantityMilli(-1_000L), ready.items.single().onHand)
            assertEquals(1, ready.negativeCount)

            // Archivada la del puesto, queda una sola: el selector desaparece y vuelve a todo.
            locations.value = listOf(location(1L, "MAIN"))
            advanceUntilIdle()
            val single = expectMostRecentItem()
            assertTrue(single.locations.isEmpty())
            assertNull(single.locationId)
        }
    }

    private fun location(id: Long, systemKey: String?) = Location(
        id = id,
        systemKey = systemKey,
        details = LocationDetails(
            if (systemKey == null) "Puesto" else null,
            if (systemKey == null) "puesto" else null,
            LocationKind.STALL,
        ),
        archivedAt = null,
        createdAt = AT,
        updatedAt = AT,
    )

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

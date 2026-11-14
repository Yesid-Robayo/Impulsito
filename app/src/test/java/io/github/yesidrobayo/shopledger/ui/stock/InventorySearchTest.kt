package io.github.yesidrobayo.shopledger.ui.stock

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductUsage
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceReview
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceReviewReason
import io.github.yesidrobayo.shopledger.domain.model.stock.DatedLotStock
import io.github.yesidrobayo.shopledger.domain.model.stock.ExpiringLot
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import io.github.yesidrobayo.shopledger.domain.rule.stock.InventoryRules
import io.github.yesidrobayo.shopledger.domain.usecase.stock.InventoryOverview
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * La búsqueda del inventario: por nombre, sin mayúsculas ni tildes, o por código completo, dentro
 * del chip elegido. No cambia las cifras ni qué chips aparecen. Productos y códigos inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InventorySearchTest {
    private val dispatcher = StandardTestDispatcher()
    private val products = MutableStateFlow(
        listOf(
            product(1L, "Arroz", 2_000L),
            product(2L, "Aceite", 0L, barcode = CODE),
            product(3L, "Sal", 9_000L),
            product(4L, "Jabón", -1_000L),
            product(5L, "Harina", 1_000L, usage = ProductUsage.INPUT),
        ),
    )

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun TestScope.viewModel(
        chip: InventoryChip = InventoryChip.LOW_STOCK,
        review: List<PriceReview> = emptyList(),
        lots: List<ExpiringLot> = emptyList(),
        hasCodes: Boolean = false,
    ): InventoryViewModel {
        val model = InventoryViewModel(
            observe = { products.map(::overviewOf) },
            computation = dispatcher,
            initialChip = chip,
            review = { flowOf(review) },
            expiring = { flowOf(lots) },
            hasCodes = flowOf(hasCodes),
        )
        backgroundScope.launch { model.uiState.collect {} }
        backgroundScope.launch { model.canScan.collect {} }
        advanceUntilIdle()
        return model
    }

    private fun TestScope.search(model: InventoryViewModel, query: String) {
        model.onAction(InventoryAction.QueryChange(query))
        advanceUntilIdle()
    }

    private fun InventoryViewModel.ready(): InventoryContent.Ready =
        uiState.value.content as InventoryContent.Ready

    @Test
    fun `a name finds its product without case or accents`() = runTest(dispatcher) {
        val model = viewModel(InventoryChip.ALL)

        search(model, "JABON")

        assertEquals(listOf(4L), model.ready().items.map { it.id })
        assertEquals("JABON", model.uiState.value.query)
        assertEquals("JABON", model.ready().query)
    }

    @Test
    fun `a full barcode finds its product, and part of it does not`() = runTest(dispatcher) {
        val model = viewModel(InventoryChip.ALL)

        search(model, " $CODE ")
        assertEquals(listOf(2L), model.ready().items.map { it.id })

        search(model, CODE.take(5))
        assertTrue(model.ready().items.isEmpty())
    }

    @Test
    fun `the search stays inside the chip and keeps the figures`() = runTest(dispatcher) {
        val model = viewModel()
        val before = model.ready()

        // Arroz y harina se están acabando; el aceite y el jabón no llevan `r`.
        search(model, "r")
        assertEquals(setOf(1L, 5L), model.ready().items.map { it.id }.toSet())
        assertEquals(before.lowStockCount, model.ready().lowStockCount)
        assertEquals(before.negativeCount, model.ready().negativeCount)
        assertEquals(before.value, model.ready().value)

        // En todo el inventario, la sal también, por nombre.
        search(model, "a")
        model.onAction(InventoryAction.ChipChanged(InventoryChip.ALL))
        advanceUntilIdle()
        assertEquals(listOf(2L, 1L, 5L, 4L, 3L), model.ready().items.map { it.id })

        search(model, "sal")
        model.onAction(InventoryAction.ChipChanged(InventoryChip.LOW_STOCK))
        advanceUntilIdle()
        assertEquals(InventoryChip.LOW_STOCK, model.uiState.value.chip)
        assertTrue(model.ready().items.isEmpty())
    }

    @Test
    fun `a search with nothing in the negative chip keeps the chip`() = runTest(dispatcher) {
        val model = viewModel(InventoryChip.NEGATIVE_STOCK)

        search(model, "arroz")

        // Hay stock negativo: el chip sigue, aunque lo buscado no esté en él.
        assertEquals(InventoryChip.NEGATIVE_STOCK, model.uiState.value.chip)
        assertTrue(model.ready().items.isEmpty())
        search(model, "")
        assertEquals(listOf(4L), model.ready().items.map { it.id })
    }

    @Test
    fun `review and expiring rows are searched, and their chips stay`() = runTest(dispatcher) {
        val list = products.value
        val pending = listOf(review(list[0]), review(list[2]))
        val lots = listOf(lot(7L, list[0]), lot(8L, list[4]))
        val model = viewModel(InventoryChip.REVIEW, pending, lots)

        search(model, "sal")
        assertEquals(listOf(3L), model.ready().reviewItems.map { it.id })
        assertTrue(model.ready().showsExpiring)

        model.onAction(InventoryAction.ChipChanged(InventoryChip.EXPIRING))
        advanceUntilIdle()
        assertEquals(InventoryChip.EXPIRING, model.uiState.value.chip)
        assertTrue(model.ready().expiringItems.isEmpty())
        assertTrue(model.ready().showsReview)

        search(model, "harina")
        assertEquals(listOf(8L), model.ready().expiringItems.map { it.lotId })
        assertTrue(model.ready().reviewItems.isEmpty())
    }

    @Test
    fun `a lot of a product no longer listed is found by its name`() = runTest(dispatcher) {
        val gone = product(9L, "Levadura", 1_000L)
        val model = viewModel(InventoryChip.EXPIRING, lots = listOf(lot(10L, gone)))

        search(model, "levad")

        assertEquals(listOf(10L), model.ready().expiringItems.map { it.lotId })
    }

    @Test
    fun `each row carries its usage, and the scanner follows the codes`() = runTest(dispatcher) {
        val model = viewModel(InventoryChip.ALL, hasCodes = true)

        val usages = model.ready().items.associate { it.id to it.usage }
        assertEquals(ProductUsage.INPUT, usages[5L])
        assertEquals(ProductUsage.SALE, usages[1L])
        assertTrue(model.canScan.value)
        assertFalse(viewModel(hasCodes = false).canScan.value)
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

    private fun review(product: Product) = PriceReview(
        product,
        listOf(PriceReviewReason.NEW_COST),
        Margin.NoData,
        BasisPoints(3_000L),
        null,
    )

    private fun lot(id: Long, product: Product) = ExpiringLot(
        DatedLotStock(
            lotId = id,
            productId = product.id,
            productName = product.details.name,
            code = null,
            expiryDate = BusinessDate(LocalDate(2026, 11, 20)),
            onHand = QuantityMilli(1_000L),
            baseUnitId = 1L,
            noticeDays = null,
            expiryNotifiedAt = null,
        ),
        daysLeft = 3,
    )

    private fun product(
        id: Long,
        name: String,
        onHand: Long,
        barcode: String? = null,
        usage: ProductUsage = ProductUsage.SALE,
    ) = Product(
        id = id,
        details = ProductDetails(
            name = name,
            normalizedName = TextNormalizer.normalize(name),
            categoryId = null,
            defaultSupplierId = null,
            baseUnitId = 1L,
            stockMode = ProductStockMode.TRACKED,
            tracksLots = false,
            fixedUnitCost = Money.ZERO,
            minStock = QuantityMilli(5_000L),
            barcode = barcode,
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
            usage = usage,
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
        /** Un código inventado, de trece cifras. */
        const val CODE = "2000000000017"
        val AT: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    }
}

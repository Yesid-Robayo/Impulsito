package io.github.yesidrobayo.shopledger.ui.reorder

import androidx.lifecycle.SavedStateHandle
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.Supplier
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.stock.Location
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationDetails
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationKind
import io.github.yesidrobayo.shopledger.domain.rule.stock.ReorderPlanner
import io.github.yesidrobayo.shopledger.domain.rule.stock.ReorderQuery
import io.github.yesidrobayo.shopledger.domain.rule.stock.ReorderSupplierFilter
import io.github.yesidrobayo.shopledger.domain.usecase.stock.ReorderOverview
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.PurchasePrefillLine
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * La reposición sugerida sin base: la sugerencia sale de las ventas y del mínimo, se cambia
 * antes de comprar, y la compra y la lista llevan solo lo que no va en cero. Nombres y cifras
 * inventados.
 */
class ReorderViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val savedState = SavedStateHandle()
    private val sales = MutableStateFlow(mapOf(1L to QuantityMilli(45_000)))
    private val locations = MutableStateFlow<List<Location>>(emptyList())
    private val asked = mutableListOf<Pair<ReorderQuery, Long?>>()
    private var failing = false

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    @Test
    fun `the example of the rule suggests 38 and leaves 8 days`() = runTest(dispatcher) {
        val model = viewModel()

        val group = model.state.groups.first()
        assertEquals("Distribuidora Sur", group.supplierName)
        val coffee = group.lines.single()
        assertEquals(QuantityMilli(38_000), coffee.quantity)
        assertEquals(8L, coffee.daysLeft)
        // 38 a 1.250 cada una.
        assertEquals(Money(47_500), group.estimatedCost)
        assertNull(model.state.empty)
        assertEquals("30", model.state.coverText)
    }

    @Test
    fun `each step changes the quantity, never below zero, and the cost follows`() =
        runTest(dispatcher) {
            val model = viewModel()

            model.act(this, ReorderAction.Increment(1L))
            assertEquals(QuantityMilli(39_000), model.coffee().quantity)
            assertEquals(Money(48_750), model.state.groups.first().estimatedCost)
            repeat(41) { model.act(this, ReorderAction.Decrement(1L)) }

            assertEquals(QuantityMilli.ZERO, model.coffee().quantity)
            // En cero queda fuera de la compra y de la lista.
            assertTrue(model.state.groups.first().toBuy.isEmpty())
            assertNull(model.purchaseOf(10L))
        }

    @Test
    fun `a product with a recipe offers to produce and stays out of the purchase`() =
        runTest(dispatcher) {
            val made = MutableStateFlow(setOf(1L))
            val model = viewModel(producible = made)

            val group = model.state.groups.first()
            assertTrue(model.coffee().producible)
            // Sigue en la lista con su sugerencia, pero no se le compra al proveedor.
            assertEquals(QuantityMilli(38_000), model.coffee().quantity)
            assertTrue(group.toBuy.isEmpty())
            assertEquals(Money.ZERO, group.estimatedCost)
            assertNull(model.purchaseOf(10L))

            // Sin la receta, vuelve a la compra con su costo.
            made.value = emptySet()
            advanceUntilIdle()
            assertFalse(model.coffee().producible)
            assertEquals(Money(47_500), model.state.groups.first().estimatedCost)
            assertEquals(1, model.purchaseOf(10L)?.prefillLines?.size)
        }

    @Test
    fun `without knowing what is produced everything goes to the purchase`() = runTest(dispatcher) {
        val model = viewModel(producible = flow { error("lectura inventada que falla") })

        assertFalse(model.coffee().producible)
        assertEquals(1, model.state.groups.first().toBuy.size)
    }

    @Test
    fun `the purchase carries the supplier, the location and only what is not zero`() =
        runTest(dispatcher) {
            locations.value = listOf(location(1L), location(2L))
            val model = viewModel()
            model.act(this, ReorderAction.LocationChange(2L))

            val purchase = model.purchaseOf(10L)

            val expected = AppNavKey.Purchase(
                prefillSupplierId = 10L,
                prefillLines = listOf(PurchasePrefillLine(1L, 38_000L)),
                prefillLocationId = 2L,
            )
            assertEquals(expected, purchase)
            assertEquals(2L, asked.last().second)
        }

    @Test
    fun `a purchase unit steps by whole boxes and goes to the purchase in boxes`() =
        runTest(dispatcher) {
            val model = viewModel(boxes = true)

            // 38 unidades piden 2 cajas de 24.
            assertEquals(QuantityMilli(48_000), model.coffee().quantity)
            model.act(this, ReorderAction.Decrement(1L))
            assertEquals(QuantityMilli(24_000), model.coffee().quantity)

            val line = requireNotNull(model.purchaseOf(10L)).prefillLines.single()
            assertEquals(PurchasePrefillLine(1L, 1_000L, BOX.id), line)
        }

    @Test
    fun `changing the days to cover asks again and drops what was edited by hand`() =
        runTest(dispatcher) {
            val model = viewModel()
            model.act(this, ReorderAction.Increment(1L))

            model.act(this, ReorderAction.CoverChange("10"))

            assertEquals(10L, asked.last().first.coverDays)
            // 1.500 por día durante 10 días, más 5 de mínimo, menos 12: 8.
            assertEquals(QuantityMilli(8_000), model.coffee().quantity)
        }

    @Test
    fun `days out of range keep the last valid list and mark too many`() = runTest(dispatcher) {
        val model = viewModel()

        model.act(this, ReorderAction.CoverChange("400"))
        assertTrue(model.state.coverTooHigh)
        model.act(this, ReorderAction.CoverChange(""))
        model.act(this, ReorderAction.CoverChange("0"))

        assertFalse(model.state.coverTooHigh)
        assertEquals(30L, asked.last().first.coverDays)
        assertEquals("0", model.state.coverText)
    }

    @Test
    fun `a supplier filter keeps what was edited by hand`() = runTest(dispatcher) {
        val model = viewModel()
        model.act(this, ReorderAction.Increment(1L))

        model.act(this, ReorderAction.SupplierFilterChange(ReorderSupplierFilter.Of(10L)))
        model.act(this, ReorderAction.CategoryChange(7L))

        assertEquals(ReorderSupplierFilter.Of(10L), asked.last().first.supplier)
        assertEquals(7L, asked.last().first.categoryId)
        assertEquals(QuantityMilli(39_000), model.coffee().quantity)
    }

    @Test
    fun `without sales the empty state asks for them, with sales nothing to restock`() =
        runTest(dispatcher) {
            sales.value = emptyMap()
            val model = viewModel(minimum = 0)
            assertEquals(ReorderEmpty.NEEDS_SALES, model.state.empty)

            sales.value = mapOf(1L to QuantityMilli(30))
            advanceUntilIdle()

            assertEquals(ReorderEmpty.NOTHING_TO_RESTOCK, model.state.empty)
        }

    @Test
    fun `a read failure shows the error, and retrying reads again`() = runTest(dispatcher) {
        failing = true
        val model = viewModel()
        assertTrue(model.state.loadFailed)

        failing = false
        model.act(this, ReorderAction.Retry)

        assertFalse(model.state.loadFailed)
        assertEquals(1, model.state.groups.size)
    }

    @Test
    fun `one location shows no selector, and what was chosen survives the process`() =
        runTest(dispatcher) {
            locations.value = listOf(location(1L))
            val first = viewModel()
            assertTrue(first.state.locations.isEmpty())
            first.act(this, ReorderAction.Increment(1L))
            first.act(this, ReorderAction.SupplierFilterChange(ReorderSupplierFilter.Without))

            val again = viewModel()

            assertEquals(ReorderSupplierFilter.Without, again.state.supplierFilter)
            assertEquals(ReorderSupplierFilter.Without, asked.last().first.supplier)
        }

    private fun TestScope.viewModel(
        boxes: Boolean = false,
        minimum: Long = 5_000,
        producible: Flow<Set<Long>> = flowOf(emptySet()),
    ): ReorderViewModel {
        val model = ReorderViewModel(
            planSource = { query, locationId ->
                asked += query to locationId
                overview(query, boxes, minimum)
            },
            locations = locations,
            savedState = savedState,
            computation = dispatcher,
            producible = producible,
        )
        backgroundScope.launch { model.uiState.collect {} }
        advanceUntilIdle()
        return model
    }

    private fun overview(
        query: ReorderQuery,
        boxes: Boolean,
        minimum: Long,
    ): Flow<ReorderOverview> {
        if (failing) return flow { error("lectura inventada que falla") }
        val conversions = if (boxes) {
            mapOf(1L to listOf(ConversionDetails(BOX.id, 24_000L, null, null, false, true)))
        } else {
            emptyMap()
        }
        return sales.map { sold ->
            ReorderOverview(
                plan = ReorderPlanner.plan(
                    query = query,
                    products = listOf(coffee(minimum)),
                    suppliers = listOf(SUPPLIER),
                    unitsOut = sold,
                    onHandAt = null,
                    conversions = conversions,
                ),
                units = mapOf(UNIT.id to UNIT, BOX.id to BOX),
                suppliers = listOf(SUPPLIER),
                categories = emptyList(),
            )
        }
    }

    private fun ReorderViewModel.act(scope: TestScope, action: ReorderAction) {
        onAction(action)
        scope.advanceUntilIdle()
    }

    private val ReorderViewModel.state: ReorderUiState get() = uiState.value

    private fun ReorderViewModel.coffee(): ReorderLineUi = state.groups.first().lines.single()

    private fun coffee(minimum: Long) = Product(
        id = 1L,
        details = ProductDetails(
            name = "Café molido",
            normalizedName = "cafe molido",
            categoryId = 7L,
            defaultSupplierId = SUPPLIER.id,
            baseUnitId = UNIT.id,
            stockMode = ProductStockMode.TRACKED,
            tracksLots = false,
            fixedUnitCost = Money.ZERO,
            minStock = QuantityMilli(minimum),
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
        salePrice = Money(2_000),
        initialCost = Money.ZERO,
        averageCost = Money(1_000),
        stockValue = Money.ZERO,
        onHand = QuantityMilli(12_000),
        replacementCost = Money(1_250),
        replacementCostAt = null,
        priceReviewSince = null,
        archivedAt = null,
        createdAt = AT,
        updatedAt = AT,
    )

    private fun location(id: Long) = Location(
        id = id,
        systemKey = null,
        details = LocationDetails("Bodega $id", "bodega $id", LocationKind.WAREHOUSE),
        archivedAt = null,
        createdAt = AT,
        updatedAt = AT,
    )

    private companion object {
        val AT: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000L)
        val UNIT = MeasureUnit(11L, "UNIT", null, null, UnitKind.COUNT, false, null)
        val BOX = MeasureUnit(12L, "BOX", null, null, UnitKind.COUNT, false, null)
        val SUPPLIER = Supplier(
            id = 10L,
            details = SupplierDetails(
                "Distribuidora Sur",
                "distribuidora sur",
                null,
                null,
                null,
                null,
            ),
            balance = Money.ZERO,
            archivedAt = null,
            createdAt = AT,
            updatedAt = AT,
        )
    }
}

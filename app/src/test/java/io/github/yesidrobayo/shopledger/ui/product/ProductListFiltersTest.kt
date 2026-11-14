package io.github.yesidrobayo.shopledger.ui.product

import app.cash.turbine.TurbineTestContext
import app.cash.turbine.test
import io.github.yesidrobayo.shopledger.domain.model.catalog.Category
import io.github.yesidrobayo.shopledger.domain.model.catalog.CategoryDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductUsage
import io.github.yesidrobayo.shopledger.domain.model.catalog.Supplier
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ObserveProductCatalogUseCase
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
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

/** Filtrar y ordenar la lista de productos: el armado puro y lo que hace la pantalla. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProductListFiltersTest {
    private val units = mapOf(COUNT_UNIT.id to COUNT_UNIT)
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun ids(content: ProductListContent): List<Long> =
        (content as ProductListContent.Products).sections.flatMap { it.items }.map { it.id }

    private fun Product.inCategory(id: Long?) = copy(details = details.copy(categoryId = id))

    private fun Product.fromSupplier(id: Long?) =
        copy(details = details.copy(defaultSupplierId = id))

    private fun Product.archived() = copy(archivedAt = ARCHIVED_AT)

    private fun Product.used(usage: ProductUsage) = copy(details = details.copy(usage = usage))

    @Test
    fun `category and usual supplier narrow the list, and the status picks archived`() {
        val rice = product(1L, "Arroz").inCategory(7L).fromSupplier(3L)
        val soap = product(2L, "Jabón").inCategory(8L).fromSupplier(3L)
        val salt = product(3L, "Sal").inCategory(7L)

        val catalog = listOf(rice, soap, salt)
        assertEquals(
            listOf(1L, 3L),
            ids(ProductListItems.build(catalog, units, "", ProductListFilters(categoryId = 7L))),
        )
        assertEquals(
            listOf(1L, 2L),
            ids(ProductListItems.build(catalog, units, "", ProductListFilters(supplierId = 3L))),
        )
        assertEquals(
            listOf(1L),
            ids(
                ProductListItems.build(
                    catalog,
                    units,
                    "",
                    ProductListFilters(categoryId = 7L, supplierId = 3L),
                ),
            ),
        )
        // Con el filtro de archivados, solo los archivados.
        val archived = listOf(soap.archived())
        val onlyArchived = ProductListFilters(archived = true)
        assertEquals(listOf(2L), ids(ProductListItems.build(archived, units, "", onlyArchived)))
        assertEquals(
            ProductListContent.FilteredOut,
            ProductListItems.build(archived, units, "", ProductListFilters()),
        )
    }

    @Test
    fun `the inputs filter shows what is an input, also what is sold too`() {
        val flour = product(1L, "Harina").used(ProductUsage.INPUT)
        val sugar = product(2L, "Azúcar").used(ProductUsage.BOTH)
        val bread = product(3L, "Pan")
        val catalog = listOf(sugar, flour, bread)

        assertEquals(
            listOf(2L, 1L),
            ids(ProductListItems.build(catalog, units, "", ProductListFilters(inputsOnly = true))),
        )
        // Sin el filtro, los insumos están en la lista como cualquier producto.
        assertEquals(
            listOf(2L, 1L, 3L),
            ids(ProductListItems.build(catalog, units, "", ProductListFilters())),
        )
        assertEquals(
            ProductListContent.FilteredOut,
            ProductListItems.build(
                listOf(bread),
                units,
                "",
                ProductListFilters(inputsOnly = true),
            ),
        )
        assertEquals(1, ProductListFilters(inputsOnly = true).activeCount)
    }

    @Test
    fun `the sheet offers the inputs filter only when there is an input`() = runTest(dispatcher) {
        val catalog = FakeCatalog()
        catalog.products.value = listOf(product(1L, "Pan"))
        val model = ProductListViewModel(
            ObserveProductCatalogUseCase(catalog, FakeUnits()),
            dispatcher,
            FixedChoices,
        )
        model.uiState.test {
            skipItems(1)
            assertFalse(awaitItem().showsUsageFilter)

            catalog.products.value =
                listOf(product(1L, "Pan"), product(2L, "Harina").used(ProductUsage.INPUT))
            var item = awaitItem()
            while (!item.showsUsageFilter) item = awaitItem()

            model.onAction(ProductListAction.FilterInputs(true))
            val inputs = expectContent {
                it is ProductListContent.Products && ids(it) == listOf(2L)
            }
            assertTrue(inputs.filters.inputsOnly)
            model.onAction(ProductListAction.FilterInputs(false))
            expectContent { it is ProductListContent.Products && ids(it) == listOf(2L, 1L) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `no product through the filters offers to clear them, not to create one`() {
        val catalog = listOf(product(1L, "Arroz"))

        assertEquals(
            ProductListContent.FilteredOut,
            ProductListItems.build(catalog, units, "", ProductListFilters(categoryId = 9L)),
        )
        // Sin archivados, el filtro deja la lista vacía, no el catálogo.
        assertEquals(
            ProductListContent.FilteredOut,
            ProductListItems.build(emptyList(), units, "", ProductListFilters(archived = true)),
        )
        // Lo buscado dentro de los filtros sigue ofreciendo crearlo.
        assertEquals(
            ProductListContent.NoResults("té"),
            ProductListItems.build(catalog, units, "té", ProductListFilters()),
        )
    }

    @Test
    fun `by stock, the lowest first and what has no stock at the end`() {
        val catalog = listOf(
            product(1L, "Aceite", onHand = 5_000L),
            product(2L, "Arroz", onHand = -1_000L),
            product(3L, "Corte", stockMode = ProductStockMode.UNTRACKED),
            product(4L, "Sal", onHand = 0L),
            product(5L, "Té", onHand = 5_000L),
        )

        val sorted = ProductListItems.build(
            catalog,
            units,
            "",
            ProductListFilters(sort = ProductSort.STOCK),
        ) as ProductListContent.Products

        // Los empates conservan el orden por nombre, y no hay encabezados por inicial.
        assertEquals(listOf(2L, 4L, 1L, 5L, 3L), sorted.sections.single().items.map { it.id })
        assertNull(sorted.sections.single().initial)
    }

    @Test
    fun `by margin, the lowest first and what has no defined margin at the end`() {
        val catalog = listOf(
            product(1L, "Aceite", salePrice = 1_000L, averageCost = 500L),
            product(2L, "Arroz", salePrice = 1_000L, averageCost = 900L),
            product(3L, "Café", salePrice = 1_000L),
            product(4L, "Sal", averageCost = 300L),
            product(5L, "Kit", salePrice = 1_000L, stockMode = ProductStockMode.COMPONENTS),
            product(6L, "Té", salePrice = 1_000L, averageCost = 1_200L),
        )

        assertEquals(
            listOf(6L, 2L, 1L, 3L, 4L, 5L),
            ids(
                ProductListItems.build(
                    catalog,
                    units,
                    "",
                    ProductListFilters(sort = ProductSort.MARGIN),
                ),
            ),
        )
    }

    @Test
    fun `five thousand products filter and sort in one pass`() {
        val catalog = (1L..5_000L)
            .map { id ->
                product(
                    id,
                    "Producto $id",
                    salePrice = 2_000L,
                    averageCost = id,
                    stockValue = id * 10,
                    onHand = 10_000L,
                )
                    .inCategory(id % 4)
            }
            .sortedBy { it.details.normalizedName }

        val content = ProductListItems.build(
            catalog,
            units,
            "",
            ProductListFilters(categoryId = 1L, sort = ProductSort.MARGIN),
        )

        val shown = ids(content)
        assertEquals(1_250, shown.size)
        assertTrue(shown.all { it % 4 == 1L })
        // A más costo, menos margen: primero el de costo más alto.
        assertEquals(4_997L, shown.first())
        assertEquals(1L, shown.last())
    }

    @Test
    fun `only category, supplier and status count as filters`() {
        assertEquals(0, ProductListFilters(sort = ProductSort.STOCK).activeCount)
        assertEquals(
            3,
            ProductListFilters(categoryId = 1L, supplierId = 2L, archived = true).activeCount,
        )
    }

    @Test
    fun `the archived filter reads the archived, and clearing keeps the order`() =
        runTest(dispatcher) {
            val catalog = FakeCatalog()
            catalog.products.value = listOf(product(1L, "Arroz"), product(2L, "Té").archived())
            val model = ProductListViewModel(
                ObserveProductCatalogUseCase(catalog, FakeUnits()),
                dispatcher,
                FixedChoices,
            )
            model.uiState.test {
                skipItems(1)
                val first = awaitItem()
                assertEquals(listOf(1L), ids(first.content))
                assertEquals(listOf(CATEGORY), first.categories)

                model.onAction(ProductListAction.OpenFilters)
                assertTrue(awaitItem().filtersOpen)
                model.onAction(ProductListAction.SortBy(ProductSort.STOCK))
                assertEquals(ProductSort.STOCK, expectFilters().sort)
                model.onAction(ProductListAction.FilterArchived(true))
                // Hasta que llega la lectura de los archivados, la lista no los inventa.
                val archived = expectContent {
                    it is ProductListContent.Products && ids(it) == listOf(2L)
                }
                assertTrue(archived.filters.archived)

                model.onAction(ProductListAction.ClearFilters)
                val cleared = expectContent {
                    it is ProductListContent.Products && ids(it) == listOf(1L)
                }
                assertFalse(cleared.filters.archived)
                assertEquals(ProductSort.STOCK, cleared.filters.sort)

                model.onAction(ProductListAction.FilterCategory(CATEGORY.id))
                expectContent { it == ProductListContent.FilteredOut }
                model.onAction(ProductListAction.FilterSupplier(9L))
                assertEquals(9L, expectFilters().supplierId)
                model.onAction(ProductListAction.CloseFilters)
                assertFalse(awaitItem().filtersOpen)
                cancelAndIgnoreRemainingEvents()
            }
        }

    private suspend fun TurbineTestContext<ProductListUiState>.expectFilters(): ProductListFilters {
        var item = awaitItem()
        while (item.content == ProductListContent.Loading) item = awaitItem()
        return item.filters
    }

    private suspend fun TurbineTestContext<ProductListUiState>.expectContent(
        wanted: (ProductListContent) -> Boolean,
    ): ProductListUiState {
        var item = awaitItem()
        while (!wanted(item.content)) item = awaitItem()
        return item
    }

    private object FixedChoices : ProductFilterChoices {
        override fun categories(): Flow<List<Category>> = flowOf(listOf(CATEGORY))

        override fun suppliers(): Flow<List<Supplier>> = flowOf(emptyList())
    }

    private companion object {
        val ARCHIVED_AT: Instant = Instant.fromEpochMilliseconds(1_800_000_000_000L)
        val CATEGORY = Category(
            id = 5L,
            details = CategoryDetails("Granos", "granos", targetMargin = null),
            createdAt = ARCHIVED_AT,
            updatedAt = ARCHIVED_AT,
        )
    }
}

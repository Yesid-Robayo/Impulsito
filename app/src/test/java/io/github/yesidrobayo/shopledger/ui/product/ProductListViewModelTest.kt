package io.github.yesidrobayo.shopledger.ui.product

import app.cash.turbine.test
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ObserveProductCatalogUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProductListViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val catalog = FakeCatalog()
    private val units = FakeUnits()

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun viewModel() =
        ProductListViewModel(ObserveProductCatalogUseCase(catalog, units), dispatcher)

    @Test
    fun `it starts loading and shows the empty catalog`() = runTest(dispatcher) {
        viewModel().uiState.test {
            assertEquals(ProductListUiState(), awaitItem())
            assertEquals(ProductListContent.EmptyCatalog, awaitItem().content)
        }
    }

    @Test
    fun `typing shows at once and searches after a short pause`() = runTest(dispatcher) {
        catalog.products.value = listOf(product(1L, "Jabón Líquido"), product(2L, "Arroz"))
        val model = viewModel()
        model.uiState.test {
            skipItems(1)
            assertEquals(
                2,
                (awaitItem().content as ProductListContent.Products).sections[0].items.size,
            )

            model.onAction(ProductListAction.QueryChange("jab"))
            val typed = awaitItem()
            assertEquals("jab", typed.query)
            // Todavía no busca: la lista sigue entera hasta la pausa.
            assertEquals(2, (typed.content as ProductListContent.Products).sections[0].items.size)

            advanceTimeBy(ProductListViewModel.SEARCH_PAUSE_MILLIS + 1)
            val found = awaitItem().content as ProductListContent.Products
            assertEquals(listOf(1L), found.sections[0].items.map { it.id })
        }
    }

    @Test
    fun `a new product saved elsewhere appears without reopening`() = runTest(dispatcher) {
        val model = viewModel()
        model.uiState.test {
            skipItems(1)
            assertEquals(ProductListContent.EmptyCatalog, awaitItem().content)

            catalog.products.value = listOf(product(1L, "Arroz"))
            val content = awaitItem().content as ProductListContent.Products
            assertEquals("Arroz", content.sections[0].items.single().name)
        }
    }

    @Test
    fun `a read failure shows the error and retry reads again`() = runTest(dispatcher) {
        catalog.failure = IllegalStateException("Falla inventada")
        catalog.products.value = listOf(product(1L, "Arroz"))
        val model = viewModel()
        model.uiState.test {
            skipItems(1)
            assertEquals(ProductListContent.ReadError, awaitItem().content)

            catalog.failure = null
            model.onAction(ProductListAction.Retry)
            assertTrue(awaitItem().content is ProductListContent.Products)
            assertEquals(2, catalog.reads)
        }
    }

    @Test
    fun `navigation actions do not change the state`() = runTest(dispatcher) {
        val model = viewModel()
        model.uiState.test {
            skipItems(2)
            model.onAction(ProductListAction.OpenProduct(1L))
            model.onAction(ProductListAction.CreateProduct("Arroz"))
            expectNoEvents()
        }
    }
}

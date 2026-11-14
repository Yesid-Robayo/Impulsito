package io.github.yesidrobayo.shopledger.ui.combo

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ComboAvailability
import io.github.yesidrobayo.shopledger.domain.rule.catalog.RecipePreview
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ComboSummary
import io.github.yesidrobayo.shopledger.ui.product.product
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** La lista de combos sin base: cifras, búsqueda, vacío y error. Nombres inventados. */
@OptIn(ExperimentalCoroutinesApi::class)
class CombosViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val kit = summary(1L, "Kit de té", Money(4_500L), ComboAvailability(3L, 11L), false)
    private val box = summary(2L, "Caja regalo", null, ComboAvailability(null, null), false)
    private val tray = summary(3L, "Bandeja", Money(1_000L), ComboAvailability(0L, 10L), true)

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun TestScope.viewModel(source: () -> Flow<List<ComboSummary>>): CombosViewModel {
        val model = CombosViewModel(source, dispatcher)
        backgroundScope.launch { model.uiState.collect {} }
        advanceUntilIdle()
        return model
    }

    @Test
    fun `each combo shows its components, what it is enough for and its margin`() =
        runTest(dispatcher) {
            val model = viewModel { flowOf(listOf(kit, box, tray)) }

            val items = model.uiState.value.items
            assertEquals(
                ComboItemUi(1L, "Kit de té", 2, 3L, Money(5_000L), margin(1_000L), true),
                items[0],
            )
            // Sin costo calculable no hay margen; con una hoja sin costo, el margen va apagado.
            assertEquals(Margin.NoData, items[1].margin)
            assertFalse(items[1].hasCost)
            assertFalse(items[2].hasCost)
        }

    @Test
    fun `the search filters by name and says when nothing matches`() = runTest(dispatcher) {
        val model = viewModel { flowOf(listOf(kit, box, tray)) }

        model.onAction(CombosAction.QueryChange("caja"))
        advanceUntilIdle()
        assertEquals(listOf(2L), model.uiState.value.items.map { it.id })

        model.onAction(CombosAction.QueryChange("zzz"))
        advanceUntilIdle()
        assertTrue(model.uiState.value.hasCombos)
        assertTrue(model.uiState.value.items.isEmpty())
    }

    @Test
    fun `no combos at all is the empty state`() = runTest(dispatcher) {
        val model = viewModel { flowOf(emptyList()) }

        assertFalse(model.uiState.value.loading)
        assertFalse(model.uiState.value.hasCombos)
    }

    @Test
    fun `a read failure says so and retries`() = runTest(dispatcher) {
        var fail = true
        val model = viewModel {
            if (fail) flow { throw IllegalStateException("lectura") } else flowOf(listOf(kit))
        }
        assertTrue(model.uiState.value.loadFailed)

        fail = false
        model.onAction(CombosAction.Retry)
        advanceUntilIdle()

        assertEquals(listOf(1L), model.uiState.value.items.map { it.id })
    }

    private fun summary(
        id: Long,
        name: String,
        cost: Money?,
        availability: ComboAvailability,
        uncosted: Boolean,
    ) = ComboSummary(
        product = product(id, name, salePrice = 5_000L, stockMode = ProductStockMode.COMPONENTS),
        componentCount = id.toInt() + 1,
        figures = RecipePreview(emptyMap(), cost, availability, uncosted),
    )

    private fun margin(basisPoints: Long) = Margin.Defined(BasisPoints(basisPoints))
}

package io.github.yesidrobayo.shopledger.ui.combo

import androidx.lifecycle.SavedStateHandle
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeComponent
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeLine
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.RecipeEditorData
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveRecipeResult
import io.github.yesidrobayo.shopledger.ui.product.COUNT_UNIT
import io.github.yesidrobayo.shopledger.ui.product.product
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
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
 * La receta de un combo sin base: lo que se ve sale de las reglas de la venta, los ciclos y el
 * tercer nivel se marcan al agregar, lo armado sobrevive al cierre del proceso y un error no borra
 * nada. Los valores son el kit de la regla: 2 de A a 1000 y 1 de B a 2500, con 9 de A y 3 de B,
 * y un precio de 5000. Nombres inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecipeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val kit = product(1L, "Kit de té", salePrice = 5_000L, stockMode = COMPONENTS)
    private val box = product(2L, "Caja regalo", salePrice = 9_000L, stockMode = COMPONENTS)
    private val tea =
        product(10L, "Té negro", averageCost = 1_000L, stockValue = 9_000L, onHand = 9_000L)
    private val cup =
        product(11L, "Taza", averageCost = 2_500L, stockValue = 7_500L, onHand = 3_000L)
    private val wrap =
        product(12L, "Envoltura", stockMode = ProductStockMode.UNTRACKED, fixedUnitCost = 300L)
    private val stored = MutableStateFlow(
        listOf(component(1L, kit.id, tea.id, 2_000L), component(2L, kit.id, cup.id, 1_000L)),
    )
    private val saves = mutableListOf<List<RecipeLine>>()
    private var result: SaveRecipeResult = SaveRecipeResult.Saved
    private val savedState = SavedStateHandle()
    private val symbols = NumberSymbols(decimalSeparator = ',', minusSign = '-', grouping = null)

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun editor(): Flow<RecipeEditorData?> = flow {
        stored.collect { rows ->
            val products = listOf(kit, box, tea, cup, wrap).associateBy { it.id }
            emit(RecipeEditorData(kit, rows, products, mapOf(COUNT_UNIT.id to COUNT_UNIT)))
        }
    }

    private fun TestScope.viewModel(
        state: SavedStateHandle = savedState,
        source: () -> Flow<RecipeEditorData?> = ::editor,
    ): RecipeViewModel {
        val model = RecipeViewModel(
            comboId = kit.id,
            editorSource = source,
            save = {
                saves += it
                result
            },
            savedState = state,
            quantitySymbols = symbols,
            computation = dispatcher,
        )
        backgroundScope.launch { model.uiState.collect {} }
        advanceUntilIdle()
        return model
    }

    private fun RecipeViewModel.act(scope: TestScope, action: RecipeAction) {
        onAction(action)
        scope.advanceUntilIdle()
    }

    private val RecipeViewModel.state: RecipeUiState get() = uiState.value

    @Test
    fun `the stored kit shows its cost, margin and what it is enough for`() = runTest(dispatcher) {
        val model = viewModel()

        val state = model.state
        assertEquals(listOf("2", "1"), state.rows.map { it.quantityText })
        assertEquals(listOf(Money(2_000L), Money(2_500L)), state.rows.map { it.lineCost })
        // 2000 + 2500 = 4500 de costo, 500 de ganancia y 10 % de margen sobre 5000.
        assertEquals(Money(4_500L), state.unitCost)
        assertEquals(Money(500L), state.profit)
        assertEquals(Margin.Defined(BasisPoints(1_000L)), state.margin)
        // 9 de A alcanzan para 4 y 3 de B para 3: limita la taza.
        assertEquals(3L, state.available)
        assertEquals("Taza", state.limitingName)
        assertFalse(state.hasUncosted)
        assertFalse(state.dirty)
    }

    @Test
    fun `adding a service starts with one unit, does not reduce stock and warns no cost`() =
        runTest(dispatcher) {
            val freeWrap = wrap.copy(details = wrap.details.copy(fixedUnitCost = Money.ZERO))
            val model = viewModel(
                source = {
                    flow {
                        stored.collect { rows ->
                            val products =
                                listOf(kit, box, tea, cup, freeWrap).associateBy { it.id }
                            emit(RecipeEditorData(kit, rows, products, emptyMap()))
                        }
                    }
                },
            )

            model.act(this, RecipeAction.OpenPicker)
            val offered = model.state.picker?.options.orEmpty().map { it.id }
            // Ni el propio combo ni lo que ya está en la receta se ofrecen.
            assertEquals(listOf(box.id, wrap.id), offered)
            model.act(this, RecipeAction.AddComponent(wrap.id))

            val row = model.state.rows.last()
            assertEquals("1", row.quantityText)
            assertFalse(row.tracked)
            assertTrue(model.state.hasUncosted)
            assertTrue(model.state.dirty)
            assertNull(model.state.picker)
        }

    @Test
    fun `a combo that holds this one is marked as a cycle as soon as it is added`() =
        runTest(dispatcher) {
            stored.value = stored.value + component(3L, box.id, kit.id, 1_000L)
            val model = viewModel()

            model.act(this, RecipeAction.AddComponent(box.id))

            assertEquals(RecipeRowError.Cycle("Kit de té"), model.state.rows.last().error)
            model.act(this, RecipeAction.Save)
            assertTrue(saves.isEmpty())
        }

    @Test
    fun `a blank quantity is marked only after trying to save`() = runTest(dispatcher) {
        val model = viewModel()

        model.act(this, RecipeAction.QuantityChange(1L, ""))
        assertNull(model.state.rows.first().error)
        model.act(this, RecipeAction.Save)

        assertEquals(RecipeRowError.NotANumber, model.state.rows.first().error)
        assertTrue(saves.isEmpty())
    }

    @Test
    fun `saving sends what is shown and ends with the saved message`() = runTest(dispatcher) {
        val model = viewModel()

        model.act(this, RecipeAction.QuantityChange(1L, "1,5"))
        model.act(this, RecipeAction.RemoveComponent(2L))
        model.act(this, RecipeAction.Save)

        assertEquals(listOf(listOf(RecipeLine(tea.id, QuantityMilli(1_500L)))), saves)
        assertTrue(model.state.saved)
        model.act(this, RecipeAction.SavedShown)
        assertTrue(model.closed.value)
    }

    @Test
    fun `a storage failure keeps the recipe and retries the same`() = runTest(dispatcher) {
        result = SaveRecipeResult.StorageFailed(StorageFailure.DISK_FULL)
        val model = viewModel()
        model.act(this, RecipeAction.QuantityChange(1L, "3"))

        model.act(this, RecipeAction.Save)

        assertEquals(RecipeNotice.SaveFailed(outOfSpace = true), model.state.notice)
        assertEquals("3", model.state.rows.first().quantityText)
        result = SaveRecipeResult.Saved
        model.act(this, RecipeAction.Retry)
        assertEquals(2, saves.size)
        assertEquals(saves[0], saves[1])
    }

    @Test
    fun `leaving with changes asks first, and without them leaves at once`() = runTest(dispatcher) {
        val model = viewModel()
        model.act(this, RecipeAction.Close)
        assertTrue(model.closed.value)

        val edited = viewModel(state = SavedStateHandle())
        edited.act(this, RecipeAction.QuantityChange(1L, "4"))
        edited.act(this, RecipeAction.Close)
        assertTrue(edited.state.discardAsked)
        assertFalse(edited.closed.value)
        edited.act(this, RecipeAction.DismissDialog)
        assertFalse(edited.state.discardAsked)
        edited.act(this, RecipeAction.Close)
        edited.act(this, RecipeAction.ConfirmLeave)
        assertTrue(edited.closed.value)
    }

    @Test
    fun `what was built survives the process being closed`() = runTest(dispatcher) {
        val model = viewModel()
        model.act(this, RecipeAction.QuantityChange(2L, "5"))
        model.act(this, RecipeAction.AddComponent(wrap.id))

        val restored = viewModel(state = savedState)

        assertEquals(listOf("2", "5", "1"), restored.state.rows.map { it.quantityText })
        assertTrue(restored.state.dirty)
    }

    @Test
    fun `a read failure says so and retries`() = runTest(dispatcher) {
        var fail = true
        val model = viewModel(
            source = {
                if (fail) flow { throw IllegalStateException("lectura") } else editor()
            },
        )
        assertTrue(model.state.loadFailed)

        fail = false
        model.act(this, RecipeAction.Retry)

        assertFalse(model.state.loadFailed)
        assertEquals(2, model.state.rows.size)
    }

    @Test
    fun `a product that is no longer a combo shows it is missing`() = runTest(dispatcher) {
        val model = viewModel(
            source = {
                flow {
                    val tracked = kit.copy(
                        details = kit.details.copy(stockMode = ProductStockMode.TRACKED),
                    )
                    emit(RecipeEditorData(tracked, emptyList(), mapOf(1L to tracked), emptyMap()))
                }
            },
        )

        assertTrue(model.state.missing)
        model.act(this, RecipeAction.Save)
        assertTrue(saves.isEmpty())
    }

    private fun component(id: Long, parent: Long, child: Long, milli: Long) =
        RecipeComponent(id, parent, child, QuantityMilli(milli))

    private companion object {
        val COMPONENTS = ProductStockMode.COMPONENTS
    }
}

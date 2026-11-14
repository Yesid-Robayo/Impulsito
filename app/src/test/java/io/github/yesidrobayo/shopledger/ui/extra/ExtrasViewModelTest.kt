package io.github.yesidrobayo.shopledger.ui.extra

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ModifierEditorData
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveModifiersResult
import io.github.yesidrobayo.shopledger.ui.product.CREATED
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
 * La pantalla de extras de un producto, sin base: lista los guardados con su precio, agrega uno
 * desde la búsqueda y quita otro guardando la lista entera en su orden, dice si un cambio no se
 * guardó, y resuelve el producto que ya no existe y la lectura que falla. Productos inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExtrasViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val arepa = product(1L, "Arepa", salePrice = 4_000L)
    private val cheese = product(2L, "Queso", salePrice = 1_000L)
    private val sauce = product(3L, "Salsa", salePrice = 200L)
    private val honey = product(4L, "Miel", salePrice = 500L)
    private val old = product(5L, "Antiguo").copy(archivedAt = CREATED)
    private val editor = MutableStateFlow<ModifierEditorData?>(
        ModifierEditorData(arepa, listOf(cheese, old), listOf(honey, sauce)),
    )
    private val saved = mutableListOf<List<Long>>()
    private var result: SaveModifiersResult = SaveModifiersResult.Saved

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun TestScope.viewModel(
        source: () -> Flow<ModifierEditorData?> = { editor },
    ): ExtrasViewModel {
        val model = ExtrasViewModel(
            editorSource = source,
            save = {
                saved += it
                result
            },
            computation = dispatcher,
        )
        backgroundScope.launch { model.uiState.collect {} }
        advanceUntilIdle()
        return model
    }

    private fun ExtrasViewModel.act(scope: TestScope, action: ExtrasAction) {
        onAction(action)
        scope.advanceUntilIdle()
    }

    @Test
    fun `the saved extras show with their price, and an archived one is marked`() =
        runTest(dispatcher) {
            val state = viewModel().uiState.value

            assertEquals("Arepa", state.productName)
            assertEquals(
                listOf(
                    ExtraRowUi(cheese.id, "Queso", Money(1_000L), archived = false),
                    ExtraRowUi(old.id, "Antiguo", Money.ZERO, archived = true),
                ),
                state.extras,
            )
            assertTrue(state.canAdd)
        }

    @Test
    fun `adding from the search saves the whole list with the new one last`() =
        runTest(dispatcher) {
            val model = viewModel()
            model.act(this, ExtrasAction.OpenPicker)
            model.act(this, ExtrasAction.QueryChange("sal"))

            assertEquals(
                listOf(ExtraOption(sauce.id, "Salsa")),
                model.uiState.value.picker?.options,
            )

            model.act(this, ExtrasAction.Add(sauce.id))

            assertEquals(listOf(listOf(cheese.id, old.id, sauce.id)), saved)
            assertNull(model.uiState.value.picker)
            assertFalse(model.uiState.value.saving)
        }

    @Test
    fun `removing one saves the rest in their order, and a failure is told`() =
        runTest(dispatcher) {
            val model = viewModel()
            result = SaveModifiersResult.StorageFailed(StorageFailure.DISK_FULL)

            model.act(this, ExtrasAction.Remove(cheese.id))

            assertEquals(listOf(listOf(old.id)), saved)
            assertTrue(model.uiState.value.saveFailed)

            result = SaveModifiersResult.Saved
            model.act(this, ExtrasAction.Remove(old.id))

            assertFalse(model.uiState.value.saveFailed)
        }

    @Test
    fun `a missing product and a failed read are told, and the read can be retried`() =
        runTest(dispatcher) {
            editor.value = null
            assertTrue(viewModel().uiState.value.missing)

            var attempts = 0
            val model = viewModel {
                flow {
                    attempts++
                    if (attempts == 1) error("lectura fallida")
                    emit(ModifierEditorData(arepa, emptyList(), emptyList()))
                }
            }
            assertTrue(model.uiState.value.loadFailed)

            model.act(this, ExtrasAction.Retry)

            assertFalse(model.uiState.value.loadFailed)
            assertTrue(model.uiState.value.extras.isEmpty())
        }
}

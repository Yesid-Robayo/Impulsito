package io.github.yesidrobayo.shopledger.ui.variant

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.VariantAxes
import io.github.yesidrobayo.shopledger.domain.model.catalog.VariantDraft
import io.github.yesidrobayo.shopledger.domain.model.catalog.VariantValues
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.CreateVariantsResult
import io.github.yesidrobayo.shopledger.ui.product.COUNT_UNIT
import io.github.yesidrobayo.shopledger.ui.product.FieldProblem
import io.github.yesidrobayo.shopledger.ui.product.ProductNumberFormat
import io.github.yesidrobayo.shopledger.ui.product.product
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
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

/** La pantalla de variantes, con respuestas fijas del dominio. Los datos son inventados. */
@OptIn(ExperimentalCoroutinesApi::class)
class VariantsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val operations = FakeVariantsOperations()

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun model() = VariantsViewModel(operations, NUMBERS)

    @Test
    fun `two axes build the matrix and create every checked combination`() = runTest(dispatcher) {
        val model = model()
        advanceUntilIdle()
        model.onAction(VariantsAction.AxisChange(VariantAxis.FIRST, "Talla"))
        addValues(model, VariantAxis.FIRST, "S", "M")
        model.onAction(VariantsAction.ShowSecondAxis)
        model.onAction(VariantsAction.AxisChange(VariantAxis.SECOND, "Color"))
        addValues(model, VariantAxis.SECOND, "Rojo", "Azul")
        model.onAction(VariantsAction.ToggleCombo(VariantCombo("M", "Azul")))
        model.onAction(VariantsAction.ApplyAllChange(VariantField.PRICE, "25000"))
        model.onAction(VariantsAction.ApplyToAll)
        val small = VariantCombo("S", "Rojo")
        model.onAction(VariantsAction.InputChange(small, VariantField.INITIAL_STOCK, "4"))

        assertEquals(3, model.uiState.value.newCombos.size)
        assertEquals(Money(2_500_000L), model.uiState.value.prices[small.key])
        model.onAction(VariantsAction.Save)
        advanceUntilIdle()

        assertEquals(VariantAxes("Talla", "Color"), operations.axes)
        assertEquals(
            listOf(
                VariantDraft(
                    VariantValues("S", "Rojo"),
                    salePrice = Money(2_500_000L),
                    initialStock = QuantityMilli(4_000L),
                ),
                VariantDraft(VariantValues("S", "Azul"), salePrice = Money(2_500_000L)),
                VariantDraft(VariantValues("M", "Rojo"), salePrice = Money(2_500_000L)),
            ),
            operations.drafts,
        )
        assertEquals(3, model.uiState.value.createdCount)
    }

    @Test
    fun `a repeated, empty or long value is marked on its field`() = runTest(dispatcher) {
        val model = model()
        advanceUntilIdle()
        addValues(model, VariantAxis.FIRST, "S")

        addValues(model, VariantAxis.FIRST, "s")
        assertEquals(
            VariantTextProblem.REPEATED,
            model.uiState.value.valueProblems[VariantAxis.FIRST],
        )
        addValues(model, VariantAxis.FIRST, "  ")
        assertEquals(
            VariantTextProblem.EMPTY,
            model.uiState.value.valueProblems[VariantAxis.FIRST],
        )
        addValues(model, VariantAxis.FIRST, "x".repeat(41))
        assertEquals(
            VariantTextProblem.TOO_LONG,
            model.uiState.value.valueProblems[VariantAxis.FIRST],
        )
        assertEquals(listOf("S"), model.uiState.value.firstValues)
    }

    @Test
    fun `existing variants fix the axes and only new combinations are created`() =
        runTest(dispatcher) {
            operations.parent.value = parentWith(variant(2L, "S", "Rojo"))
            val model = model()
            advanceUntilIdle()

            val state = model.uiState.value
            assertTrue(state.axesLocked)
            assertEquals("Talla", state.firstAxis)
            assertEquals(listOf("S"), state.firstValues)
            assertEquals(listOf("Rojo"), state.secondValues)
            assertTrue(state.newCombos.isEmpty())
            // Un valor ya usado no se quita, y el segundo eje no se vuelve a abrir.
            model.onAction(VariantsAction.RemoveValue(VariantAxis.FIRST, "S"))
            model.onAction(VariantsAction.ShowSecondAxis)
            assertEquals(listOf("S"), model.uiState.value.firstValues)

            addValues(model, VariantAxis.FIRST, "M")
            assertEquals(listOf(VariantCombo("M", "Rojo")), model.uiState.value.newCombos)
        }

    @Test
    fun `nothing checked, a bad number or a blank axis never reach the domain`() =
        runTest(dispatcher) {
            val model = model()
            advanceUntilIdle()
            model.onAction(VariantsAction.Save)
            assertEquals(VariantsNotice.NothingSelected, model.uiState.value.notice)

            addValues(model, VariantAxis.FIRST, "S")
            model.onAction(VariantsAction.Save)
            assertEquals(
                VariantTextProblem.EMPTY,
                model.uiState.value.axisProblems[VariantAxis.FIRST],
            )

            model.onAction(VariantsAction.AxisChange(VariantAxis.FIRST, "Talla"))
            val small = VariantCombo("S", null)
            model.onAction(VariantsAction.InputChange(small, VariantField.COST, "1,2,3"))
            model.onAction(VariantsAction.Save)
            advanceUntilIdle()

            assertEquals(
                FieldProblem.NotANumber,
                model.uiState.value.problemOf(small, VariantField.COST),
            )
            assertEquals(small.key, model.uiState.value.expanded)
            assertNull(operations.drafts)
        }

    @Test
    fun `each answer of the domain is shown without losing what was written`() =
        runTest(dispatcher) {
            val model = model()
            advanceUntilIdle()
            model.onAction(VariantsAction.AxisChange(VariantAxis.FIRST, "Talla"))
            addValues(model, VariantAxis.FIRST, "S", "M")
            val medium = VariantCombo("M", null)

            operations.result = CreateVariantsResult.DuplicateBarcode(1, "Gorra")
            model.onAction(VariantsAction.Save)
            advanceUntilIdle()
            assertEquals(
                FieldProblem.Duplicate("Gorra"),
                model.uiState.value.problemOf(medium, VariantField.BARCODE),
            )

            operations.result = CreateVariantsResult.ParentHasStock
            model.onAction(VariantsAction.Save)
            advanceUntilIdle()
            assertEquals(VariantsNotice.ParentHasStock, model.uiState.value.notice)

            operations.result = CreateVariantsResult.StorageFailed(StorageFailure.DISK_FULL)
            model.onAction(VariantsAction.Save)
            advanceUntilIdle()
            assertEquals(VariantsNotice.SaveFailed(true), model.uiState.value.notice)
            assertEquals(listOf("S", "M"), model.uiState.value.firstValues)
            assertNull(model.uiState.value.createdCount)
        }

    @Test
    fun `closing with something written asks before discarding`() = runTest(dispatcher) {
        val model = model()
        advanceUntilIdle()
        model.onAction(VariantsAction.Close)
        assertTrue(model.uiState.value.closed)

        val other = model()
        advanceUntilIdle()
        addValues(other, VariantAxis.FIRST, "S")
        other.onAction(VariantsAction.Close)
        assertTrue(other.uiState.value.confirmingDiscard)
        assertFalse(other.uiState.value.closed)
        other.onAction(VariantsAction.Discard)
        assertTrue(other.uiState.value.closed)
    }

    private fun addValues(model: VariantsViewModel, axis: VariantAxis, vararg values: String) {
        for (value in values) {
            model.onAction(VariantsAction.NewValueChange(axis, value))
            model.onAction(VariantsAction.AddValue(axis))
        }
    }

    private class FakeVariantsOperations : VariantsOperations {
        val parent = MutableStateFlow<VariantParent?>(parentWith())
        var axes: VariantAxes? = null
        var drafts: List<VariantDraft>? = null
        var result: CreateVariantsResult? = null

        override fun parent(): Flow<VariantParent?> = parent

        override suspend fun create(
            axes: VariantAxes,
            drafts: List<VariantDraft>,
        ): CreateVariantsResult {
            this.axes = axes
            this.drafts = drafts
            return result ?: CreateVariantsResult.Created(drafts.indices.map { it + 10L })
        }
    }

    private companion object {
        val SYMBOLS = NumberSymbols(decimalSeparator = ',', minusSign = '-', grouping = null)
        val NUMBERS = ProductNumberFormat(SYMBOLS, currencyDecimals = 2, quantitySymbols = SYMBOLS)

        fun parentWith(vararg variants: Product) =
            VariantParent(product(1L, "Camiseta"), COUNT_UNIT, variants.toList())

        fun variant(id: Long, first: String, second: String?): Product {
            val base = product(id, "Camiseta · $first · $second")
            return base.copy(
                details = base.details.copy(
                    parentProductId = 1L,
                    variantAxis1 = "Talla",
                    variantAxis2 = "Color",
                    variantValue1 = first,
                    variantValue2 = second,
                ),
            )
        }
    }
}

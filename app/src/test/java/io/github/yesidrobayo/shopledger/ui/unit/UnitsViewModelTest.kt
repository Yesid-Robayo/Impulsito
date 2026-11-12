package io.github.yesidrobayo.shopledger.ui.unit

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDraft
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionEntry
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionInputError
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductConversion
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitDraft
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitInputError
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitsOverview
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.DeleteConversionResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveConversionResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveUnitResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.UnitLifecycleResult
import io.github.yesidrobayo.shopledger.ui.product.ProductNumberFormat
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
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

/** La pantalla de unidades y sus hojas, con respuestas fijas del dominio. Los datos son inventados. */
@OptIn(ExperimentalCoroutinesApi::class)
class UnitsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val operations = FakeUnitsOperations()

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun model() = UnitsViewModel(operations, NAMES, NUMBERS)

    @Test
    fun `it loads the units, the conversions and the products`() = runTest(dispatcher) {
        val model = model()
        assertTrue(model.uiState.value.loading)

        advanceUntilIdle()

        val state = model.uiState.value
        assertFalse(state.loading)
        assertEquals(listOf(COUNT, KILO), state.active)
        assertEquals(listOf(OLD), state.archived)
        assertEquals(setOf(COUNT.id), state.inUse)
        assertEquals(1, state.conversions.size)
        assertEquals(listOf(RICE), state.products)
    }

    @Test
    fun `a failed read shows the error instead of closing and retry reads again`() =
        runTest(dispatcher) {
            operations.failingReads = 1
            val model = model()

            advanceUntilIdle()

            assertTrue(model.uiState.value.readFailed)
            assertFalse(model.uiState.value.loading)

            model.onAction(UnitsAction.Retry)
            advanceUntilIdle()

            assertFalse(model.uiState.value.readFailed)
            assertEquals(listOf(COUNT, KILO), model.uiState.value.active)
        }

    @Test
    fun `a new unit is saved with what was written and says so`() = runTest(dispatcher) {
        val model = model()
        advanceUntilIdle()

        model.onAction(UnitsAction.NewUnit)
        model.unitEditor.onAction(UnitEditorAction.Name("Bulto"))
        model.unitEditor.onAction(UnitEditorAction.Symbol("blt"))
        model.unitEditor.onAction(UnitEditorAction.Kind(UnitKind.MASS))
        model.unitEditor.onAction(UnitEditorAction.AllowsFraction(true))
        model.unitEditor.onAction(UnitEditorAction.Save)
        advanceUntilIdle()

        assertEquals(
            listOf(null to UnitDraft("Bulto", "blt", UnitKind.MASS, true)),
            operations.unitsSaved,
        )
        assertNull(model.unitEditor.editor.value)
        assertEquals(UnitsMessage.UnitSaved, model.uiState.value.message)
        model.onAction(UnitsAction.MessageShown)
        assertNull(model.uiState.value.message)
    }

    @Test
    fun `the translated name of an initial unit is not taken by a new one`() = runTest(dispatcher) {
        val model = model()
        advanceUntilIdle()

        model.onAction(UnitsAction.NewUnit)
        model.unitEditor.onAction(UnitEditorAction.Name(" kilogram "))
        model.unitEditor.onAction(UnitEditorAction.Save)
        advanceUntilIdle()

        assertEquals(UnitNameProblem.DUPLICATE, model.unitEditor.editor.value?.nameProblem)
        assertTrue(operations.unitsSaved.isEmpty())
        // Escribir de nuevo borra el error, y lo escrito sigue ahí.
        model.unitEditor.onAction(UnitEditorAction.Name("Kilo"))
        assertNull(model.unitEditor.editor.value?.nameProblem)
    }

    @Test
    fun `an initial unit left as it was keeps following the language`() = runTest(dispatcher) {
        val model = model()
        advanceUntilIdle()

        model.onAction(UnitsAction.EditUnit(KILO.id))
        val editor = requireNotNull(model.unitEditor.editor.value)
        assertEquals("KILOGRAM", editor.name)
        assertTrue(editor.isInitial)
        model.unitEditor.onAction(UnitEditorAction.AllowsFraction(false))
        model.unitEditor.onAction(UnitEditorAction.Save)
        advanceUntilIdle()

        assertEquals(
            KILO.id to UnitDraft("", "", UnitKind.MASS, false),
            operations.unitsSaved.single(),
        )
    }

    @Test
    fun `errors of the domain go to their field and a failed save keeps the sheet`() =
        runTest(dispatcher) {
            val model = model()
            advanceUntilIdle()
            model.onAction(UnitsAction.NewUnit)
            model.unitEditor.onAction(UnitEditorAction.Name("Bulto"))

            operations.unitResult =
                SaveUnitResult.Invalid(
                    setOf(UnitInputError.NAME_TOO_LONG, UnitInputError.SYMBOL_TOO_LONG),
                )
            model.unitEditor.onAction(UnitEditorAction.Save)
            advanceUntilIdle()
            val invalid = requireNotNull(model.unitEditor.editor.value)
            assertEquals(UnitNameProblem.TOO_LONG, invalid.nameProblem)
            assertTrue(invalid.symbolTooLong)

            operations.unitResult = SaveUnitResult.StorageFailed(StorageFailure.DISK_FULL)
            model.unitEditor.onAction(UnitEditorAction.Save)
            advanceUntilIdle()
            val failed = requireNotNull(model.unitEditor.editor.value)
            assertEquals(SaveFailure(outOfSpace = true), failed.saveFailed)
            assertEquals("Bulto", failed.name)
            assertFalse(failed.saving)

            operations.unitResult = SaveUnitResult.DuplicateName
            model.unitEditor.onAction(UnitEditorAction.Save)
            advanceUntilIdle()
            assertEquals(UnitNameProblem.DUPLICATE, model.unitEditor.editor.value?.nameProblem)

            operations.unitResult = SaveUnitResult.Invalid(setOf(UnitInputError.NAME_EMPTY))
            model.unitEditor.onAction(UnitEditorAction.Save)
            advanceUntilIdle()
            assertEquals(UnitNameProblem.EMPTY, model.unitEditor.editor.value?.nameProblem)

            operations.unitResult = SaveUnitResult.NotFound
            model.unitEditor.onAction(UnitEditorAction.Save)
            advanceUntilIdle()
            assertEquals(SaveFailure(outOfSpace = false), model.unitEditor.editor.value?.saveFailed)

            model.unitEditor.onAction(UnitEditorAction.Dismiss)
            assertNull(model.unitEditor.editor.value)
        }

    @Test
    fun `archiving and deleting ask first, and reactivating a taken name says so`() =
        runTest(dispatcher) {
            val model = model()
            advanceUntilIdle()

            model.onAction(UnitsAction.AskArchive(KILO.id))
            assertEquals(UnitsDialog.Archive(KILO.id, "KILOGRAM"), model.uiState.value.dialog)
            model.onAction(UnitsAction.ConfirmDialog)
            advanceUntilIdle()
            assertEquals(listOf("archive" to KILO.id), operations.lifecycle)
            assertNull(model.uiState.value.message)

            model.onAction(UnitsAction.AskDelete(KILO.id))
            model.onAction(UnitsAction.DismissDialog)
            assertNull(model.uiState.value.dialog)
            model.onAction(UnitsAction.AskDelete(KILO.id))
            model.onAction(UnitsAction.ConfirmDialog)
            advanceUntilIdle()
            assertEquals("delete" to KILO.id, operations.lifecycle.last())

            operations.lifecycleResult = UnitLifecycleResult.NameTaken
            model.onAction(UnitsAction.Reactivate(OLD.id))
            advanceUntilIdle()
            assertEquals(UnitsMessage.NameTaken, model.uiState.value.message)

            operations.lifecycleResult = UnitLifecycleResult.StorageFailed(StorageFailure.IO_ERROR)
            model.onAction(UnitsAction.Reactivate(OLD.id))
            advanceUntilIdle()
            assertEquals(UnitsMessage.Failed(outOfSpace = false), model.uiState.value.message)

            operations.lifecycleResult = UnitLifecycleResult.InUse
            model.onAction(UnitsAction.Reactivate(OLD.id))
            advanceUntilIdle()
            assertEquals(UnitsMessage.Failed(outOfSpace = false), model.uiState.value.message)
            // Sin diálogo abierto, confirmar no hace nada.
            model.onAction(UnitsAction.ConfirmDialog)
        }

    @Test
    fun `a box of 24 is read from the field and saved as 24000 thousandths`() =
        runTest(dispatcher) {
            val model = model()
            advanceUntilIdle()
            model.onAction(UnitsAction.SelectTab(UnitsTab.CONVERSIONS))
            assertEquals(UnitsTab.CONVERSIONS, model.uiState.value.tab)

            model.onAction(UnitsAction.NewConversion)
            val editor = model.conversionEditor
            editor.onAction(ConversionEditorAction.Product(RICE.id))
            editor.onAction(ConversionEditorAction.AlternateUnit(BOX.id))
            editor.onAction(ConversionEditorAction.Factor("24"))
            editor.onAction(ConversionEditorAction.Price("95,5"))
            editor.onAction(ConversionEditorAction.Barcode(" 7701 "))
            editor.onAction(ConversionEditorAction.ForSale(false))
            editor.onAction(ConversionEditorAction.ForPurchase(true))
            editor.onAction(ConversionEditorAction.Save)
            advanceUntilIdle()

            assertEquals(
                null to
                    ConversionDraft(RICE.id, BOX.id, 24_000L, Money(9_550L), "7701", false, true),
                operations.conversionsSaved.single(),
            )
            assertNull(editor.editor.value)
            assertEquals(UnitsMessage.ConversionSaved, model.uiState.value.message)
        }

    @Test
    fun `missing choices, a zero factor and bad numbers are marked without saving`() =
        runTest(dispatcher) {
            val model = model()
            advanceUntilIdle()
            model.onAction(UnitsAction.NewConversion)
            val editor = model.conversionEditor

            editor.onAction(ConversionEditorAction.Price("1.2.3"))
            editor.onAction(ConversionEditorAction.Barcode("9".repeat(65)))
            editor.onAction(ConversionEditorAction.Save)
            assertEquals(
                mapOf(
                    ConversionField.PRODUCT to ConversionProblem.Missing,
                    ConversionField.UNIT to ConversionProblem.Missing,
                    ConversionField.FACTOR to ConversionProblem.Zero,
                    ConversionField.PRICE to ConversionProblem.NotANumber,
                    ConversionField.BARCODE to ConversionProblem.TooLong(64),
                ),
                editor.editor.value?.errors,
            )

            editor.onAction(ConversionEditorAction.Factor("0"))
            editor.onAction(ConversionEditorAction.Price("99999999999999"))
            editor.onAction(ConversionEditorAction.Save)
            assertEquals(
                ConversionProblem.Zero,
                editor.editor.value?.errors?.get(ConversionField.FACTOR),
            )
            assertEquals(
                ConversionProblem.TooHigh,
                editor.editor.value?.errors?.get(ConversionField.PRICE),
            )

            editor.onAction(ConversionEditorAction.Factor("2000000"))
            editor.onAction(ConversionEditorAction.Save)
            assertEquals(
                ConversionProblem.TooHigh,
                editor.editor.value?.errors?.get(ConversionField.FACTOR),
            )

            editor.onAction(ConversionEditorAction.Factor("1,2345"))
            editor.onAction(ConversionEditorAction.Save)
            assertEquals(
                ConversionProblem.NotANumber,
                editor.editor.value?.errors?.get(ConversionField.FACTOR),
            )
            assertTrue(operations.conversionsSaved.isEmpty())
        }

    @Test
    fun `the answers of the domain go to their field`() = runTest(dispatcher) {
        val model = model()
        advanceUntilIdle()
        val editor = model.conversionEditor

        fun attempt(result: SaveConversionResult): ConversionEditor {
            operations.conversionResult = result
            model.onAction(UnitsAction.NewConversion)
            editor.onAction(ConversionEditorAction.Product(RICE.id))
            editor.onAction(ConversionEditorAction.AlternateUnit(BOX.id))
            editor.onAction(ConversionEditorAction.Factor("24"))
            editor.onAction(ConversionEditorAction.Save)
            dispatcher.scheduler.advanceUntilIdle()
            return requireNotNull(editor.editor.value)
        }

        assertEquals(
            ConversionProblem.Taken,
            attempt(SaveConversionResult.UnitTaken).errors[ConversionField.UNIT],
        )
        assertEquals(
            ConversionProblem.DuplicateBarcode("Aceite 900 ml"),
            attempt(
                SaveConversionResult.DuplicateBarcode("Aceite 900 ml"),
            ).errors[ConversionField.BARCODE],
        )
        assertEquals(
            ConversionProblem.Missing,
            attempt(SaveConversionResult.ProductUnavailable).errors[ConversionField.PRODUCT],
        )
        assertEquals(
            ConversionProblem.Missing,
            attempt(SaveConversionResult.UnitUnavailable).errors[ConversionField.UNIT],
        )
        val invalid =
            attempt(
                SaveConversionResult.Invalid(
                    ConversionInputError.entries.toSet(),
                ),
            ).errors
        assertEquals(ConversionProblem.SameAsBase, invalid[ConversionField.UNIT])
        assertEquals(ConversionProblem.TooHigh, invalid[ConversionField.PRICE])
        assertEquals(ConversionProblem.TooLong(64), invalid[ConversionField.BARCODE])
        assertEquals(
            SaveFailure(outOfSpace = true),
            attempt(SaveConversionResult.StorageFailed(StorageFailure.DISK_FULL)).saveFailed,
        )
        assertEquals(
            SaveFailure(outOfSpace = false),
            attempt(SaveConversionResult.NotFound).saveFailed,
        )
    }

    @Test
    fun `changing the factor of a saved conversion asks first`() = runTest(dispatcher) {
        val model = model()
        advanceUntilIdle()
        model.onAction(UnitsAction.EditConversion(SAVED.id))
        val editor = model.conversionEditor
        val opened = requireNotNull(editor.editor.value)
        assertEquals("24", opened.factor)
        assertEquals("120", opened.price)
        assertTrue(opened.unitLocked)

        editor.onAction(ConversionEditorAction.Factor("12"))
        editor.onAction(ConversionEditorAction.Save)
        assertTrue(editor.editor.value?.confirmingFactorChange == true)
        editor.onAction(ConversionEditorAction.KeepEditing)
        assertFalse(editor.editor.value?.confirmingFactorChange == true)
        assertTrue(operations.conversionsSaved.isEmpty())

        editor.onAction(ConversionEditorAction.Save)
        editor.onAction(ConversionEditorAction.ConfirmFactorChange)
        advanceUntilIdle()

        assertEquals(SAVED.id, operations.conversionsSaved.single().first)
        assertEquals(12_000L, operations.conversionsSaved.single().second.factorMilli)
    }

    @Test
    fun `removing a conversion asks first and a failure keeps the sheet`() = runTest(dispatcher) {
        val model = model()
        advanceUntilIdle()
        model.onAction(UnitsAction.EditConversion(SAVED.id))
        val editor = model.conversionEditor

        operations.deleteResult = DeleteConversionResult.StorageFailed(StorageFailure.DISK_FULL)
        editor.onAction(ConversionEditorAction.AskDelete)
        assertTrue(editor.editor.value?.confirmingDelete == true)
        editor.onAction(ConversionEditorAction.ConfirmDelete)
        advanceUntilIdle()
        assertEquals(SaveFailure(outOfSpace = true), editor.editor.value?.saveFailed)

        operations.deleteResult = DeleteConversionResult.Deleted
        editor.onAction(ConversionEditorAction.ConfirmDelete)
        advanceUntilIdle()
        assertNull(editor.editor.value)
        assertEquals(listOf(SAVED.id, SAVED.id), operations.deleted)
        // Quitar no se anuncia como guardar.
        assertNull(model.uiState.value.message)
    }

    private class FakeUnitsOperations : UnitsOperations {
        val overview =
            MutableStateFlow(UnitsOverview(listOf(COUNT, KILO), listOf(OLD), setOf(COUNT.id)))
        var unitResult: SaveUnitResult? = null
        var conversionResult: SaveConversionResult? = null
        var lifecycleResult: UnitLifecycleResult = UnitLifecycleResult.Done
        var deleteResult: DeleteConversionResult = DeleteConversionResult.Deleted
        val unitsSaved = mutableListOf<Pair<Long?, UnitDraft>>()
        val conversionsSaved = mutableListOf<Pair<Long?, ConversionDraft>>()
        val lifecycle = mutableListOf<Pair<String, Long>>()
        val deleted = mutableListOf<Long>()

        /** Cuántas lecturas de las unidades fallan antes de responder. */
        var failingReads = 0

        override fun units(): Flow<UnitsOverview> = if (failingReads > 0) {
            failingReads--
            flow { throw IllegalStateException("lectura fallida de prueba") }
        } else {
            overview
        }

        override fun conversions(): Flow<List<ConversionEntry>> =
            MutableStateFlow(listOf(ConversionEntry(SAVED, RICE)))

        override fun products(): Flow<List<Product>> = MutableStateFlow(listOf(RICE))

        override suspend fun saveUnit(editingId: Long?, draft: UnitDraft): SaveUnitResult {
            val result = unitResult ?: SaveUnitResult.Saved(editingId ?: 40L)
            if (result is SaveUnitResult.Saved) unitsSaved += editingId to draft
            return result
        }

        override suspend fun archiveUnit(id: Long): UnitLifecycleResult {
            lifecycle += "archive" to id
            return lifecycleResult
        }

        override suspend fun reactivateUnit(id: Long): UnitLifecycleResult {
            lifecycle += "reactivate" to id
            return lifecycleResult
        }

        override suspend fun deleteUnit(id: Long): UnitLifecycleResult {
            lifecycle += "delete" to id
            return lifecycleResult
        }

        override suspend fun saveConversion(
            editingId: Long?,
            draft: ConversionDraft,
        ): SaveConversionResult {
            val result = conversionResult ?: SaveConversionResult.Saved(editingId ?: 50L)
            if (result is SaveConversionResult.Saved) conversionsSaved += editingId to draft
            return result
        }

        override suspend fun deleteConversion(id: Long): DeleteConversionResult {
            deleted += id
            return deleteResult
        }
    }

    private companion object {
        val SYMBOLS = NumberSymbols(decimalSeparator = ',', minusSign = '-', grouping = null)
        val NUMBERS = ProductNumberFormat(SYMBOLS, currencyDecimals = 2, quantitySymbols = SYMBOLS)

        /** Sin recursos: la clave de una inicial hace de nombre traducido. */
        val NAMES =
            object : UnitNames {
                override fun name(unit: MeasureUnit): String = unit.name ?: unit.systemKey.orEmpty()

                override fun symbol(unit: MeasureUnit): String? = unit.symbol
            }
        val COUNT = MeasureUnit(1L, "UNIT", null, null, UnitKind.COUNT, false, null)
        val KILO = MeasureUnit(2L, "KILOGRAM", null, null, UnitKind.MASS, true, null)
        val BOX = MeasureUnit(10L, "BOX", null, null, UnitKind.COUNT, false, null)
        val OLD =
            MeasureUnit(
                20L,
                null,
                "Fardo",
                null,
                UnitKind.OTHER,
                false,
                Instant.fromEpochMilliseconds(5L),
            )
        val RICE =
            Product(
                id = 7L,
                details =
                    ProductDetails(
                        "Arroz 1 kg", "arroz 1 kg", null, null, COUNT.id, ProductStockMode.TRACKED,
                        false, Money.ZERO, QuantityMilli.ZERO, null, null, null, null, null, null,
                        null, null, null, null, null, null,
                    ),
                salePrice = Money(5_000L),
                initialCost = Money.ZERO,
                averageCost = Money(417L),
                stockValue = Money(10_000L),
                onHand = QuantityMilli(24_000L),
                replacementCost = Money.ZERO,
                replacementCostAt = null,
                priceReviewSince = null,
                archivedAt = null,
                createdAt = Instant.fromEpochMilliseconds(0L),
                updatedAt = Instant.fromEpochMilliseconds(0L),
            )
        val SAVED =
            ProductConversion(
                30L,
                RICE.id,
                ConversionDetails(
                    BOX.id,
                    24_000L,
                    Money(12_000L),
                    null,
                    forSale = true,
                    forPurchase = true,
                ),
            )
    }
}

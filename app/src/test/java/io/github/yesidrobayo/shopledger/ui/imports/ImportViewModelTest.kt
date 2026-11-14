package io.github.yesidrobayo.shopledger.ui.imports

import androidx.lifecycle.SavedStateHandle
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportApplyResult
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportCatalog
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportExistingPolicy
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportFileRead
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportIssue
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportPreview
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportRejection
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportRow
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportRowIssue
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportSheet
import io.github.yesidrobayo.shopledger.domain.model.imports.ImportTarget
import io.github.yesidrobayo.shopledger.domain.model.region.DecimalSeparator
import io.github.yesidrobayo.shopledger.domain.model.settings.CsvSeparator
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.rule.imports.ImportColumnMatcher
import io.github.yesidrobayo.shopledger.domain.rule.imports.ImportNumberFormat
import io.github.yesidrobayo.shopledger.domain.rule.imports.ImportRowReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
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
 * El asistente de importar sin base ni archivos: un lector falso, el reconocimiento de columnas
 * con encabezados escritos a mano y la lectura de filas real. Nombres y cifras inventados.
 */
class ImportViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val savedState = SavedStateHandle()
    private val headers = mapOf(
        "name" to listOf("Nombre"),
        "sale_price" to listOf("Precio de venta"),
        "stock_value" to listOf("Valor del stock"),
    )
    private var fileRead: ImportFileRead = ImportFileRead.Read(
        ImportSheet(
            header = listOf("Nombre", "Precio venta", "Valor del stock"),
            rows = listOf(
                ImportRow(2, listOf("Arroz", "4.200", "0")),
                ImportRow(3, listOf("Sal", "1,234", "0")),
                ImportRow(4, listOf("", "1", "0")),
            ),
            separator = CsvSeparator.SEMICOLON,
        ),
        fileName = "productos.csv",
    )
    private val separatorsAsked = mutableListOf<CsvSeparator?>()
    private var applyResult: ImportApplyResult = ImportApplyResult.Applied(1, 0, 0)
    private val applied = mutableListOf<Pair<ImportPreview, ImportExistingPolicy>>()
    private val written = mutableListOf<Pair<String, String>>()

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    @Test
    fun `an unknown column goes to the mapping and the rest is recognized`() = runTest(dispatcher) {
        val model = opened()

        val state = model.uiState.value
        assertEquals(ImportStep.MAP, state.step)
        assertEquals("productos.csv", state.fileName)
        assertEquals(3, state.rowCount)
        assertEquals(listOf("name", null, null), state.columns.map { it.field })
        assertEquals(listOf(false, true, false), state.columns.map { it.unmatched })
        assertEquals(listOf("Arroz", "Sal"), state.columns[0].examples)
        // `4.200` y `1,234` no deciden: manda el decimal del formato del negocio.
        assertEquals(DecimalSeparator.COMMA, state.decimal)
    }

    @Test
    fun `review waits until every required field has a column`() = runTest(dispatcher) {
        fileRead = ImportFileRead.Read(
            ImportSheet(listOf("Producto"), listOf(ImportRow(2, listOf("Té"))), null),
            fileName = null,
        )
        val model = opened()

        model.onAction(ImportAction.Review)
        advanceUntilIdle()
        assertEquals(ImportStep.MAP, model.uiState.value.step)
        assertEquals(listOf("name"), model.uiState.value.missing)

        model.onAction(ImportAction.ChooseField(0, "name"))
        advanceUntilIdle()
        assertTrue(model.uiState.value.missing.isEmpty())
        model.onAction(ImportAction.Review)
        advanceUntilIdle()
        assertEquals(ImportStep.REVIEW, model.uiState.value.step)
        assertEquals(1, model.uiState.value.preview?.ready?.size)
    }

    @Test
    fun `two columns with the same field are marked`() = runTest(dispatcher) {
        val model = opened()

        model.onAction(ImportAction.ChooseField(1, "name"))
        model.onAction(ImportAction.Review)
        advanceUntilIdle()

        assertEquals(listOf("name"), model.uiState.value.duplicated)
        assertEquals(ImportStep.MAP, model.uiState.value.step)
    }

    @Test
    fun `the review lists the errors of each row and applies the valid ones`() =
        runTest(dispatcher) {
            val model = opened()
            model.onAction(ImportAction.ChooseField(1, "sale_price"))
            model.onAction(ImportAction.Review)
            advanceUntilIdle()

            val state = model.uiState.value
            assertEquals(ImportStep.REVIEW, state.step)
            assertEquals(
                listOf(
                    // Con coma decimal, `1,234` trae tres decimales.
                    ImportRowIssue(3, "sale_price", ImportIssue.DECIMALS),
                    ImportRowIssue(4, "name", ImportIssue.MISSING),
                ),
                state.issues,
            )
            assertEquals(1, state.toApply)

            model.onAction(ImportAction.Apply)
            advanceUntilIdle()

            assertEquals(ImportExistingPolicy.SKIP, applied.single().second)
            assertEquals(ImportStep.DONE, model.uiState.value.step)
            assertEquals(ImportDoneUi(1, 0, 0, 2), model.uiState.value.done)
        }

    @Test
    fun `changing the decimal separator reviews the rows again`() = runTest(dispatcher) {
        val model = opened()
        model.onAction(ImportAction.ChooseField(1, "sale_price"))
        advanceUntilIdle()

        model.onAction(ImportAction.ChangeDecimal(DecimalSeparator.POINT))
        advanceUntilIdle()

        // Con punto decimal, `1,234` es mil doscientos treinta y cuatro, y `4.200` trae tres
        // decimales.
        assertEquals(
            listOf(
                ImportRowIssue(2, "sale_price", ImportIssue.DECIMALS),
                ImportRowIssue(4, "name", ImportIssue.MISSING),
            ),
            model.uiState.value.issues,
        )
    }

    @Test
    fun `changing the column separator reads the file again`() = runTest(dispatcher) {
        val model = opened()

        model.onAction(ImportAction.ChangeSeparator(CsvSeparator.COMMA))
        advanceUntilIdle()

        assertEquals(listOf(null, CsvSeparator.COMMA), separatorsAsked)
    }

    @Test
    fun `a rejected file stays in the first step with its reason`() = runTest(dispatcher) {
        fileRead = ImportFileRead.Rejected(ImportRejection.TOO_MANY_ROWS)
        val model = opened()

        val state = model.uiState.value
        assertEquals(ImportStep.FILE, state.step)
        assertEquals(ImportFileProblem.Rejected(ImportRejection.TOO_MANY_ROWS), state.fileProblem)
        assertFalse(state.reading)
    }

    @Test
    fun `a file without headers stays in the first step`() = runTest(dispatcher) {
        fileRead = ImportFileRead.Read(
            ImportSheet(listOf("", " "), listOf(ImportRow(2, listOf("a", "b"))), null),
            fileName = null,
        )
        val model = opened()

        assertEquals(ImportFileProblem.NoHeader, model.uiState.value.fileProblem)
    }

    @Test
    fun `a failed import changes nothing and can be retried`() = runTest(dispatcher) {
        applyResult = ImportApplyResult.StorageFailed(StorageFailure.DISK_FULL)
        val model = reviewing()

        model.onAction(ImportAction.Apply)
        advanceUntilIdle()

        assertEquals(ImportStep.REVIEW, model.uiState.value.step)
        assertEquals(ImportNotice.APPLY_OUT_OF_SPACE, model.uiState.value.notice)
        assertFalse(model.uiState.value.applying)
    }

    @Test
    fun `an outdated review is prepared again`() = runTest(dispatcher) {
        applyResult = ImportApplyResult.Outdated
        val model = reviewing()

        model.onAction(ImportAction.Apply)
        advanceUntilIdle()

        assertEquals(ImportNotice.OUTDATED, model.uiState.value.notice)
        assertTrue(model.uiState.value.preview != null)
    }

    @Test
    fun `leaving with a file asks first, and back goes from review to mapping`() =
        runTest(dispatcher) {
            val model = reviewing()
            assertFalse(model.closesDirectly())

            model.onAction(ImportAction.Back)
            assertEquals(ImportStep.MAP, model.uiState.value.step)
            model.onAction(ImportAction.Back)
            assertTrue(model.uiState.value.confirmLeave)
            model.onAction(ImportAction.DismissLeave)
            assertFalse(model.uiState.value.confirmLeave)
        }

    @Test
    fun `the rows with errors are saved as a neutralized csv`() = runTest(dispatcher) {
        val model = reviewing()

        model.onAction(ImportAction.ErrorsDestination("content://destino"))
        advanceUntilIdle()

        val (destination, content) = written.single()
        assertEquals("content://destino", destination)
        assertTrue(content.contains("\"Sal\";\"1,234\";\"0\""))
        assertFalse(content.contains("Arroz"))
    }

    @Test
    fun `the target survives and importing another file starts over`() = runTest(dispatcher) {
        val model = viewModel()
        model.onAction(ImportAction.ChooseTarget(ImportTarget.SUPPLIERS))
        assertEquals(ImportTarget.SUPPLIERS.name, savedState.get<String>("import.target"))

        val restored = viewModel()
        assertEquals(ImportTarget.SUPPLIERS, restored.uiState.value.target)
        restored.onAction(ImportAction.ImportAnother)
        assertNull(restored.uiState.value.fileName)
        assertEquals(ImportStep.FILE, restored.uiState.value.step)
    }

    private fun TestScope.reviewing(): ImportViewModel {
        val model = opened()
        model.onAction(ImportAction.ChooseField(1, "sale_price"))
        model.onAction(ImportAction.Review)
        advanceUntilIdle()
        return model
    }

    private fun TestScope.opened(): ImportViewModel {
        val model = viewModel()
        model.onAction(ImportAction.FileChosen("content://archivo"))
        advanceUntilIdle()
        return model
    }

    private fun viewModel(): ImportViewModel = ImportViewModel(
        currency = flowOf(ImportCurrencyUi("XTS", confirmed = true, locked = true)),
        preferredSeparator = { CsvSeparator.SEMICOLON },
        defaultDecimal = { DecimalSeparator.COMMA },
        tools = ImportTools(
            reader = { _, separator, _, onRows ->
                separatorsAsked += separator
                onRows(3)
                fileRead
            },
            matcher = ImportColumnMatcher { key -> headers[key].orEmpty() },
            prepare = { sheet, target, columns, decimal ->
                val format = ImportNumberFormat(decimal, 2, spreadsheet = sheet.separator == null)
                ImportRowReader(target, columns, format, ImportCatalog()).read(sheet)
            },
            apply = { preview, policy ->
                applied += preview to policy
                applyResult
            },
            writeErrors = { destination, content ->
                written += destination to content
                true
            },
        ),
        savedState = savedState,
        computation = dispatcher,
    )
}

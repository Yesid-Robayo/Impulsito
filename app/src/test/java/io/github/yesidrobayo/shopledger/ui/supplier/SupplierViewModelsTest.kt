package io.github.yesidrobayo.shopledger.ui.supplier

import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierForm
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierFormIssue
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierOverview
import io.github.yesidrobayo.shopledger.domain.model.catalog.SuppliersOverview
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveSupplierResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SupplierLifecycleResult
import io.github.yesidrobayo.shopledger.ui.unit.SaveFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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

/** La lista y el detalle de proveedores, y su hoja, con respuestas fijas del dominio. */
@OptIn(ExperimentalCoroutinesApi::class)
class SupplierViewModelsTest {
    private val dispatcher = StandardTestDispatcher()
    private val operations = FakeSupplierOperations()

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    @Test
    fun `the list shows active and archived apart, and archived starts closed`() =
        runTest(dispatcher) {
            val model = SupplierListViewModel(operations)
            observe(model.uiState)
            assertEquals(SupplierListContent.Loading, model.uiState.value.content)

            advanceUntilIdle()

            val state = model.uiState.value
            assertEquals(
                SupplierListContent.Suppliers(SupplierSamples.active, SupplierSamples.archived),
                state.content,
            )
            assertFalse(state.showArchived)
            model.onAction(SupplierListAction.ToggleArchived)
            advanceUntilIdle()
            assertTrue(model.uiState.value.showArchived)
        }

    @Test
    fun `searching ignores case and accents, and no match offers to create it`() =
        runTest(dispatcher) {
            val model = SupplierListViewModel(operations)
            observe(model.uiState)

            model.onAction(SupplierListAction.QueryChange("LACTEOS"))
            advanceUntilIdle()
            assertEquals(
                listOf(3L),
                (model.uiState.value.content as SupplierListContent.Suppliers).active.map {
                    it.supplier.id
                },
            )

            model.onAction(SupplierListAction.QueryChange(" sol "))
            advanceUntilIdle()
            assertEquals(SupplierListContent.NoResults("sol"), model.uiState.value.content)

            model.onAction(SupplierListAction.NewSupplier("sol"))
            assertEquals("sol", model.editor.editor.value?.name)
        }

    @Test
    fun `no supplier at all is the empty state`() {
        assertEquals(
            SupplierListContent.Empty,
            SupplierListViewModel.contentOf(SuppliersOverview(emptyList(), emptyList()), "x"),
        )
    }

    @Test
    fun `a failed read shows the error and retrying reads again`() = runTest(dispatcher) {
        operations.failReads = true
        val model = SupplierListViewModel(operations)
        observe(model.uiState)
        advanceUntilIdle()
        assertEquals(SupplierListContent.ReadError, model.uiState.value.content)

        operations.failReads = false
        model.onAction(SupplierListAction.Retry)
        advanceUntilIdle()
        assertTrue(model.uiState.value.content is SupplierListContent.Suppliers)
    }

    @Test
    fun `the sheet keeps what was written on errors and closes on save`() = runTest(dispatcher) {
        val model = SupplierListViewModel(operations)
        observe(model.uiState)
        model.onAction(SupplierListAction.NewSupplier())
        val editor = model.editor
        editor.onAction(SupplierEditorAction.Name("Granja"))
        editor.onAction(SupplierEditorAction.Phone("uno"))
        operations.saveResult = SaveSupplierResult.Invalid(setOf(SupplierFormIssue.PHONE_FORMAT))

        editor.onAction(SupplierEditorAction.Save)
        advanceUntilIdle()
        assertEquals(setOf(SupplierFormIssue.PHONE_FORMAT), editor.editor.value?.issues)
        assertEquals("uno", editor.editor.value?.phone)

        // Volver a tocar el teléfono quita su error, y no los de otros campos.
        editor.onAction(SupplierEditorAction.Phone("555"))
        assertEquals(emptySet<SupplierFormIssue>(), editor.editor.value?.issues)

        operations.saveResult = SaveSupplierResult.DuplicateName("Granja El Alto")
        editor.onAction(SupplierEditorAction.Save)
        advanceUntilIdle()
        assertEquals("Granja El Alto", editor.editor.value?.duplicateName)

        operations.saveResult = SaveSupplierResult.StorageFailed(StorageFailure.DISK_FULL)
        editor.onAction(SupplierEditorAction.Save)
        advanceUntilIdle()
        assertEquals(SaveFailure(outOfSpace = true), editor.editor.value?.saveFailed)

        operations.saveResult = SaveSupplierResult.Saved(9L)
        editor.onAction(SupplierEditorAction.Save)
        advanceUntilIdle()
        assertNull(editor.editor.value)
        assertEquals(SupplierMessage.Saved, model.uiState.value.message)
        assertEquals(null to SupplierForm("Granja", "555"), operations.saved.last())
    }

    @Test
    fun `reactivating from the list says why when a name is taken`() = runTest(dispatcher) {
        val model = SupplierListViewModel(operations)
        observe(model.uiState)
        operations.lifecycleResult = SupplierLifecycleResult.NameTaken("Panadería La Esquina")

        model.onAction(SupplierListAction.Reactivate(4L))
        advanceUntilIdle()

        assertEquals(SupplierMessage.NameTaken, model.uiState.value.message)
        model.onAction(SupplierListAction.MessageShown)
        advanceUntilIdle()
        assertNull(model.uiState.value.message)
    }

    @Test
    fun `the detail opens the sheet with the stored data`() = runTest(dispatcher) {
        val model = SupplierDetailViewModel(1L, operations)
        observe(model.uiState)
        advanceUntilIdle()

        model.onAction(SupplierDetailAction.Edit)

        val editor = requireNotNull(model.editor.editor.value)
        assertEquals(1L, editor.editingId)
        assertEquals("compras@example.com", editor.email)
        assertEquals(SupplierSamples.NOTES, editor.notes)
    }

    @Test
    fun `deleting asks first, then closes the detail and says so`() = runTest(dispatcher) {
        val model = SupplierDetailViewModel(1L, operations)
        observe(model.uiState)
        advanceUntilIdle()

        model.onAction(SupplierDetailAction.AskDelete)
        advanceUntilIdle()
        assertEquals(
            SupplierDialog.Delete(1L, SupplierSamples.LONG_NAME),
            model.uiState.value.dialog,
        )

        model.onAction(SupplierDetailAction.ConfirmDialog)
        advanceUntilIdle()

        assertEquals(listOf("delete:1"), operations.lifecycle)
        assertNull(model.uiState.value.dialog)
        assertTrue(model.uiState.value.closed)
        assertEquals(SupplierMessage.Deleted, model.uiState.value.message)
    }

    @Test
    fun `a supplier with history is not deleted, and the detail stays open`() =
        runTest(dispatcher) {
            val model = SupplierDetailViewModel(1L, operations)
            observe(model.uiState)
            advanceUntilIdle()
            operations.lifecycleResult = SupplierLifecycleResult.HasHistory

            model.onAction(SupplierDetailAction.AskDelete)
            model.onAction(SupplierDetailAction.ConfirmDialog)
            advanceUntilIdle()

            assertFalse(model.uiState.value.closed)
            assertEquals(SupplierMessage.HasHistory, model.uiState.value.message)
        }

    @Test
    fun `archiving and reactivating ask the domain and say how it went`() = runTest(dispatcher) {
        val model = SupplierDetailViewModel(1L, operations)
        observe(model.uiState)
        advanceUntilIdle()

        model.onAction(SupplierDetailAction.AskArchive)
        model.onAction(SupplierDetailAction.DismissDialog)
        assertNull(model.uiState.value.dialog)
        model.onAction(SupplierDetailAction.AskArchive)
        model.onAction(SupplierDetailAction.ConfirmDialog)
        advanceUntilIdle()
        assertEquals(SupplierMessage.Archived, model.uiState.value.message)

        operations.lifecycleResult =
            SupplierLifecycleResult.StorageFailed(StorageFailure.DISK_FULL)
        model.onAction(SupplierDetailAction.Reactivate)
        advanceUntilIdle()
        assertEquals(SupplierMessage.Failed(outOfSpace = true), model.uiState.value.message)
        assertEquals(listOf("archive:1", "reactivate:1"), operations.lifecycle)
    }

    @Test
    fun `a missing supplier shows that it could not be opened`() = runTest(dispatcher) {
        val model = SupplierDetailViewModel(99L, operations)
        observe(model.uiState)
        advanceUntilIdle()

        assertEquals(SupplierDetailContent.Missing, model.uiState.value.content)
        model.onAction(SupplierDetailAction.Edit)
        assertNull(model.editor.editor.value)
    }

    private fun TestScope.observe(flow: Flow<*>) {
        backgroundScope.launch(dispatcher) { flow.collect {} }
    }
}

/** Operaciones con respuestas fijas: los proveedores de las muestras, y lo que se pidió. */
private class FakeSupplierOperations : SupplierOperations {
    var failReads = false
    var saveResult: SaveSupplierResult = SaveSupplierResult.Saved(1L)
    var lifecycleResult: SupplierLifecycleResult = SupplierLifecycleResult.Done
    val saved = mutableListOf<Pair<Long?, SupplierForm>>()
    val lifecycle = mutableListOf<String>()

    private val overview =
        MutableStateFlow(SuppliersOverview(SupplierSamples.active, SupplierSamples.archived))

    override fun suppliers(): Flow<SuppliersOverview> = overview.map {
        check(!failReads) { "Lectura fallida" }
        it
    }

    override fun overview(supplierId: Long): Flow<SupplierOverview?> =
        MutableStateFlow(if (supplierId == 1L) SupplierSamples.overview() else null)

    override suspend fun save(editingId: Long?, form: SupplierForm): SaveSupplierResult {
        saved += editingId to form
        return saveResult
    }

    override suspend fun archive(supplierId: Long): SupplierLifecycleResult {
        lifecycle += "archive:$supplierId"
        return lifecycleResult
    }

    override suspend fun reactivate(supplierId: Long): SupplierLifecycleResult {
        lifecycle += "reactivate:$supplierId"
        return lifecycleResult
    }

    override suspend fun delete(supplierId: Long): SupplierLifecycleResult {
        lifecycle += "delete:$supplierId"
        return lifecycleResult
    }
}

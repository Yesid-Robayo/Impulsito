package io.github.yesidrobayo.shopledger.ui.supplier

import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierForm
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierOverview
import io.github.yesidrobayo.shopledger.domain.model.catalog.SuppliersOverview
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveSupplierResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SupplierLifecycleResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
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
import org.junit.Before
import org.junit.Test

/**
 * Vaciar datos de un proveedor: si la edición deja vacío un teléfono, un correo o unas notas que
 * tenían algo, el aviso dice que siguen en las copias anteriores y ofrece `Actualizar copias`, que
 * pide una copia nueva. Nada se borra solo. Datos inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SupplierContactClearedTest {
    private val dispatcher = StandardTestDispatcher()
    private val operations = ClearingOperations()

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun TestScope.detail(): SupplierDetailViewModel {
        val model = SupplierDetailViewModel(1L, operations)
        backgroundScope.launch(dispatcher) { model.uiState.collect {} }
        advanceUntilIdle()
        model.onAction(SupplierDetailAction.Edit)
        return model
    }

    @Test
    fun `clearing the phone says the data stays in earlier backups`() = runTest(dispatcher) {
        val model = detail()

        model.editor.onAction(SupplierEditorAction.Phone(""))
        model.editor.onAction(SupplierEditorAction.Save)
        advanceUntilIdle()

        assertEquals(SupplierMessage.ContactCleared, model.uiState.value.message)
        assertEquals(0, operations.copyRequests)
    }

    @Test
    fun `update backups asks for a new copy and nothing else`() = runTest(dispatcher) {
        val model = detail()
        model.editor.onAction(SupplierEditorAction.Email(""))
        model.editor.onAction(SupplierEditorAction.Notes("   "))
        model.editor.onAction(SupplierEditorAction.Save)
        advanceUntilIdle()

        model.onAction(SupplierDetailAction.UpdateCopies)
        advanceUntilIdle()

        assertEquals(SupplierMessage.ContactCleared, model.uiState.value.message)
        assertEquals(1, operations.copyRequests)
        assertEquals(emptyList<String>(), operations.lifecycle)
    }

    @Test
    fun `an edit that clears nothing is an ordinary save`() = runTest(dispatcher) {
        val model = detail()

        model.editor.onAction(SupplierEditorAction.Name("Granja El Bajo"))
        model.editor.onAction(SupplierEditorAction.Phone("+1 555 010 0100"))
        model.editor.onAction(SupplierEditorAction.Save)
        advanceUntilIdle()

        assertEquals(SupplierMessage.Saved, model.uiState.value.message)
    }

    @Test
    fun `a new supplier from the list clears nothing`() = runTest(dispatcher) {
        val model = SupplierListViewModel(operations)
        backgroundScope.launch(dispatcher) { model.uiState.collect {} }
        advanceUntilIdle()

        model.editor.openNew("Huerta")
        model.editor.onAction(SupplierEditorAction.Save)
        advanceUntilIdle()

        assertEquals(SupplierMessage.Saved, model.uiState.value.message)
    }
}

/** Operaciones con respuestas fijas: el proveedor de las muestras y cuántas copias se pidieron. */
private class ClearingOperations : SupplierOperations {
    var copyRequests = 0
    val lifecycle = mutableListOf<String>()

    override fun suppliers(): Flow<SuppliersOverview> =
        flowOf(SuppliersOverview(SupplierSamples.active, SupplierSamples.archived))

    override fun overview(supplierId: Long): Flow<SupplierOverview?> =
        flowOf(if (supplierId == 1L) SupplierSamples.overview() else null)

    override suspend fun save(editingId: Long?, form: SupplierForm): SaveSupplierResult =
        SaveSupplierResult.Saved(editingId ?: 2L)

    override suspend fun archive(supplierId: Long): SupplierLifecycleResult {
        lifecycle += "archive:$supplierId"
        return SupplierLifecycleResult.Done
    }

    override suspend fun reactivate(supplierId: Long): SupplierLifecycleResult {
        lifecycle += "reactivate:$supplierId"
        return SupplierLifecycleResult.Done
    }

    override suspend fun delete(supplierId: Long): SupplierLifecycleResult {
        lifecycle += "delete:$supplierId"
        return SupplierLifecycleResult.Done
    }

    override suspend fun updateCopies() {
        copyRequests++
    }
}

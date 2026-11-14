package io.github.yesidrobayo.shopledger.ui.extras

import io.github.yesidrobayo.shopledger.domain.model.catalog.CustomField
import io.github.yesidrobayo.shopledger.domain.model.catalog.CustomFieldDraft
import io.github.yesidrobayo.shopledger.domain.model.catalog.CustomFieldType
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductTag
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ExtraLifecycleResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveExtraResult
import io.github.yesidrobayo.shopledger.ui.unit.SaveFailure
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

/**
 * La pantalla de campos propios y etiquetas y su hoja, con respuestas fijas del dominio. Los datos
 * son inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CustomFieldsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val operations = FakeCustomFieldsOperations()

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun model() = CustomFieldsViewModel(operations)

    @Test
    fun `fields come in their order with the archived apart, and tags by name`() =
        runTest(dispatcher) {
            val model = model()
            assertTrue(model.uiState.value.loading)

            advanceUntilIdle()

            val state = model.uiState.value
            assertFalse(state.loading)
            assertEquals(listOf(BRAND, WARRANTY, EXPIRY), state.fields)
            assertEquals(listOf(SIZE), state.archived)
            assertEquals(listOf(GIFT, SEASON), state.tags)
        }

    @Test
    fun `a failed read shows the error instead of closing and retry reads again`() =
        runTest(dispatcher) {
            operations.failingReads = 1
            val model = model()
            advanceUntilIdle()

            assertTrue(model.uiState.value.readFailed)
            assertFalse(model.uiState.value.loading)

            model.onAction(CustomFieldsAction.Retry)
            advanceUntilIdle()

            assertFalse(model.uiState.value.readFailed)
            assertEquals(3, model.uiState.value.fields.size)
        }

    @Test
    fun `a new field is saved with its name and type and says so`() = runTest(dispatcher) {
        val model = model()
        advanceUntilIdle()

        model.onAction(CustomFieldsAction.NewField)
        model.editor.onAction(ExtraEditorAction.Name("Talla de zapato"))
        model.editor.onAction(ExtraEditorAction.Type(CustomFieldType.NUMBER))
        model.editor.onAction(ExtraEditorAction.Save)
        advanceUntilIdle()

        assertEquals(
            listOf(null to CustomFieldDraft("Talla de zapato", CustomFieldType.NUMBER)),
            operations.fieldsSaved,
        )
        assertNull(model.editor.editor.value)
        assertEquals(CustomFieldsMessage.FieldSaved, model.uiState.value.message)
        model.onAction(CustomFieldsAction.MessageShown)
        assertNull(model.uiState.value.message)
    }

    @Test
    fun `an empty, long or repeated name is marked on the field and nothing is sent`() =
        runTest(dispatcher) {
            val model = model()
            advanceUntilIdle()
            model.onAction(CustomFieldsAction.NewField)

            model.editor.onAction(ExtraEditorAction.Save)
            assertEquals(ExtraNameProblem.EMPTY, model.editor.editor.value?.nameProblem)

            model.editor.onAction(ExtraEditorAction.Name("x".repeat(41)))
            assertNull(model.editor.editor.value?.nameProblem)
            model.editor.onAction(ExtraEditorAction.Save)
            assertEquals(ExtraNameProblem.TOO_LONG, model.editor.editor.value?.nameProblem)

            // Sin mayúsculas ni tildes es el mismo nombre que `Garantía`.
            model.editor.onAction(ExtraEditorAction.Name(" GARANTIA "))
            model.editor.onAction(ExtraEditorAction.Save)
            advanceUntilIdle()
            assertEquals(ExtraNameProblem.DUPLICATE, model.editor.editor.value?.nameProblem)
            assertEquals(" GARANTIA ", model.editor.editor.value?.name)
            assertTrue(operations.fieldsSaved.isEmpty())
        }

    @Test
    fun `the type of a field with values is locked and stays as it was`() = runTest(dispatcher) {
        val model = model()
        advanceUntilIdle()

        model.onAction(CustomFieldsAction.EditField(BRAND.id))
        assertTrue(model.editor.editor.value?.typeLocked == true)
        model.editor.onAction(ExtraEditorAction.Type(CustomFieldType.DATE))
        assertEquals(CustomFieldType.TEXT, model.editor.editor.value?.type)

        // Un campo sin valores se edita con su tipo libre.
        model.onAction(CustomFieldsAction.EditField(EXPIRY.id))
        assertFalse(model.editor.editor.value?.typeLocked == true)
    }

    @Test
    fun `a type locked while editing comes back to the saved type`() = runTest(dispatcher) {
        operations.fieldResult = SaveExtraResult.TypeLocked
        val model = model()
        advanceUntilIdle()

        model.onAction(CustomFieldsAction.EditField(EXPIRY.id))
        model.editor.onAction(ExtraEditorAction.Type(CustomFieldType.TEXT))
        model.editor.onAction(ExtraEditorAction.Save)
        advanceUntilIdle()

        val editor = requireNotNull(model.editor.editor.value)
        assertTrue(editor.typeLocked)
        assertEquals(CustomFieldType.DATE, editor.type)
        assertFalse(editor.saving)
    }

    @Test
    fun `a storage failure keeps the sheet open with what was written`() = runTest(dispatcher) {
        operations.tagResult = SaveExtraResult.StorageFailed(StorageFailure.DISK_FULL)
        val model = model()
        advanceUntilIdle()

        model.onAction(CustomFieldsAction.NewTag)
        model.editor.onAction(ExtraEditorAction.Name("Importado"))
        model.editor.onAction(ExtraEditorAction.Save)
        advanceUntilIdle()

        val editor = requireNotNull(model.editor.editor.value)
        assertEquals(SaveFailure(outOfSpace = true), editor.saveFailed)
        assertEquals("Importado", editor.name)
        assertEquals(listOf(null to "Importado"), operations.tagsSaved)
    }

    @Test
    fun `moving a field sends the whole new order and shows it at once`() = runTest(dispatcher) {
        val model = model()
        advanceUntilIdle()

        model.onAction(CustomFieldsAction.MoveField(EXPIRY.id, up = true))
        assertEquals(listOf(BRAND, EXPIRY, WARRANTY), model.uiState.value.fields)
        advanceUntilIdle()
        assertEquals(listOf(listOf(BRAND.id, EXPIRY.id, WARRANTY.id)), operations.orders)

        // El primero no sube y el último no baja.
        model.onAction(CustomFieldsAction.MoveField(BRAND.id, up = true))
        model.onAction(CustomFieldsAction.MoveField(WARRANTY.id, up = false))
        advanceUntilIdle()
        assertEquals(1, operations.orders.size)
    }

    @Test
    fun `archiving, deleting and deleting a tag ask first`() = runTest(dispatcher) {
        val model = model()
        advanceUntilIdle()

        model.onAction(CustomFieldsAction.AskArchiveField(BRAND.id))
        assertEquals(
            CustomFieldsDialog.ArchiveField(BRAND.id, "Marca"),
            model.uiState.value.dialog,
        )
        model.onAction(CustomFieldsAction.DismissDialog)
        assertNull(model.uiState.value.dialog)
        assertTrue(operations.archived.isEmpty())

        model.onAction(CustomFieldsAction.AskArchiveField(BRAND.id))
        model.onAction(CustomFieldsAction.ConfirmDialog)
        model.onAction(CustomFieldsAction.AskDeleteField(EXPIRY.id))
        model.onAction(CustomFieldsAction.ConfirmDialog)
        model.onAction(CustomFieldsAction.AskDeleteTag(SEASON.id))
        assertEquals(
            CustomFieldsDialog.DeleteTag(SEASON.id, "Temporada", 12),
            model.uiState.value.dialog,
        )
        model.onAction(CustomFieldsAction.ConfirmDialog)
        advanceUntilIdle()

        assertEquals(listOf(BRAND.id to true), operations.archived)
        assertEquals(listOf(EXPIRY.id), operations.fieldsDeleted)
        assertEquals(listOf(SEASON.id), operations.tagsDeleted)
        assertNull(model.uiState.value.message)
    }

    @Test
    fun `reactivating a field whose name is taken says so`() = runTest(dispatcher) {
        operations.lifecycleResult = ExtraLifecycleResult.NameTaken
        val model = model()
        advanceUntilIdle()

        model.onAction(CustomFieldsAction.ReactivateField(SIZE.id))
        advanceUntilIdle()

        assertEquals(listOf(SIZE.id to false), operations.archived)
        assertEquals(CustomFieldsMessage.NameTaken, model.uiState.value.message)
    }

    @Test
    fun `a renamed tag is saved and says so`() = runTest(dispatcher) {
        val model = model()
        advanceUntilIdle()

        model.onAction(CustomFieldsAction.EditTag(GIFT.id))
        assertEquals("Para regalo", model.editor.editor.value?.name)
        model.editor.onAction(ExtraEditorAction.Name("Regalos"))
        model.editor.onAction(ExtraEditorAction.Save)
        advanceUntilIdle()

        assertEquals(listOf(GIFT.id to "Regalos"), operations.tagsSaved)
        assertEquals(CustomFieldsMessage.TagSaved, model.uiState.value.message)
    }

    private class FakeCustomFieldsOperations : CustomFieldsOperations {
        val catalog =
            MutableStateFlow(listOf(EXPIRY, BRAND, SIZE, WARRANTY) to listOf(SEASON, GIFT))
        var failingReads = 0
        var fieldResult: SaveExtraResult? = null
        var tagResult: SaveExtraResult? = null
        var lifecycleResult: ExtraLifecycleResult = ExtraLifecycleResult.Done
        val fieldsSaved = mutableListOf<Pair<Long?, CustomFieldDraft>>()
        val tagsSaved = mutableListOf<Pair<Long?, String>>()
        val archived = mutableListOf<Pair<Long, Boolean>>()
        val fieldsDeleted = mutableListOf<Long>()
        val tagsDeleted = mutableListOf<Long>()
        val orders = mutableListOf<List<Long>>()

        override fun catalog(): Flow<Pair<List<CustomField>, List<ProductTag>>> = flow {
            if (failingReads > 0) {
                failingReads--
                error("lectura fallida de prueba")
            }
            catalog.collect { emit(it) }
        }

        override suspend fun saveField(
            editingId: Long?,
            draft: CustomFieldDraft,
        ): SaveExtraResult {
            fieldsSaved += editingId to draft
            return fieldResult ?: SaveExtraResult.Saved(editingId ?: 50L)
        }

        override suspend fun archiveField(id: Long, archive: Boolean): ExtraLifecycleResult {
            archived += id to archive
            return lifecycleResult
        }

        override suspend fun deleteField(id: Long): ExtraLifecycleResult {
            fieldsDeleted += id
            return lifecycleResult
        }

        override suspend fun reorderFields(orderedIds: List<Long>): ExtraLifecycleResult {
            orders += orderedIds
            return lifecycleResult
        }

        override suspend fun saveTag(editingId: Long?, name: String): SaveExtraResult {
            tagsSaved += editingId to name
            return tagResult ?: SaveExtraResult.Saved(editingId ?: 60L)
        }

        override suspend fun deleteTag(id: Long): ExtraLifecycleResult {
            tagsDeleted += id
            return lifecycleResult
        }
    }

    private companion object {
        val BRAND = CustomField(1L, "Marca", CustomFieldType.TEXT, 0L, false, 12)
        val WARRANTY = CustomField(2L, "Garantía", CustomFieldType.NUMBER, 1L, false, 3)
        val EXPIRY = CustomField(3L, "Vence", CustomFieldType.DATE, 2L, false, 0)
        val SIZE = CustomField(4L, "Talla", CustomFieldType.TEXT, 3L, true, 7)
        val SEASON = ProductTag(1L, "Temporada", 12)
        val GIFT = ProductTag(2L, "Para regalo", 0)
    }
}

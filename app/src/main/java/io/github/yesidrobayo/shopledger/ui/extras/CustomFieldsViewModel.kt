package io.github.yesidrobayo.shopledger.ui.extras

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yesidrobayo.shopledger.domain.model.catalog.CustomField
import io.github.yesidrobayo.shopledger.domain.model.catalog.CustomFieldDraft
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductTag
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ExtraLifecycleResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveExtraResult
import io.github.yesidrobayo.shopledger.ui.ScreenReads
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Lo que la pantalla de campos y etiquetas le pide al dominio. Es una interfaz para que las
 * pruebas del ViewModel den respuestas fijas sin una base.
 */
interface CustomFieldsOperations {
    /** Todos los campos, vigentes y archivados, y todas las etiquetas. */
    fun catalog(): Flow<Pair<List<CustomField>, List<ProductTag>>>

    suspend fun saveField(editingId: Long?, draft: CustomFieldDraft): SaveExtraResult

    suspend fun archiveField(id: Long, archive: Boolean): ExtraLifecycleResult

    suspend fun deleteField(id: Long): ExtraLifecycleResult

    suspend fun reorderFields(orderedIds: List<Long>): ExtraLifecycleResult

    suspend fun saveTag(editingId: Long?, name: String): SaveExtraResult

    suspend fun deleteTag(id: Long): ExtraLifecycleResult
}

/**
 * La pantalla de campos propios y etiquetas: los campos en el orden de la persona, con los
 * archivados al final, las etiquetas por nombre, y la hoja para crearlos y editarlos.
 *
 * Un campo con valores no se elimina: solo se archiva, y sus valores se conservan. Cada acción que
 * cambia algo y no se deshace en el acto pregunta antes, y al terminar avisa.
 */
class CustomFieldsViewModel(private val operations: CustomFieldsOperations) : ViewModel() {
    private val state = MutableStateFlow(CustomFieldsUiState())
    val uiState: StateFlow<CustomFieldsUiState> = state.asStateFlow()

    /** La hoja de un campo o de una etiqueta, abierta o no. */
    val editor =
        ExtraEditorModel(
            scope = viewModelScope,
            activeFields = { state.value.fields },
            tags = { state.value.tags },
            saveField = operations::saveField,
            saveTag = operations::saveTag,
            onSaved = { kind ->
                show(
                    when (kind) {
                        ExtraKind.FIELD -> CustomFieldsMessage.FieldSaved
                        ExtraKind.TAG -> CustomFieldsMessage.TagSaved
                    },
                )
            },
        )

    /** Una falla de lectura se dice con `Reintentar`, sin cerrar nada. */
    private val reads = ScreenReads()

    init {
        viewModelScope.launch {
            reads.guard { operations.catalog() }.collect { (fields, tags) ->
                state.update {
                    it.copy(
                        loading = false,
                        fields = fields.filterNot(CustomField::isArchived).sortedWith(FIELD_ORDER),
                        archived = fields.filter(CustomField::isArchived).sortedWith(NAME_ORDER),
                        tags = tags.sortedWith(TAG_ORDER),
                    )
                }
            }
        }
        viewModelScope.launch {
            reads.failed.collect { failed ->
                state.update { it.copy(readFailed = failed, loading = it.loading && !failed) }
            }
        }
    }

    fun onAction(action: CustomFieldsAction) {
        when (action) {
            // La pantalla vuelve atrás: aquí no cambia nada.
            CustomFieldsAction.Back -> Unit

            CustomFieldsAction.Retry -> {
                state.update { it.copy(loading = true) }
                reads.retry()
            }

            is CustomFieldsAction.SelectTab -> state.update { it.copy(tab = action.tab) }

            CustomFieldsAction.NewField -> editor.openNew(ExtraKind.FIELD)

            is CustomFieldsAction.EditField -> fieldOf(action.fieldId)?.let(editor::openField)

            is CustomFieldsAction.MoveField -> move(action.fieldId, action.up)

            is CustomFieldsAction.AskArchiveField ->
                fieldOf(action.fieldId)?.let { field ->
                    state.update {
                        it.copy(dialog = CustomFieldsDialog.ArchiveField(field.id, field.name))
                    }
                }

            is CustomFieldsAction.AskDeleteField ->
                fieldOf(action.fieldId)?.let { field ->
                    state.update {
                        it.copy(dialog = CustomFieldsDialog.DeleteField(field.id, field.name))
                    }
                }

            is CustomFieldsAction.ReactivateField ->
                lifecycle { operations.archiveField(action.fieldId, archive = false) }

            CustomFieldsAction.NewTag -> editor.openNew(ExtraKind.TAG)

            is CustomFieldsAction.EditTag -> tagOf(action.tagId)?.let(editor::openTag)

            is CustomFieldsAction.AskDeleteTag ->
                tagOf(action.tagId)?.let { tag ->
                    val dialog = CustomFieldsDialog.DeleteTag(tag.id, tag.name, tag.productCount)
                    state.update { it.copy(dialog = dialog) }
                }

            CustomFieldsAction.ConfirmDialog -> confirmDialog()

            CustomFieldsAction.DismissDialog -> state.update { it.copy(dialog = null) }

            CustomFieldsAction.MessageShown -> state.update { it.copy(message = null) }
        }
    }

    private fun fieldOf(id: Long): CustomField? =
        (state.value.fields + state.value.archived).firstOrNull { it.id == id }

    private fun tagOf(id: Long): ProductTag? = state.value.tags.firstOrNull { it.id == id }

    /**
     * Cambia de lugar un campo vigente con su vecino. La lista se ve cambiada en el acto, y la
     * base recibe el orden completo: si no se puede guardar, la lectura siguiente devuelve el
     * anterior.
     */
    private fun move(fieldId: Long, up: Boolean) {
        val fields = state.value.fields
        val index = fields.indexOfFirst { it.id == fieldId }
        val target = if (up) index - 1 else index + 1
        if (index < 0 || target !in fields.indices) return
        val reordered = fields.toMutableList().apply { add(target, removeAt(index)) }
        state.update { it.copy(fields = reordered) }
        lifecycle { operations.reorderFields(reordered.map(CustomField::id)) }
    }

    private fun confirmDialog() {
        val dialog = state.value.dialog ?: return
        state.update { it.copy(dialog = null) }
        when (dialog) {
            is CustomFieldsDialog.ArchiveField ->
                lifecycle { operations.archiveField(dialog.fieldId, archive = true) }

            is CustomFieldsDialog.DeleteField ->
                lifecycle { operations.deleteField(dialog.fieldId) }

            is CustomFieldsDialog.DeleteTag -> lifecycle { operations.deleteTag(dialog.tagId) }
        }
    }

    private fun lifecycle(block: suspend () -> ExtraLifecycleResult) {
        viewModelScope.launch {
            val message =
                when (val result = block()) {
                    // La lista ya muestra el cambio: no hace falta un aviso.
                    ExtraLifecycleResult.Done, ExtraLifecycleResult.NotFound -> null

                    ExtraLifecycleResult.NameTaken -> CustomFieldsMessage.NameTaken

                    // El menú no ofrece eliminar un campo con valores; si llega, alguien le puso
                    // uno mientras se confirmaba, y no se hizo nada.
                    ExtraLifecycleResult.HasValues ->
                        CustomFieldsMessage.Failed(outOfSpace = false)

                    is ExtraLifecycleResult.StorageFailed ->
                        CustomFieldsMessage.Failed(result.failure.isOutOfSpace)
                }
            if (message != null) show(message)
        }
    }

    private fun show(message: CustomFieldsMessage) {
        state.update { it.copy(message = message) }
    }

    private companion object {
        val FIELD_ORDER: Comparator<CustomField> =
            compareBy<CustomField> { it.sortOrder }.thenBy { it.id }

        val NAME_ORDER: Comparator<CustomField> =
            compareBy<CustomField> { TextNormalizer.normalize(it.name) }.thenBy { it.id }

        val TAG_ORDER: Comparator<ProductTag> =
            compareBy<ProductTag> { TextNormalizer.normalize(it.name) }.thenBy { it.id }
    }
}

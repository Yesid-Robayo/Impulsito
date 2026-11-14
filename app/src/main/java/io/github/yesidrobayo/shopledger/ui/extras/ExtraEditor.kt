package io.github.yesidrobayo.shopledger.ui.extras

import androidx.compose.runtime.Immutable
import io.github.yesidrobayo.shopledger.domain.model.catalog.CustomField
import io.github.yesidrobayo.shopledger.domain.model.catalog.CustomFieldDraft
import io.github.yesidrobayo.shopledger.domain.model.catalog.CustomFieldType
import io.github.yesidrobayo.shopledger.domain.model.catalog.ExtraNameIssue
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductTag
import io.github.yesidrobayo.shopledger.domain.rule.catalog.CustomFieldRules
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ExtraNameCheck
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveExtraResult
import io.github.yesidrobayo.shopledger.ui.unit.SaveFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Qué se edita en la hoja: un campo propio o una etiqueta. */
enum class ExtraKind {
    FIELD,
    TAG,
}

/** Por qué no se guarda un nombre. */
enum class ExtraNameProblem {
    EMPTY,
    TOO_LONG,
    DUPLICATE,
}

/**
 * La hoja de un campo propio o de una etiqueta mientras se escribe.
 *
 * [editingId] nulo es uno nuevo. [type] solo cuenta en un campo, y [savedType] es el que tenía al
 * abrir la hoja. Con [typeLocked], el campo ya tiene valores y su tipo no cambia, porque se
 * escribieron para ese tipo. [saveFailed] dice si el último guardado falló, y con qué: lo escrito
 * siempre se conserva.
 */
@Immutable
data class ExtraEditor(
    val kind: ExtraKind,
    val editingId: Long?,
    val name: String,
    val type: CustomFieldType = CustomFieldType.TEXT,
    val savedType: CustomFieldType? = null,
    val typeLocked: Boolean = false,
    val nameProblem: ExtraNameProblem? = null,
    val saving: Boolean = false,
    val saveFailed: SaveFailure? = null,
)

/** Lo que la persona hace en la hoja de un campo o de una etiqueta. */
sealed interface ExtraEditorAction {
    data class Name(val text: String) : ExtraEditorAction

    data class Type(val type: CustomFieldType) : ExtraEditorAction

    data object Save : ExtraEditorAction

    data object Dismiss : ExtraEditorAction
}

/**
 * La hoja de crear o editar un campo propio o una etiqueta.
 *
 * Los errores aparecen al guardar, nunca mientras se escribe, y lo escrito no se pierde por un
 * error. El nombre no puede repetir el de otro campo vigente, o el de otra etiqueta, sin
 * mayúsculas ni tildes. Guardar corre una sola vez a la vez.
 *
 * @param activeFields los campos vigentes en este momento.
 * @param tags las etiquetas en este momento.
 * @param onSaved recibe qué se guardó, con la hoja ya cerrada.
 */
class ExtraEditorModel(
    private val scope: CoroutineScope,
    private val activeFields: () -> List<CustomField>,
    private val tags: () -> List<ProductTag>,
    private val saveField: suspend (Long?, CustomFieldDraft) -> SaveExtraResult,
    private val saveTag: suspend (Long?, String) -> SaveExtraResult,
    private val onSaved: (ExtraKind) -> Unit,
) {
    private val state = MutableStateFlow<ExtraEditor?>(null)
    val editor: StateFlow<ExtraEditor?> = state.asStateFlow()

    /** Abre la hoja vacía de un campo o de una etiqueta nuevos. */
    fun openNew(kind: ExtraKind) {
        state.value = ExtraEditor(kind = kind, editingId = null, name = "")
    }

    /**
     * Abre la hoja con [field] para editarlo. Un campo de un tipo que esta versión no conoce no se
     * edita: escribirle otro tipo cambiaría cómo se leen sus valores.
     */
    fun openField(field: CustomField) {
        val type = field.type ?: return
        state.value =
            ExtraEditor(
                kind = ExtraKind.FIELD,
                editingId = field.id,
                name = field.name,
                type = type,
                savedType = type,
                typeLocked = field.productCount > 0,
            )
    }

    /** Abre la hoja con [tag] para cambiarle el nombre. */
    fun openTag(tag: ProductTag) {
        state.value = ExtraEditor(kind = ExtraKind.TAG, editingId = tag.id, name = tag.name)
    }

    fun onAction(action: ExtraEditorAction) {
        when (action) {
            is ExtraEditorAction.Name ->
                change { copy(name = action.text, nameProblem = null, saveFailed = null) }

            is ExtraEditorAction.Type ->
                change { if (typeLocked) this else copy(type = action.type) }

            ExtraEditorAction.Save -> save()

            ExtraEditorAction.Dismiss -> state.value = null
        }
    }

    private fun change(block: ExtraEditor.() -> ExtraEditor) {
        state.update { it?.block() }
    }

    private fun save() {
        val current = state.value ?: return
        if (current.saving) return
        val problem = nameProblem(current)
        if (problem != null) {
            change { copy(nameProblem = problem) }
            return
        }
        change { copy(saving = true, saveFailed = null) }
        scope.launch {
            val result =
                when (current.kind) {
                    ExtraKind.FIELD ->
                        saveField(current.editingId, CustomFieldDraft(current.name, current.type))

                    ExtraKind.TAG -> saveTag(current.editingId, current.name)
                }
            if (result is SaveExtraResult.Saved) {
                state.value = null
                onSaved(current.kind)
            } else {
                change { copy(saving = false).withProblem(result) }
            }
        }
    }

    /** El problema del nombre que se ve sin preguntar a la base: vacío, largo o ya usado. */
    private fun nameProblem(editor: ExtraEditor): ExtraNameProblem? {
        val check = CustomFieldRules.name(editor.name)
        val valid =
            when (check) {
                is ExtraNameCheck.Invalid -> return problemOf(check.issue)
                is ExtraNameCheck.Valid -> check
            }
        val taken =
            when (editor.kind) {
                ExtraKind.FIELD ->
                    activeFields().any {
                        it.id != editor.editingId && normalized(it.name) == valid.normalizedName
                    }

                ExtraKind.TAG ->
                    tags().any {
                        it.id != editor.editingId && normalized(it.name) == valid.normalizedName
                    }
            }
        return if (taken) ExtraNameProblem.DUPLICATE else null
    }

    private fun normalized(name: String): String? =
        (CustomFieldRules.name(name) as? ExtraNameCheck.Valid)?.normalizedName

    private fun ExtraEditor.withProblem(result: SaveExtraResult): ExtraEditor = when (result) {
        is SaveExtraResult.Saved -> this

        is SaveExtraResult.Invalid -> copy(nameProblem = problemOf(result.issue))

        SaveExtraResult.DuplicateName -> copy(nameProblem = ExtraNameProblem.DUPLICATE)

        // Alguien le puso un valor mientras se editaba: el tipo queda como estaba, bloqueado.
        SaveExtraResult.TypeLocked -> copy(type = savedType ?: type, typeLocked = true)

        // Se archivó o se eliminó mientras se editaba: no se guardó nada, y lo escrito sigue.
        SaveExtraResult.NotFound -> copy(saveFailed = SaveFailure(outOfSpace = false))

        is SaveExtraResult.StorageFailed ->
            copy(saveFailed = SaveFailure(result.failure.isOutOfSpace))
    }

    private fun problemOf(issue: ExtraNameIssue): ExtraNameProblem = when (issue) {
        ExtraNameIssue.EMPTY -> ExtraNameProblem.EMPTY
        ExtraNameIssue.TOO_LONG -> ExtraNameProblem.TOO_LONG
    }

    companion object {
        /** Largo máximo del nombre, para el mensaje de error. */
        const val NAME_MAX: Int = CustomFieldRules.NAME_MAX
    }
}

package io.github.yesidrobayo.shopledger.ui.supplier

import androidx.compose.runtime.Immutable
import io.github.yesidrobayo.shopledger.domain.model.catalog.Supplier
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierForm
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierFormIssue
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.SaveSupplierResult
import io.github.yesidrobayo.shopledger.ui.unit.SaveFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * La hoja de un proveedor mientras se escribe.
 *
 * [editingId] nulo es un proveedor nuevo. [paymentTermsDays] no se escribe en la hoja: se conserva
 * el que tenía para no perderlo al editar. [issues] y [duplicateName] son los problemas del último
 * intento de guardar, que se borran del campo que la persona vuelve a tocar; [saveFailed] dice si
 * falló la base, y con qué. Lo escrito siempre se conserva.
 */
@Immutable
data class SupplierEditor(
    val editingId: Long?,
    val name: String,
    val phone: String = "",
    val email: String = "",
    val notes: String = "",
    val paymentTermsDays: Int? = null,
    val issues: Set<SupplierFormIssue> = emptySet(),
    val duplicateName: String? = null,
    val saving: Boolean = false,
    val saveFailed: SaveFailure? = null,
) {
    /** Lo escrito, como lo valida el dominio. */
    val form: SupplierForm get() = SupplierForm(name, phone, email, notes, paymentTermsDays)
}

/** Lo que la persona hace en la hoja de un proveedor. */
sealed interface SupplierEditorAction {
    data class Name(val text: String) : SupplierEditorAction

    data class Phone(val text: String) : SupplierEditorAction

    data class Email(val text: String) : SupplierEditorAction

    data class Notes(val text: String) : SupplierEditorAction

    data object Save : SupplierEditorAction

    data object Dismiss : SupplierEditorAction
}

/**
 * La hoja de crear o editar un proveedor, para la lista y para el detalle.
 *
 * Los errores aparecen al guardar, nunca mientras se escribe, y lo escrito no se pierde por un error.
 * Guardar corre una sola vez a la vez.
 *
 * @param onSaved recibe el id del proveedor guardado, con la hoja ya cerrada.
 */
class SupplierEditorModel(
    private val scope: CoroutineScope,
    private val save: suspend (Long?, SupplierForm) -> SaveSupplierResult,
    private val onSaved: (Long) -> Unit,
) {
    private val state = MutableStateFlow<SupplierEditor?>(null)
    val editor: StateFlow<SupplierEditor?> = state.asStateFlow()

    /** Abre la hoja vacía, con [name] ya escrito si viene de una búsqueda sin resultados. */
    fun openNew(name: String = "") {
        state.value = SupplierEditor(editingId = null, name = name)
    }

    /** Abre la hoja con los datos de [supplier] para editarlos. */
    fun openEdit(supplier: Supplier) {
        val details = supplier.details
        state.value =
            SupplierEditor(
                editingId = supplier.id,
                name = details.name,
                phone = details.phone.orEmpty(),
                email = details.email.orEmpty(),
                notes = details.notes.orEmpty(),
                paymentTermsDays = details.paymentTermsDays,
            )
    }

    fun onAction(action: SupplierEditorAction) {
        when (action) {
            is SupplierEditorAction.Name ->
                change {
                    copy(
                        name = action.text,
                        issues = issues - NAME_ISSUES,
                        duplicateName = null,
                        saveFailed = null,
                    )
                }

            is SupplierEditorAction.Phone ->
                change {
                    copy(phone = action.text, issues = issues - PHONE_ISSUES, saveFailed = null)
                }

            is SupplierEditorAction.Email ->
                change {
                    copy(email = action.text, issues = issues - EMAIL_ISSUES, saveFailed = null)
                }

            is SupplierEditorAction.Notes ->
                change {
                    copy(
                        notes = action.text,
                        issues = issues - SupplierFormIssue.NOTES_TOO_LONG,
                        saveFailed = null,
                    )
                }

            SupplierEditorAction.Save -> save()

            SupplierEditorAction.Dismiss -> state.value = null
        }
    }

    private fun change(block: SupplierEditor.() -> SupplierEditor) {
        state.update { it?.block() }
    }

    private fun save() {
        val current = state.value ?: return
        if (current.saving) return
        change { copy(saving = true, saveFailed = null) }
        scope.launch {
            when (val result = save(current.editingId, current.form)) {
                is SaveSupplierResult.Saved -> {
                    state.value = null
                    onSaved(result.supplierId)
                }

                is SaveSupplierResult.Invalid ->
                    change { copy(saving = false, issues = result.issues) }

                is SaveSupplierResult.DuplicateName ->
                    change { copy(saving = false, duplicateName = result.existingName) }

                // Se eliminó mientras se editaba: no se guardó nada, y lo escrito sigue.
                SaveSupplierResult.NotFound ->
                    change { copy(saving = false, saveFailed = SaveFailure(outOfSpace = false)) }

                is SaveSupplierResult.StorageFailed ->
                    change {
                        copy(saving = false, saveFailed = SaveFailure(result.failure.isOutOfSpace))
                    }
            }
        }
    }

    private companion object {
        val NAME_ISSUES = setOf(SupplierFormIssue.NAME_EMPTY, SupplierFormIssue.NAME_TOO_LONG)
        val PHONE_ISSUES = setOf(SupplierFormIssue.PHONE_TOO_LONG, SupplierFormIssue.PHONE_FORMAT)
        val EMAIL_ISSUES = setOf(SupplierFormIssue.EMAIL_TOO_LONG, SupplierFormIssue.EMAIL_FORMAT)
    }
}

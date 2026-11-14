package io.github.yesidrobayo.shopledger.ui.extras

import androidx.compose.runtime.Immutable
import io.github.yesidrobayo.shopledger.domain.model.catalog.CustomField
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductTag

/** Los dos segmentos de la pantalla. */
enum class CustomFieldsTab {
    FIELDS,
    TAGS,
}

/** Lo que se confirma antes de hacerlo. [name] es el del campo o la etiqueta, para el título. */
sealed interface CustomFieldsDialog {
    val name: String

    /** Archivar un campo: deja de pedirse, y sus valores se conservan. */
    data class ArchiveField(val fieldId: Long, override val name: String) : CustomFieldsDialog

    /** Eliminar un campo sin valores: no deja historia. */
    data class DeleteField(val fieldId: Long, override val name: String) : CustomFieldsDialog

    /** Eliminar una etiqueta: [productCount] productos la pierden. */
    data class DeleteTag(val tagId: Long, override val name: String, val productCount: Int) :
        CustomFieldsDialog
}

/** Un aviso breve, abajo, tras una acción. */
sealed interface CustomFieldsMessage {
    data object FieldSaved : CustomFieldsMessage

    data object TagSaved : CustomFieldsMessage

    /** Reactivar no se pudo: un campo vigente ya usa su nombre. */
    data object NameTaken : CustomFieldsMessage

    data class Failed(val outOfSpace: Boolean) : CustomFieldsMessage
}

/**
 * El estado de la pantalla de campos propios y etiquetas.
 *
 * [loading] hasta que llega la primera lectura, y [readFailed] si una lectura falló: se dice, con
 * `Reintentar`. [fields] son los campos vigentes en el orden que eligió la persona, y [archived]
 * los archivados, que van al final. [tags] van por nombre. Las hojas abiertas llegan aparte, con
 * su propio estado.
 */
@Immutable
data class CustomFieldsUiState(
    val loading: Boolean = true,
    val readFailed: Boolean = false,
    val tab: CustomFieldsTab = CustomFieldsTab.FIELDS,
    val fields: List<CustomField> = emptyList(),
    val archived: List<CustomField> = emptyList(),
    val tags: List<ProductTag> = emptyList(),
    val dialog: CustomFieldsDialog? = null,
    val message: CustomFieldsMessage? = null,
)

/** Lo que la persona hace en la pantalla, fuera de las hojas. */
sealed interface CustomFieldsAction {
    data object Back : CustomFieldsAction

    /** Vuelve a leer tras una falla de lectura. */
    data object Retry : CustomFieldsAction

    data class SelectTab(val tab: CustomFieldsTab) : CustomFieldsAction

    data object NewField : CustomFieldsAction

    data class EditField(val fieldId: Long) : CustomFieldsAction

    /** Sube o baja un campo vigente un lugar: [up] hacia el principio. */
    data class MoveField(val fieldId: Long, val up: Boolean) : CustomFieldsAction

    data class AskArchiveField(val fieldId: Long) : CustomFieldsAction

    data class AskDeleteField(val fieldId: Long) : CustomFieldsAction

    data class ReactivateField(val fieldId: Long) : CustomFieldsAction

    data object NewTag : CustomFieldsAction

    data class EditTag(val tagId: Long) : CustomFieldsAction

    data class AskDeleteTag(val tagId: Long) : CustomFieldsAction

    data object ConfirmDialog : CustomFieldsAction

    data object DismissDialog : CustomFieldsAction

    data object MessageShown : CustomFieldsAction
}

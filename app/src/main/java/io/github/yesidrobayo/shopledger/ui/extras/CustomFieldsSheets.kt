package io.github.yesidrobayo.shopledger.ui.extras

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.text.input.ImeAction
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.catalog.CustomFieldType
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppTextField
import io.github.yesidrobayo.shopledger.ui.components.CompactChipRow
import io.github.yesidrobayo.shopledger.ui.components.ConfirmDialog
import io.github.yesidrobayo.shopledger.ui.components.ConfirmVariant
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.saveErrorText
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.unit.EditorContainer
import io.github.yesidrobayo.shopledger.ui.unit.SaveFailure

/** El nombre de un tipo de campo, de la redacción. */
@StringRes
internal fun fieldTypeLabel(type: CustomFieldType): Int = when (type) {
    CustomFieldType.TEXT -> R.string.field_type_text
    CustomFieldType.NUMBER -> R.string.field_type_number
    CustomFieldType.DATE -> R.string.field_type_date
    CustomFieldType.BOOLEAN -> R.string.field_type_boolean
}

/**
 * La hoja de un campo propio o de una etiqueta: `Nombre`, de hasta 40 caracteres, y en un campo
 * su `Tipo`. Con valores, el tipo se muestra solo para leer, con su razón. Los errores van en su
 * campo, y un guardado fallido se avisa arriba del botón sin borrar nada.
 *
 * Con [asPanel] va al lado de las listas, en una ventana `Expanded`; si no, como hoja inferior.
 */
@Composable
fun ExtraEditorSheet(
    editor: ExtraEditor,
    onAction: (ExtraEditorAction) -> Unit,
    asPanel: Boolean = false,
) {
    val field = editor.kind == ExtraKind.FIELD
    val isNew = editor.editingId == null
    val title =
        when (editor.kind) {
            ExtraKind.FIELD -> if (isNew) R.string.field_new_action else R.string.field_edit_title
            ExtraKind.TAG -> if (isNew) R.string.tag_new_action else R.string.tag_edit_title
        }
    val saveText = if (field) R.string.field_save_action else R.string.tag_save_action
    val nameLabel = if (field) R.string.field_name_label else R.string.tag_name_label
    EditorContainer(
        asPanel = asPanel,
        title = stringResource(title),
        onDismiss = { onAction(ExtraEditorAction.Dismiss) },
        action = {
            SaveAction(
                text = stringResource(saveText),
                saving = editor.saving,
                failure = editor.saveFailed,
            ) { onAction(ExtraEditorAction.Save) }
        },
    ) {
        AppTextField(
            value = editor.name,
            onValueChange = { onAction(ExtraEditorAction.Name(it)) },
            label = stringResource(nameLabel),
            error = nameError(editor),
            imeAction = ImeAction.Done,
        )
        if (field) TypeChoice(editor, onAction)
    }
}

/** El tipo de un campo: chips mientras no tenga valores; con valores, para leer y con su razón. */
@Composable
private fun TypeChoice(editor: ExtraEditor, onAction: (ExtraEditorAction) -> Unit) {
    val spacing = AppTheme.spacing
    val label = stringResource(R.string.field_type_label)
    if (editor.typeLocked) {
        val type = stringResource(fieldTypeLabel(editor.type))
        val reason = stringResource(R.string.field_type_locked)
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = spacing.s8)
                .clearAndSetSemantics {
                    contentDescription = listOf(label, type, reason).joinToString()
                    disabled()
                },
            verticalArrangement = Arrangement.spacedBy(spacing.s2),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                type,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    Text(
        label,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = spacing.s8),
    )
    CompactChipRow(
        options = CustomFieldType.entries,
        label = { stringResource(fieldTypeLabel(it)) },
        selected = setOf(editor.type),
        onToggle = { onAction(ExtraEditorAction.Type(it)) },
        multiple = false,
    )
}

@Composable
private fun nameError(editor: ExtraEditor): String? {
    val field = editor.kind == ExtraKind.FIELD
    return when (editor.nameProblem) {
        null -> null

        ExtraNameProblem.EMPTY ->
            stringResource(
                if (field) R.string.field_error_name_empty else R.string.tag_error_name_empty,
            )

        ExtraNameProblem.DUPLICATE ->
            stringResource(
                if (field) {
                    R.string.field_error_name_repeated
                } else {
                    R.string.tag_error_name_repeated
                },
            )

        ExtraNameProblem.TOO_LONG ->
            pluralStringResource(
                R.plurals.error_text_long,
                ExtraEditorModel.NAME_MAX,
                ExtraEditorModel.NAME_MAX,
            )
    }
}

/** El aviso de un guardado fallido y el botón principal, al pie de la hoja. */
@Composable
private fun SaveAction(text: String, saving: Boolean, failure: SaveFailure?, onSave: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12)) {
        failure?.let {
            NoticeCard(
                text = stringResource(saveErrorText(it.outOfSpace)),
                variant = NoticeVariant.ERROR,
                icon = R.drawable.ic_error,
            )
        }
        PrimaryActionButton(
            text = text,
            onClick = onSave,
            variant = ActionVariant.PRIMARY,
            modifier = Modifier.fillMaxWidth(),
            state = if (saving) ActionState.WORKING else ActionState.IDLE,
        )
    }
}

/** La confirmación de archivar o eliminar un campo, o de eliminar una etiqueta. */
@Composable
internal fun CustomFieldsDialogView(
    dialog: CustomFieldsDialog,
    onAction: (CustomFieldsAction) -> Unit,
) {
    val confirm = { onAction(CustomFieldsAction.ConfirmDialog) }
    val dismiss = { onAction(CustomFieldsAction.DismissDialog) }
    when (dialog) {
        is CustomFieldsDialog.ArchiveField ->
            ConfirmDialog(
                title = stringResource(R.string.field_archive_confirm_title, dialog.name),
                body = stringResource(R.string.field_archive_confirm_body),
                confirmLabel = stringResource(R.string.field_archive_confirm_action),
                onConfirm = confirm,
                onDismiss = dismiss,
            )

        is CustomFieldsDialog.DeleteField ->
            ConfirmDialog(
                title = stringResource(R.string.field_delete_confirm_title, dialog.name),
                body = stringResource(R.string.field_delete_confirm_body),
                confirmLabel = stringResource(R.string.field_delete_confirm_action),
                onConfirm = confirm,
                onDismiss = dismiss,
                variant = ConfirmVariant.DESTRUCTIVE,
            )

        is CustomFieldsDialog.DeleteTag ->
            ConfirmDialog(
                title = stringResource(R.string.tag_delete_confirm_title, dialog.name),
                body =
                    pluralStringResource(
                        R.plurals.tag_delete_confirm_body,
                        dialog.productCount,
                        dialog.productCount,
                    ),
                confirmLabel = stringResource(R.string.tag_delete_confirm_action),
                onConfirm = confirm,
                onDismiss = dismiss,
                variant = ConfirmVariant.DESTRUCTIVE,
            )
    }
}

package io.github.yesidrobayo.shopledger.ui.supplier

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierFormIssue
import io.github.yesidrobayo.shopledger.domain.rule.catalog.SupplierFormRules
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppTextField
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.WholeNumberField
import io.github.yesidrobayo.shopledger.ui.components.saveErrorText
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews
import io.github.yesidrobayo.shopledger.ui.unit.EditorContainer
import io.github.yesidrobayo.shopledger.ui.unit.SaveFailure

/**
 * La hoja de un proveedor: `Nombre`, `Teléfono`, `Correo`, `Notas` y `Plazo de pago`, en días, todo
 * opcional salvo el nombre. El plazo propone el vencimiento de las compras a plazo.
 * Invita a anotar solo lo necesario y a no escribir datos sensibles en las notas. Los errores van en
 * su campo, y un guardado fallido se avisa arriba del botón sin borrar nada.
 *
 * @param asPanel en una ventana `Expanded`, la hoja va como panel al lado de la lista.
 */
@Composable
fun SupplierEditorSheet(
    editor: SupplierEditor,
    onAction: (SupplierEditorAction) -> Unit,
    asPanel: Boolean = false,
) {
    val title =
        if (editor.editingId == null) R.string.supplier_new_title else R.string.supplier_edit_title
    EditorContainer(
        asPanel = asPanel,
        title = stringResource(title),
        onDismiss = { onAction(SupplierEditorAction.Dismiss) },
        action = {
            SaveAction(editor.saving, editor.saveFailed) { onAction(SupplierEditorAction.Save) }
        },
    ) {
        AppTextField(
            value = editor.name,
            onValueChange = { onAction(SupplierEditorAction.Name(it)) },
            label = stringResource(R.string.form_name),
            error = nameError(editor),
            capitalization = KeyboardCapitalization.Words,
        )
        AppTextField(
            value = editor.phone,
            onValueChange = { onAction(SupplierEditorAction.Phone(it)) },
            label = stringResource(R.string.form_phone),
            error =
                when {
                    SupplierFormIssue.PHONE_FORMAT in editor.issues ->
                        stringResource(R.string.error_phone_format)

                    SupplierFormIssue.PHONE_TOO_LONG in editor.issues ->
                        tooLong(SupplierFormRules.PHONE_MAX)

                    else -> null
                },
            keyboardType = KeyboardType.Phone,
            autoCorrect = false,
        )
        AppTextField(
            value = editor.email,
            onValueChange = { onAction(SupplierEditorAction.Email(it)) },
            label = stringResource(R.string.form_email),
            error =
                when {
                    SupplierFormIssue.EMAIL_FORMAT in editor.issues ->
                        stringResource(R.string.error_email_format)

                    SupplierFormIssue.EMAIL_TOO_LONG in editor.issues ->
                        tooLong(SupplierFormRules.EMAIL_MAX)

                    else -> null
                },
            keyboardType = KeyboardType.Email,
            capitalization = KeyboardCapitalization.None,
            autoCorrect = false,
        )
        AppTextField(
            value = editor.notes,
            onValueChange = { onAction(SupplierEditorAction.Notes(it)) },
            label = stringResource(R.string.form_notes),
            error =
                if (SupplierFormIssue.NOTES_TOO_LONG in editor.issues) {
                    tooLong(SupplierFormRules.NOTES_MAX)
                } else {
                    null
                },
            supportingText = stringResource(R.string.supplier_notes_help),
            singleLine = false,
            imeAction = ImeAction.Default,
        )
        WholeNumberField(
            value = editor.paymentTermsText,
            onValueChange = { onAction(SupplierEditorAction.PaymentTerms(it)) },
            label = stringResource(R.string.supplier_payment_terms),
            suffix = stringResource(R.string.supplier_payment_terms_days),
            error = stringResource(R.string.error_value_high)
                .takeIf { SupplierFormIssue.PAYMENT_TERMS_OUT_OF_RANGE in editor.issues },
            imeAction = ImeAction.Done,
        )
        Text(
            stringResource(R.string.supplier_payment_terms_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun nameError(editor: SupplierEditor): String? = when {
    editor.duplicateName != null ->
        stringResource(R.string.supplier_error_name_duplicate, editor.duplicateName)

    SupplierFormIssue.NAME_EMPTY in editor.issues ->
        stringResource(R.string.supplier_error_name_empty)

    SupplierFormIssue.NAME_TOO_LONG in editor.issues -> tooLong(SupplierFormRules.NAME_MAX)

    else -> null
}

@Composable
private fun tooLong(max: Int): String = pluralStringResource(R.plurals.error_text_long, max, max)

/** El aviso de un guardado fallido y el botón principal, al pie de la hoja. */
@Composable
private fun SaveAction(saving: Boolean, failure: SaveFailure?, onSave: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12)) {
        failure?.let {
            NoticeCard(
                text = stringResource(saveErrorText(it.outOfSpace)),
                variant = NoticeVariant.ERROR,
                icon = R.drawable.ic_error,
            )
        }
        PrimaryActionButton(
            text = stringResource(R.string.supplier_save),
            onClick = onSave,
            variant = ActionVariant.PRIMARY,
            modifier = Modifier.fillMaxWidth(),
            state = if (saving) ActionState.WORKING else ActionState.IDLE,
        )
    }
}

@ThemePreviews
@Composable
private fun SupplierEditorPreview() {
    PreviewTheme {
        SupplierEditorSheet(
            SupplierEditor(
                editingId = 1L,
                name = SupplierSamples.LONG_NAME,
                phone = "555-CALL",
                email = "compras",
                notes = SupplierSamples.NOTES,
                issues = setOf(SupplierFormIssue.PHONE_FORMAT, SupplierFormIssue.EMAIL_FORMAT),
                saveFailed = SaveFailure(outOfSpace = true),
            ),
            onAction = {},
            asPanel = true,
        )
    }
}

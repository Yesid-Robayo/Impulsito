package io.github.yesidrobayo.shopledger.ui.customer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerFormIssue
import io.github.yesidrobayo.shopledger.domain.rule.customer.CustomerFormRules
import io.github.yesidrobayo.shopledger.localization.SeedText
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppTextField
import io.github.yesidrobayo.shopledger.ui.components.CompactChipRow
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.saveErrorText
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews
import io.github.yesidrobayo.shopledger.ui.unit.EditorContainer
import io.github.yesidrobayo.shopledger.ui.unit.SaveFailure

/**
 * La hoja de un cliente: `Elegir de contactos`, `Nombre`, y `Teléfono`, `Correo` y `Notas`, todo
 * opcional salvo el nombre, con la invitación a anotar solo lo necesario. No pide documento de
 * identidad. El selector de contactos es el del sistema, sin permiso de contactos: trae solo el nombre
 * y el teléfono del contacto elegido.
 *
 * Los errores van en su campo. Un nombre repetido se avisa sin bloquear, y un guardado fallido se
 * avisa arriba del botón sin borrar nada.
 *
 * @param asPanel en una ventana `Expanded`, la hoja va como panel al lado de la lista.
 */
@Composable
fun CustomerEditorSheet(
    editor: CustomerEditor,
    onAction: (CustomerEditorAction) -> Unit,
    asPanel: Boolean = false,
) {
    val title =
        if (editor.editingId == null) R.string.customer_new_action else R.string.customer_edit_title
    EditorContainer(
        asPanel = asPanel,
        title = stringResource(title),
        onDismiss = { onAction(CustomerEditorAction.Dismiss) },
        action = { SaveAction(editor) { onAction(CustomerEditorAction.Save) } },
    ) {
        PrimaryActionButton(
            text = stringResource(R.string.customer_pick_contact_action),
            onClick = { onAction(CustomerEditorAction.PickContact) },
            variant = ActionVariant.OUTLINED,
            modifier = Modifier.fillMaxWidth(),
        )
        if (editor.noContactsApp) {
            NoticeCard(
                text = stringResource(R.string.customer_no_contacts_app),
                variant = NoticeVariant.INFO,
                icon = R.drawable.ic_contacts,
            )
        }
        AppTextField(
            value = editor.name,
            onValueChange = { onAction(CustomerEditorAction.Name(it)) },
            label = stringResource(R.string.customer_name),
            error = nameError(editor),
            capitalization = KeyboardCapitalization.Words,
        )
        Text(
            stringResource(R.string.customer_minimal_data_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AppTextField(
            value = editor.phone,
            onValueChange = { onAction(CustomerEditorAction.Phone(it)) },
            label = stringResource(R.string.customer_phone),
            error = when {
                CustomerFormIssue.PHONE_FORMAT in editor.issues ->
                    stringResource(R.string.error_phone_format)

                CustomerFormIssue.PHONE_TOO_LONG in editor.issues ->
                    tooLong(CustomerFormRules.PHONE_MAX)

                else -> null
            },
            keyboardType = KeyboardType.Phone,
            autoCorrect = false,
        )
        AppTextField(
            value = editor.email,
            onValueChange = { onAction(CustomerEditorAction.Email(it)) },
            label = stringResource(R.string.customer_email),
            error = when {
                CustomerFormIssue.EMAIL_FORMAT in editor.issues ->
                    stringResource(R.string.error_email_format)

                CustomerFormIssue.EMAIL_TOO_LONG in editor.issues ->
                    tooLong(CustomerFormRules.EMAIL_MAX)

                else -> null
            },
            keyboardType = KeyboardType.Email,
            capitalization = KeyboardCapitalization.None,
            autoCorrect = false,
        )
        if (editor.priceLists.isNotEmpty()) {
            val resources = LocalResources.current
            Text(
                stringResource(R.string.customer_price_list),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val selected = editor.priceLists.filter {
                it.id == editor.priceListId || (editor.priceListId == null && it.isDefault)
            }
            CompactChipRow(
                options = editor.priceLists,
                label = {
                    SeedText.displayName(resources, SeedTable.PRICE_LISTS, it.systemKey, it.name)
                },
                selected = selected.toSet(),
                onToggle = { onAction(CustomerEditorAction.ChoosePriceList(it.id)) },
                multiple = false,
            )
        }
        AppTextField(
            value = editor.notes,
            onValueChange = { onAction(CustomerEditorAction.Notes(it)) },
            label = stringResource(R.string.customer_notes),
            error =
                if (CustomerFormIssue.NOTES_TOO_LONG in editor.issues) {
                    tooLong(CustomerFormRules.NOTES_MAX)
                } else {
                    null
                },
            supportingText = stringResource(R.string.customer_notes_help),
            singleLine = false,
            imeAction = ImeAction.Default,
        )
    }
}

@Composable
private fun nameError(editor: CustomerEditor): String? = when {
    CustomerFormIssue.NAME_EMPTY in editor.issues ->
        stringResource(R.string.customer_error_name_empty)

    CustomerFormIssue.NAME_TOO_LONG in editor.issues ->
        stringResource(R.string.customer_error_name_long)

    else -> null
}

@Composable
private fun tooLong(max: Int): String = pluralStringResource(R.plurals.error_text_long, max, max)

/**
 * El aviso de nombre repetido, el de un guardado fallido y el botón principal, al pie de la hoja.
 * El aviso de nombre repetido no bloquea: el botón guarda igual.
 */
@Composable
private fun SaveAction(editor: CustomerEditor, onSave: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12)) {
        if (editor.sameNameWarned) {
            NoticeCard(
                text = stringResource(R.string.customer_duplicate_warning),
                variant = NoticeVariant.WARNING,
                icon = R.drawable.ic_error,
            )
        }
        editor.saveFailed?.let {
            NoticeCard(
                text = stringResource(saveErrorText(it.outOfSpace)),
                variant = NoticeVariant.ERROR,
                icon = R.drawable.ic_error,
            )
        }
        PrimaryActionButton(
            text = stringResource(R.string.customer_save_action),
            onClick = onSave,
            variant = ActionVariant.PRIMARY,
            modifier = Modifier.fillMaxWidth(),
            state = if (editor.saving) ActionState.WORKING else ActionState.IDLE,
        )
    }
}

@ThemePreviews
@Composable
private fun CustomerEditorPreview() {
    PreviewTheme {
        CustomerEditorSheet(
            CustomerEditor(
                editingId = 1L,
                name = CustomerSamples.LONG_NAME,
                phone = "555-CALL",
                email = "compras",
                notes = CustomerSamples.NOTES,
                issues = setOf(CustomerFormIssue.PHONE_FORMAT, CustomerFormIssue.EMAIL_FORMAT),
                sameNameWarned = true,
                noContactsApp = true,
                saveFailed = SaveFailure(outOfSpace = true),
            ),
            onAction = {},
            asPanel = true,
        )
    }
}

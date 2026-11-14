package io.github.yesidrobayo.shopledger.ui.product

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.catalog.CustomField
import io.github.yesidrobayo.shopledger.domain.model.catalog.CustomFieldType
import io.github.yesidrobayo.shopledger.domain.model.catalog.CustomValueIssue
import io.github.yesidrobayo.shopledger.domain.rule.catalog.CustomFieldRules
import io.github.yesidrobayo.shopledger.ui.components.AppTextField
import io.github.yesidrobayo.shopledger.ui.components.CompactChipRow
import io.github.yesidrobayo.shopledger.ui.components.DateField
import io.github.yesidrobayo.shopledger.ui.components.DecimalField
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.SettingsRow
import io.github.yesidrobayo.shopledger.ui.components.SettingsRowAction
import io.github.yesidrobayo.shopledger.ui.components.saveErrorText
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * Los campos propios y las etiquetas dentro de `Más detalles`: un campo por definición vigente,
 * según su tipo, las etiquetas como chips de selección múltiple, y los accesos para
 * administrarlos. Solo aparece si hay algún campo o alguna etiqueta.
 *
 * Un número se escribe con los separadores de la región; una fecha se elige con su calendario, y
 * se puede quitar; el sí o no es un interruptor. Un campo de un tipo que esta versión no conoce se
 * muestra solo para leer.
 */
@Composable
internal fun ProductExtrasSection(
    extras: ProductExtrasForm,
    onAction: (ProductEditAction) -> Unit,
) {
    val spacing = AppTheme.spacing
    Text(
        stringResource(R.string.field_title),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = spacing.s16).semantics { heading() },
    )
    for (field in extras.fields) {
        key(field.id) {
            ExtraValueField(
                field = field,
                value = extras.values[field.id].orEmpty(),
                error = extras.errors[field.id]?.let { valueError(it) },
                calendar = extras.calendar,
                onChange = { onAction(ProductEditAction.ExtraValueChange(field.id, it)) },
            )
        }
    }
    if (extras.tags.isNotEmpty()) {
        Text(
            stringResource(R.string.tag_tab_tags),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = spacing.s8),
        )
        CompactChipRow(
            options = extras.tags,
            label = { it.name },
            selected = extras.tags.filterTo(mutableSetOf()) { it.id in extras.tagIds },
            onToggle = { onAction(ProductEditAction.ExtraTagToggle(it.id)) },
            multiple = true,
        )
    }
    if (extras.fields.isNotEmpty()) {
        InlineTextButton(
            stringResource(R.string.field_manage_action),
            { onAction(ProductEditAction.ManageExtras) },
        )
    }
    if (extras.tags.isNotEmpty()) {
        InlineTextButton(
            stringResource(R.string.tag_manage_action),
            { onAction(ProductEditAction.ManageExtras) },
        )
    }
}

/** El campo de [field] según su tipo, con [value] tal como se ve. */
@Composable
private fun ExtraValueField(
    field: CustomField,
    value: String,
    error: String?,
    calendar: FieldCalendar?,
    onChange: (String) -> Unit,
) {
    when (field.type) {
        CustomFieldType.TEXT ->
            AppTextField(
                value = value,
                onValueChange = onChange,
                label = field.name,
                error = error,
            )

        CustomFieldType.NUMBER ->
            DecimalField(
                value = value,
                onValueChange = onChange,
                label = field.name,
                error = error,
            )

        CustomFieldType.DATE -> DateValueField(field, value, error, calendar, onChange)

        CustomFieldType.BOOLEAN ->
            SettingsRow(
                title = field.name,
                action =
                    SettingsRowAction.Toggle(value == CustomFieldRules.TRUE) { on ->
                        onChange(if (on) CustomFieldRules.TRUE else CustomFieldRules.FALSE)
                    },
                warning = error,
            )

        // Esta versión no sabe cómo se escribe: se muestra para leer, sin tocarlo.
        null -> ReadOnlyValue(field.name, value)
    }
}

/**
 * Una fecha elegida con el calendario del negocio, sin tope: puede ser futura, como un
 * vencimiento. `Quitar` la deja sin valor. Sin calendario, se muestra para leer.
 */
@Composable
private fun DateValueField(
    field: CustomField,
    value: String,
    error: String?,
    calendar: FieldCalendar?,
    onChange: (String) -> Unit,
) {
    if (calendar == null) {
        ReadOnlyValue(field.name, value)
        return
    }
    Column(Modifier.fillMaxWidth()) {
        DateField(
            label = field.name,
            date = BusinessDate.parse(value),
            onDateChange = { onChange(it.toString()) },
            today = calendar.today,
            weekStartDay = calendar.weekStartDay,
            latest = null,
            error = error,
        )
        if (value.isNotBlank()) {
            InlineTextButton(stringResource(R.string.action_remove), { onChange("") })
        }
    }
}

/** El nombre y el valor guardado, para leer, en un solo foco. */
@Composable
private fun ReadOnlyValue(name: String, value: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = AppTheme.spacing.s4)
            .clearAndSetSemantics { contentDescription = listOf(name, value).joinToString() },
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s2),
    ) {
        Text(
            name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** El mensaje de un valor que no sirve para su campo. */
@Composable
private fun valueError(issue: CustomValueIssue): String = when (issue) {
    CustomValueIssue.TOO_LONG ->
        pluralStringResource(
            R.plurals.error_text_long,
            CustomFieldRules.VALUE_MAX,
            CustomFieldRules.VALUE_MAX,
        )

    CustomValueIssue.NOT_A_NUMBER -> stringResource(R.string.field_error_number)

    CustomValueIssue.NOT_A_DATE -> stringResource(R.string.field_error_date)

    // El interruptor no deja escribir otra cosa, y un tipo desconocido no se escribe: si llegan,
    // cambió algo por debajo y no se guardó nada.
    CustomValueIssue.NOT_A_BOOLEAN, CustomValueIssue.UNKNOWN_TYPE ->
        stringResource(saveErrorText(outOfSpace = false))
}

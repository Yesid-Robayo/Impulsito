package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * Un idioma del selector, con su nombre ya escrito en ese mismo idioma: así lo reconoce quien lo
 * habla, aunque la app esté en otro.
 *
 * @param tag la etiqueta del idioma, o `null` para seguir al teléfono.
 */
@Immutable
data class LanguageOption(val tag: String?, val name: String)

/**
 * El selector de idioma: primero `Igual que el teléfono`, con el idioma que tiene ahora el
 * teléfono debajo, y después cada idioma que trae la app. Es un grupo de opciones de una sola
 * elección: cada fila es un foco y TalkBack dice cuál está elegida. La marca de la elegida es un
 * icono, no solo un color.
 *
 * No conoce ningún idioma: recibe los que la app trae, así que agregar uno no lo toca. No lee la
 * base, así que sirve también en el rescate.
 *
 * @param phoneLanguage el idioma del teléfono, escrito en el de la interfaz.
 * @param selected la etiqueta elegida, o `null` si sigue al teléfono.
 */
@Composable
fun LanguagePicker(
    options: List<LanguageOption>,
    selected: String?,
    phoneLanguage: String,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.selectableGroup()) {
        LanguageRow(
            name = stringResource(R.string.language_system),
            summary = stringResource(R.string.language_current, phoneLanguage),
            selected = selected == null,
            onClick = { onSelect(null) },
        )
        options.forEach { option ->
            LanguageRow(
                name = option.name,
                summary = null,
                selected = option.tag == selected,
                onClick = { onSelect(option.tag) },
            )
        }
    }
}

@Composable
private fun LanguageRow(name: String, summary: String?, selected: Boolean, onClick: () -> Unit) {
    SettingsRow(
        title = name,
        action =
            SettingsRowAction.Value {
                if (selected) {
                    Icon(
                        painter = painterResource(R.drawable.ic_check),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(AppTheme.sizes.icon),
                    )
                }
            },
        summary = summary,
        modifier = Modifier.selectable(
            selected = selected,
            role = Role.RadioButton,
            onClick = onClick,
        ),
    )
}

@ThemePreviews
@Composable
private fun LanguagePickerPreview() {
    PreviewTheme {
        LanguagePicker(
            options =
                listOf(
                    LanguageOption("en", "English"),
                    LanguageOption("es", "Español"),
                    LanguageOption("pt", "Português"),
                    LanguageOption("xx", PreviewSamples.LONG_NAME),
                ),
            selected = "es",
            phoneLanguage = PreviewSamples.LONG_NAME,
            onSelect = {},
        )
    }
}

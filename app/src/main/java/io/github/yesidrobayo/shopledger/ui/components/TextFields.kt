package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.isSensitiveData
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * Un campo de texto libre, como un nombre, una nota o un símbolo: el mismo contorno, forma y colores
 * que [MoneyField], con el texto en `bodyLarge` porque no es una cifra.
 *
 * Guarda el texto tal como se escribió: no recorta ni bloquea un largo, para no perder lo que la
 * persona escribió o pegó. El largo y la forma los revisa quien lo usa, que pasa [error] al salir
 * del campo o al intentar guardar, nunca mientras se escribe. Para saber cuándo sale, recibe
 * [onLeave].
 *
 * @param supportingText una ayuda debajo, como el largo permitido. Con un error, se ve el error.
 * @param onLeave se llama cuando el campo pierde el foco después de haberlo tenido.
 * @param keyboardType el teclado, como `Uri` para una dirección.
 * @param autoCorrect falso para lo que no es texto corrido, como un usuario o una dirección.
 * @param trailingIcon una acción a la derecha del campo, como abrir el escáner.
 * @param keyboardActions lo que hace la tecla de acción, como usar el código que dejó un lector.
 */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    supportingText: String? = null,
    imeAction: ImeAction = ImeAction.Next,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences,
    singleLine: Boolean = true,
    onLeave: () -> Unit = {},
    keyboardType: KeyboardType = KeyboardType.Text,
    autoCorrect: Boolean = true,
    trailingIcon: (@Composable () -> Unit)? = null,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    var focused by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier =
            modifier
                .fillMaxWidth()
                .onFocusChanged {
                    if (focused && !it.isFocused) onLeave()
                    focused = it.isFocused
                },
        textStyle = MaterialTheme.typography.bodyLarge,
        label = { Text(label, style = MaterialTheme.typography.bodyLarge) },
        isError = error != null,
        supportingText =
            when {
                error != null -> {
                    { FieldError(error) }
                }

                supportingText != null -> {
                    {
                        Text(
                            supportingText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                else -> null
            },
        keyboardOptions =
            KeyboardOptions(
                capitalization = capitalization,
                autoCorrectEnabled = autoCorrect,
                keyboardType = keyboardType,
                imeAction = imeAction,
            ),
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        trailingIcon = trailingIcon,
        shape = MaterialTheme.shapes.medium,
        colors = appFieldColors(),
    )
}

/**
 * Un campo de contraseña: oculta por defecto, con `Mostrar contraseña` a la derecha, sin
 * autocorrección ni sugerencias y marcado como dato sensible, para que el sistema no lo muestre ni
 * lo guarde. Se puede pegar, para admitir los gestores de contraseñas, pero va dentro de
 * [PrivateInput]: ni con la contraseña a la vista se copia, y el teclado no la aprende.
 *
 * El texto solo vive en memoria de quien lo usa: nunca debe ir a un estado guardado.
 *
 * @param showLabel y [hideLabel] son los nombres del botón del ojo para TalkBack.
 */
@Composable
fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    visible: Boolean,
    onToggleVisible: () -> Unit,
    showLabel: String,
    hideLabel: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    imeAction: ImeAction = ImeAction.Done,
) = PrivateInput {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier =
            modifier
                .fillMaxWidth()
                .semantics {
                    contentType = ContentType.Password
                    isSensitiveData = true
                },
        textStyle = MaterialTheme.typography.bodyLarge,
        label = { Text(label, style = MaterialTheme.typography.bodyLarge) },
        isError = error != null,
        supportingText = error?.let { { FieldError(it) } },
        trailingIcon = {
            IconButton(onClick = onToggleVisible) {
                Icon(
                    painter =
                        painterResource(
                            if (visible) R.drawable.ic_visibility_off else R.drawable.ic_visibility,
                        ),
                    contentDescription = if (visible) hideLabel else showLabel,
                    modifier = Modifier.size(AppTheme.sizes.icon),
                )
            }
        },
        visualTransformation =
            if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions =
            KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Password,
                imeAction = imeAction,
            ),
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        colors = appFieldColors(),
    )
}

@ThemePreviews
@Composable
private fun TextFieldsPreview() {
    val s = PreviewSamples
    PreviewTheme {
        Column(
            Modifier.padding(AppTheme.spacing.s16),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
        ) {
            AppTextField(s.LONG_NAME, {}, stringResource(R.string.form_name))
            AppTextField("", {}, stringResource(R.string.form_name))
            AppTextField(
                s.SHORT_NAME,
                {},
                stringResource(R.string.form_name),
                error = stringResource(R.string.state_error_read),
            )
            PasswordField(
                value = s.SHORT_NAME,
                onValueChange = {},
                label = stringResource(R.string.webdav_field_password),
                visible = false,
                onToggleVisible = {},
                showLabel = stringResource(R.string.webdav_password_show_action),
                hideLabel = stringResource(R.string.webdav_password_hide_action),
            )
        }
    }
}

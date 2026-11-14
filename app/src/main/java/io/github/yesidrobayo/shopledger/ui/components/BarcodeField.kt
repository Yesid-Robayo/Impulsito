package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * El campo de un código de barras, con el escáner a la derecha, en 48 dp.
 *
 * Teclado de texto simple, sin mayúsculas automáticas ni autocorrección: un código no es una
 * palabra. Un lector externo escribe aquí como un teclado y termina con Enter, que llega a
 * [onSubmit]; así funciona sin permisos.
 *
 * @param onScan abre el escáner.
 * @param error el problema del código, que se muestra debajo.
 * @param supportingText una ayuda debajo, mientras no haya un error.
 */
@Composable
fun BarcodeField(
    value: String,
    onValueChange: (String) -> Unit,
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.scanner_barcode_label),
    error: String? = null,
    supportingText: String? = null,
    onSubmit: () -> Unit = {},
) {
    AppTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        modifier = modifier,
        error = error,
        supportingText = supportingText,
        imeAction = ImeAction.Done,
        capitalization = KeyboardCapitalization.None,
        keyboardType = KeyboardType.Ascii,
        autoCorrect = false,
        trailingIcon = {
            TopBarAction(
                R.drawable.ic_barcode_scanner,
                stringResource(R.string.a11y_scan_code),
                onScan,
            )
        },
        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
    )
}

@ThemePreviews
@Composable
private fun BarcodeFieldPreview() {
    PreviewTheme {
        Column(
            Modifier.padding(AppTheme.spacing.s16),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
        ) {
            BarcodeField(value = "", onValueChange = {}, onScan = {})
            BarcodeField(value = PreviewSamples.BARCODE, onValueChange = {}, onScan = {})
            BarcodeField(
                value = PreviewSamples.BARCODE,
                onValueChange = {},
                onScan = {},
                error = PreviewSamples.LONG_NAME,
            )
        }
    }
}

package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.ui.currency.CurrencySymbolFonts
import io.github.yesidrobayo.shopledger.ui.currency.LEFT_TO_RIGHT_ISOLATE
import io.github.yesidrobayo.shopledger.ui.currency.POP_DIRECTIONAL_ISOLATE
import io.github.yesidrobayo.shopledger.ui.currency.ResolvedSymbol
import io.github.yesidrobayo.shopledger.ui.currency.SymbolFont
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.CurrencySymbolFamily
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/** Decimales que muestra una cantidad en milésimas. */
private const val QUANTITY_DECIMALS = 3

/**
 * Un campo de monto. El símbolo va antes o después de las cifras según el formato del negocio, en su
 * propia fuente, y los separadores de miles aparecen mientras se escribe.
 *
 * Guarda el texto tal como se escribió, limpio: lo lee el dominio con los símbolos del formateador,
 * `MoneyFormatter.inputSymbols`, así que acepta coma y punto como decimal. El teclado es numérico, y
 * decimal si la moneda tiene decimales.
 *
 * @param error el mensaje que dice cómo arreglarlo, o `null`. Se pasa al salir del campo o al
 *   intentar guardar, nunca mientras se escribe.
 */
@Composable
fun MoneyField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    imeAction: ImeAction = ImeAction.Next,
) {
    val formatter = LocalAppFormats.current.money
    val resources = LocalResources.current
    val symbols = formatter.inputSymbols
    val symbol =
        remember(formatter, resources) {
            val parts = formatter.parts(Money.ZERO)
            symbolText(
                CurrencySymbolFonts.chain(resources).resolve(parts.symbol, parts.currencyCode),
            ) to
                parts.symbolFirst
        }
    val decimals = formatter.currencyDecimals
    NumberField(
        value = value,
        onValueChange = { onValueChange(cleanNumberInput(it, symbols, decimals)) },
        label = label,
        symbols = symbols,
        keyboardType = if (decimals > 0) KeyboardType.Decimal else KeyboardType.Number,
        modifier = modifier,
        error = error,
        imeAction = imeAction,
        prefix = symbol.first.takeIf { symbol.second },
        suffix = symbol.first.takeIf { !symbol.second },
    )
}

/**
 * Un campo de cantidad, con la unidad al final. Admite tres decimales si la unidad tiene fracciones,
 * y ninguno si no. Acepta coma y punto, porque el teclado decimal depende del fabricante.
 */
@Composable
fun QuantityField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    unit: UnitLabel,
    modifier: Modifier = Modifier,
    error: String? = null,
    imeAction: ImeAction = ImeAction.Next,
) {
    val symbols = LocalAppFormats.current.quantity.inputSymbols
    val decimals = if (unit.allowsFraction) QUANTITY_DECIMALS else 0
    NumberField(
        value = value,
        onValueChange = { onValueChange(cleanNumberInput(it, symbols, decimals)) },
        label = label,
        symbols = symbols,
        keyboardType = if (unit.allowsFraction) KeyboardType.Decimal else KeyboardType.Number,
        modifier = modifier,
        error = error,
        imeAction = imeAction,
        prefix = null,
        suffix = AnnotatedString(unit.symbol),
    )
}

@Composable
private fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    symbols: NumberSymbols,
    keyboardType: KeyboardType,
    modifier: Modifier,
    error: String?,
    imeAction: ImeAction,
    prefix: AnnotatedString?,
    suffix: AnnotatedString?,
) {
    val style = AppTheme.amounts.row
    val affixColor = MaterialTheme.colorScheme.onSurfaceVariant
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        textStyle = style,
        label = { Text(label, style = MaterialTheme.typography.bodyLarge) },
        prefix = prefix?.let { { Text(it, style = style, color = affixColor) } },
        suffix = suffix?.let { { Text(it, style = style, color = affixColor) } },
        isError = error != null,
        supportingText = error?.let { { FieldError(it) } },
        visualTransformation = DigitGroupingTransformation(symbols),
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = imeAction,
            autoCorrectEnabled = false,
        ),
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        colors = appFieldColors(),
    )
}

/**
 * El error de un campo: icono y mensaje debajo, para que no dependa del borde rojo. Lo comparten
 * todos los campos del catálogo.
 */
@Composable
internal fun FieldError(message: String) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s4),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_error),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(AppTheme.sizes.iconTag),
        )
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** Los colores de un campo: el papel de tarjeta, borde de contorno y foco en el color de acción. */
@Composable
fun appFieldColors(): TextFieldColors {
    val scheme = MaterialTheme.colorScheme
    val card = AppTheme.colors.cardSurface
    return OutlinedTextFieldDefaults.colors(
        focusedContainerColor = card,
        unfocusedContainerColor = card,
        errorContainerColor = card,
        focusedBorderColor = scheme.primary,
        unfocusedBorderColor = scheme.outline,
        focusedLabelColor = scheme.primary,
        unfocusedLabelColor = scheme.onSurfaceVariant,
        cursorColor = scheme.primary,
    )
}

/** El símbolo de la moneda, aislado y con cada tramo en su fuente, igual que dentro de un monto. */
private fun symbolText(symbol: ResolvedSymbol): AnnotatedString = buildAnnotatedString {
    append(LEFT_TO_RIGHT_ISOLATE)
    for (run in symbol.runs) {
        when (run.font) {
            SymbolFont.APP -> append(run.text)

            SymbolFont.CURRENCY_SYMBOLS -> withStyle(
                SpanStyle(fontFamily = CurrencySymbolFamily),
            ) {
                append(run.text)
            }

            SymbolFont.SYSTEM -> withStyle(SpanStyle(fontFamily = FontFamily.Default)) {
                append(run.text)
            }
        }
    }
    append(POP_DIRECTIONAL_ISOLATE)
}

@ThemePreviews
@Composable
private fun NumberFieldsPreview() {
    PreviewTheme {
        Column(
            Modifier.padding(AppTheme.spacing.s16),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
        ) {
            MoneyField("1234567", {}, stringResource(R.string.form_amount))
            MoneyField(
                "",
                {
                },
                stringResource(
                    R.string.form_amount,
                ),
                error = stringResource(R.string.state_error_read),
            )
            QuantityField("1,25", {}, stringResource(R.string.form_amount), PreviewSamples.KILOGRAM)
        }
    }
}

package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * Dos campos de un formulario que van juntos, como una comisión en porcentaje y una fija: lado a
 * lado mientras sus rótulos caben enteros en una línea, y uno debajo del otro cuando no, como con
 * letra grande o en una pantalla angosta. Un rótulo nunca se parte.
 *
 * Decide midiendo [firstLabel] y [secondLabel] con el estilo del rótulo de los campos, más el relleno
 * del campo y el espacio de un sufijo, contra la mitad del ancho disponible. [first] y [second]
 * reciben el modificador con el ancho que les toca.
 */
@Composable
fun AdaptiveFieldRow(
    firstLabel: String,
    secondLabel: String,
    modifier: Modifier = Modifier,
    first: @Composable (Modifier) -> Unit,
    second: @Composable (Modifier) -> Unit,
) {
    val spacing = AppTheme.spacing
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.bodyLarge
    val density = LocalDensity.current
    val widest =
        remember(firstLabel, secondLabel, style, density) {
            maxOf(
                measurer.measure(firstLabel, style).size.width,
                measurer.measure(secondLabel, style).size.width,
            )
        }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val half = with(density) { ((maxWidth - spacing.s12) / 2).roundToPx() }
        // El campo rodea su rótulo con su relleno a cada lado, y deja sitio para un sufijo corto.
        val chrome = with(density) { (spacing.s16 * 2 + spacing.s32).roundToPx() }
        if (widest + chrome <= half) {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.s12)) {
                first(Modifier.weight(1f))
                second(Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.s12)) {
                first(Modifier.fillMaxWidth())
                second(Modifier.fillMaxWidth())
            }
        }
    }
}

@ThemePreviews
@Composable
private fun AdaptiveFieldRowPreview() {
    PreviewTheme {
        AdaptiveFieldRow(
            firstLabel = stringResource(R.string.term_margin),
            secondLabel = stringResource(R.string.form_amount),
            modifier = Modifier.padding(AppTheme.spacing.s16),
            first = { PercentField("2,75", {}, stringResource(R.string.term_margin), it) },
            second = { MoneyField("100", {}, stringResource(R.string.form_amount), it) },
        )
    }
}

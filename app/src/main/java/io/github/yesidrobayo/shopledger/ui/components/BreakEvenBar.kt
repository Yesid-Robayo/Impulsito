package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * ¿Cuánto falta para cubrir los gastos fijos? Una barra de 12 dp con lo vendido en `chartBar`
 * sobre `surfaceContainerHigh`, y una marca vertical en `onSurface` en el punto de equilibrio.
 * La escala es la mayor de las dos cifras: si lo vendido ya pasó el punto, la barra cruza la
 * marca.
 *
 * Es un foco con `a11y_break_even_bar`. En modo discreto la barra queda sin relleno y sin marca,
 * porque su forma también diría cuánto se vendió.
 */
@Composable
fun BreakEvenBar(sold: Money, breakEven: Money, modifier: Modifier = Modifier) {
    val discreet = LocalDiscreetMode.current
    val (_, soldSpoken) = amountInText(sold)
    val (_, targetSpoken) = amountInText(breakEven)
    val description = stringResource(R.string.a11y_break_even_bar, targetSpoken, soldSpoken)
    // La proporción solo dibuja: ningún monto pasa por decimales binarios.
    val scale = maxOf(sold.minor, breakEven.minor).coerceAtLeast(1L).toFloat()
    val fill = (sold.minor.coerceAtLeast(0L) / scale).coerceIn(0f, 1f)
    val mark = (breakEven.minor.coerceAtLeast(0L) / scale).coerceIn(0f, 1f)
    val shape = MaterialTheme.shapes.small
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(AppTheme.sizes.breakEvenBar)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        if (!discreet) {
            if (fill > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(fill)
                        .height(AppTheme.sizes.breakEvenBar)
                        .background(AppTheme.colors.chartBar, shape),
                )
            }
            val markWidth = AppTheme.sizes.meterMark
            Box(
                Modifier
                    .padding(start = (maxWidth - markWidth) * mark)
                    .size(markWidth, AppTheme.sizes.breakEvenBar)
                    .background(MaterialTheme.colorScheme.onSurface),
            )
        }
    }
}

@ThemePreviews
@Composable
private fun BreakEvenBarPreview() {
    PreviewTheme {
        Column(Modifier.padding(AppTheme.spacing.s16)) {
            BreakEvenBar(Money(1_000_000), Money(1_777_778))
            BreakEvenBar(
                Money(2_400_000),
                Money(1_777_778),
                Modifier.padding(top = AppTheme.spacing.s16),
            )
        }
    }
}

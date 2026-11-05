package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * Una tarjeta: papel algo más claro que el fondo, con un filo de 1 dp y las esquinas grandes. Sin
 * sombra ni elevación tonal: la profundidad la da el cambio de papel. No se anidan tarjetas.
 *
 * @param onClick si la tarjeta entera se toca, con la onda estándar; `null` para una tarjeta fija.
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(AppTheme.spacing.s16),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = AppTheme.colors
    val border = BorderStroke(AppTheme.sizes.hairline, colors.cardBorder)
    val shape = MaterialTheme.shapes.large
    val inner: @Composable () -> Unit = {
        Column(Modifier.padding(contentPadding), content = content)
    }
    if (onClick == null) {
        Surface(
            modifier = modifier,
            shape = shape,
            color = colors.cardSurface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = border,
            content = inner,
        )
    } else {
        Surface(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            color = colors.cardSurface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = border,
            content = inner,
        )
    }
}

@ThemePreviews
@Composable
private fun AppCardPreview() {
    PreviewTheme {
        Column(
            modifier = Modifier.padding(AppTheme.spacing.s16),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
        ) {
            AppCard(Modifier.fillMaxWidth()) {
                Text(PreviewSamples.LONG_NAME, style = MaterialTheme.typography.titleSmall)
                MoneyText(PreviewSamples.PRICE, MoneySize.ROW)
            }
            AppCard(Modifier.fillMaxWidth(), onClick = {}) {
                Text(
                    stringResource(R.string.empty_catalog_title),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }
    }
}

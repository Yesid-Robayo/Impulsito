package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.rule.cash.CashSessionFigures
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * La tarjeta de la caja: si está abierta y desde cuándo, con icono, texto y etiqueta, y con caja
 * abierta, `Apertura`, `Ventas en efectivo`, `Entradas`, `Salidas` y `Esperado`.
 *
 * Las cifras son de la caja y se ocultan con el modo discreto. El estado nunca depende solo del
 * color: lo dicen el texto y la etiqueta. Con letra grande, cada cifra baja bajo su rótulo.
 *
 * Toda la tarjeta es un foco para TalkBack: el estado y, en orden, cada rótulo con su cifra.
 *
 * @param status `Caja abierta desde las …` o `Caja cerrada`, ya con su hora.
 * @param figures las cifras de la caja abierta, o `null` con la caja cerrada.
 */
@Composable
fun CashSessionCard(status: String, figures: CashSessionFigures?, modifier: Modifier = Modifier) {
    val spacing = AppTheme.spacing
    val open = figures != null
    AppCard(modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s8),
        ) {
            Icon(
                painterResource(if (open) R.drawable.ic_schedule else R.drawable.ic_check_circle),
                contentDescription = null,
                tint = if (open) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(AppTheme.sizes.icon),
            )
            Text(
                status,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            StatusTag(
                stringResource(
                    if (open) {
                        R.string.cash_session_status_open
                    } else {
                        R.string.cash_session_status_closed
                    },
                ),
                tone = if (open) StatusTone.INFO else StatusTone.NEUTRAL,
            )
        }
        figures ?: return@AppCard
        FigureRow(stringResource(R.string.cash_opening), figures.opening, merge = false)
        FigureRow(stringResource(R.string.cash_sales), figures.sales, merge = false)
        FigureRow(stringResource(R.string.cash_in), figures.cashIn, merge = false)
        FigureRow(stringResource(R.string.cash_out), figures.cashOut, merge = false)
        HorizontalDivider(
            thickness = AppTheme.sizes.hairline,
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(vertical = spacing.s4),
        )
        FigureRow(
            stringResource(R.string.cash_expected),
            figures.expected,
            strong = true,
            merge = false,
        )
    }
}

/**
 * Un rótulo con su cifra a la derecha, un solo foco; con letra grande, la cifra baja.
 *
 * @param merge falso dentro de algo que ya es un foco, como [CashSessionCard]: un foco adentro lo
 *   partiría en varios.
 * @param live si la cifra cambia mientras se escribe, como `Total contado`: TalkBack la anuncia en
 *   una región viva educada.
 */
@Composable
internal fun FigureRow(
    label: String,
    amount: Money?,
    strong: Boolean = false,
    sensitive: Boolean = true,
    merge: Boolean = true,
    live: Boolean = false,
) {
    val stacked = LocalDensity.current.fontScale >= STACKED_FONT_SCALE
    val typography = MaterialTheme.typography
    val style = if (strong) typography.titleSmall else typography.bodyLarge
    val size = if (strong) MoneySize.TITLE else MoneySize.ROW
    val focus =
        if (merge) {
            Modifier.semantics(mergeDescendants = true) {
                if (live) liveRegion = LiveRegionMode.Polite
            }
        } else {
            Modifier
        }
    if (stacked) {
        Column(Modifier.fillMaxWidth().then(focus)) {
            Text(label, style = style, color = MaterialTheme.colorScheme.onSurface)
            MoneyText(amount, size, sensitive = sensitive)
        }
    } else {
        Row(
            Modifier.fillMaxWidth().then(focus),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
        ) {
            Text(
                label,
                style = style,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            MoneyText(amount, size, sensitive = sensitive)
        }
    }
}

/** Desde qué tamaño de letra la cifra baja bajo su rótulo. */
private const val STACKED_FONT_SCALE = 1.5f

// ---------------------------------------------------------------- vistas previas

@ThemePreviews
@Composable
private fun CashSessionCardPreview() {
    PreviewTheme {
        Column(
            Modifier.padding(AppTheme.spacing.s16),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
        ) {
            CashSessionCard(
                status = stringResource(R.string.cash_open_since, "08:02"),
                figures = CashSessionFigures(
                    opening = Money(50_000),
                    sales = PreviewSamples.LONG_AMOUNT,
                    cashIn = Money(10_000),
                    cashOut = Money(-30_000),
                    expected = Money(242_000),
                ),
            )
            CashSessionCard(status = stringResource(R.string.cash_closed), figures = null)
        }
    }
}

@ThemePreviews
@Composable
private fun CashSessionCardDiscreetPreview() {
    PreviewTheme(discreet = true) {
        CashSessionCard(
            status = stringResource(R.string.cash_open_since, "08:02"),
            figures = CashSessionFigures(
                Money(50_000),
                Money(212_000),
                Money(10_000),
                Money(-30_000),
                Money(242_000),
            ),
            modifier = Modifier.padding(AppTheme.spacing.s16),
        )
    }
}

package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/** Dónde vive una tarjeta de cifra. */
enum class StatTileVariant {
    /** Una tarjeta propia, con relleno de 12 dp. */
    CARD,

    /** Sin fondo, sobre el héroe, con sus colores y el acento. */
    HERO,

    /** Sin fondo, para filas de cifras en un reporte. */
    PLAIN,
}

/**
 * La variación de una cifra frente a otro período.
 *
 * @param change la variación con signo, en puntos básicos.
 * @param favorable si es buena noticia: unas ventas que suben lo son, unos gastos que suben no. Lo
 *   decide quien la calcula, no el signo.
 * @param comparedTo el período de comparación, ya con palabras: `octubre`.
 */
@Immutable
data class StatChange(val change: BasisPoints, val favorable: Boolean, val comparedTo: String)

/**
 * Una cifra destacada: la etiqueta, el valor y, si hay comparación, la variación con icono, signo y
 * período. Sin comparación no hay línea: nunca un `0 %` que parezca medido.
 *
 * Sin comparación puede llevar [caption], una línea que dice qué es la cifra, como `Después de
 * gastos`. El valor negativo va en pérdida y con su signo. Sobre el héroe no se usan los colores de ganancia ni
 * de pérdida: el signo y la palabra marcan la pérdida, en el acento. Es un solo foco para TalkBack.
 */
@Composable
fun StatTile(
    label: String,
    amount: Money?,
    variant: StatTileVariant,
    modifier: Modifier = Modifier,
    change: StatChange? = null,
    sensitive: Boolean = false,
    loading: Boolean = false,
    caption: String? = null,
) {
    when (variant) {
        StatTileVariant.CARD ->
            AppCard(modifier, contentPadding = PaddingValues(AppTheme.spacing.s12)) {
                StatTileContent(label, amount, variant, change, sensitive, loading, caption)
            }

        StatTileVariant.HERO, StatTileVariant.PLAIN ->
            Column(modifier) {
                StatTileContent(label, amount, variant, change, sensitive, loading, caption)
            }
    }
}

@Composable
private fun ColumnScope.StatTileContent(
    label: String,
    amount: Money?,
    variant: StatTileVariant,
    change: StatChange?,
    sensitive: Boolean,
    loading: Boolean,
    caption: String?,
) {
    val hero = variant == StatTileVariant.HERO
    val app = AppTheme.colors
    val labelColor = if (hero) app.onHeroVariant else MaterialTheme.colorScheme.onSurfaceVariant
    if (loading) {
        Column(
            modifier = Modifier.semantics { contentDescription = label },
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
        ) {
            SkeletonBlock(Modifier.width(AppTheme.spacing.s64).height(AppTheme.spacing.s12))
            MoneyText(null, MoneySize.TILE, loading = true)
            SkeletonBlock(Modifier.width(AppTheme.spacing.s64 * 2).height(AppTheme.spacing.s12))
        }
        return
    }
    val negative = amount != null && amount < Money.ZERO
    val tone =
        when {
            hero && negative -> MoneyTone.HERO_ACCENT
            hero -> MoneyTone.ON_HERO
            negative -> MoneyTone.LOSS
            else -> MoneyTone.DEFAULT
        }
    Column(
        modifier = Modifier.semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s2),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = labelColor)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            MoneyText(amount, MoneySize.TILE, tone = tone, sensitive = sensitive)
            if (hero && negative) {
                Text(
                    stringResource(R.string.status_loss),
                    style = MaterialTheme.typography.labelMedium,
                    color = app.heroAccent,
                )
            }
        }
        when {
            change != null -> StatChangeLine(change, hero)

            caption != null -> Text(
                caption,
                style = MaterialTheme.typography.labelMedium,
                color = labelColor,
            )
        }
    }
}

/** La línea de variación: icono de subida o bajada, el porcentaje con signo y el período. */
@Composable
internal fun StatChangeLine(change: StatChange, hero: Boolean) {
    val app = AppTheme.colors
    val percent = LocalAppFormats.current.percent
    val up = change.change >= BasisPoints.ZERO
    val color: Color =
        when {
            hero -> app.heroAccent
            change.favorable -> app.profit
            else -> app.loss
        }
    val magnitude = percent.format(BasisPoints(if (up) change.change.bp else -change.change.bp))
    val spoken =
        stringResource(
            R.string.format_variation_vs_period,
            stringResource(
                if (up) R.string.a11y_percent_up else R.string.a11y_percent_down,
                magnitude,
            ),
            change.comparedTo,
        )
    Row(
        modifier = Modifier.clearAndSetSemantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s4),
    ) {
        Icon(
            painter = painterResource(
                if (up) R.drawable.ic_trending_up else R.drawable.ic_trending_down,
            ),
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(AppTheme.sizes.iconTag),
        )
        Text(
            text = stringResource(
                R.string.format_variation_vs_period,
                percent.formatChange(change.change),
                change.comparedTo,
            ),
            style = AppTheme.amounts.caption,
            color = color,
        )
    }
}

@ThemePreviews
@Composable
private fun StatTilePreview() {
    val s = PreviewSamples
    PreviewTheme {
        Column(
            Modifier.padding(AppTheme.spacing.s16),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
        ) {
            StatTile(
                stringResource(R.string.records_tab_sales),
                s.MONTH_SALES,
                StatTileVariant.CARD,
                Modifier.fillMaxWidth(),
                change = StatChange(
                    BasisPoints(800L),
                    favorable = true,
                    comparedTo = s.PERIOD_LABEL,
                ),
            )
            StatTile(
                stringResource(R.string.records_tab_expenses),
                s.LONG_AMOUNT,
                StatTileVariant.CARD,
                Modifier.fillMaxWidth(),
                change = StatChange(
                    BasisPoints(320L),
                    favorable = false,
                    comparedTo = s.PERIOD_LABEL,
                ),
            )
            StatTile(
                stringResource(R.string.term_margin),
                s.NEGATIVE_AMOUNT,
                StatTileVariant.PLAIN,
                sensitive = true,
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(AppTheme.colors.heroContainer, MaterialTheme.shapes.extraLarge)
                    .padding(AppTheme.spacing.s20),
                verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
            ) {
                StatTile(
                    stringResource(R.string.records_tab_sales),
                    s.MONTH_SALES,
                    StatTileVariant.HERO,
                    change = StatChange(
                        BasisPoints(-320L),
                        favorable = false,
                        comparedTo = s.PERIOD_LABEL,
                    ),
                )
                StatTile(
                    stringResource(R.string.term_margin),
                    s.NEGATIVE_AMOUNT,
                    StatTileVariant.HERO,
                )
            }
            StatTile(
                stringResource(R.string.records_tab_sales),
                null,
                StatTileVariant.CARD,
                loading = true,
            )
        }
    }
}

package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * Una fila de ranking: el nombre, el valor a la derecha y una barra de 6 dp proporcional al primer
 * lugar, sobre una pista del papel. La barra es decorativa: el valor ya está escrito, y en modo
 * discreto la barra conserva su forma.
 *
 * @param leader el valor del primer lugar, que llena la barra.
 * @param expanded si la fila se despliega, como un producto con sus variantes debajo: `true`
 *   abierta, `false` cerrada, y `null` si no se despliega. Con valor, la fila entera es un botón
 *   que avisa [onToggle], con una flecha y su estado para TalkBack.
 * @param nested si la fila va debajo de otra, como una variante bajo su producto: con sangría y
 *   el nombre en tinta secundaria. La barra sigue en la escala del primer lugar.
 */
@Composable
fun RankingRow(
    name: String,
    amount: Money,
    leader: Money,
    modifier: Modifier = Modifier,
    sensitive: Boolean = false,
    expanded: Boolean? = null,
    onToggle: () -> Unit = {},
    nested: Boolean = false,
) {
    // La proporción solo dibuja la barra: ningún monto pasa por decimales binarios.
    val fraction =
        if (leader <= Money.ZERO ||
            amount <= Money.ZERO
        ) {
            0f
        } else {
            (amount.minor.toFloat() / leader.minor.toFloat()).coerceIn(0f, 1f)
        }
    val spacing = AppTheme.spacing
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .rankingSemantics(expanded, onToggle)
                .padding(
                    start = if (nested) spacing.s40 else spacing.s16,
                    end = spacing.s16,
                    top = spacing.s8,
                    bottom = spacing.s8,
                ),
        verticalArrangement = Arrangement.spacedBy(spacing.s4),
    ) {
        LedgerLineLayout(
            gap = spacing.s12,
            leading = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        name,
                        style = MaterialTheme.typography.bodyMedium,
                        color =
                            if (nested) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (expanded != null) {
                        Icon(
                            painter = painterResource(R.drawable.ic_expand_more),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier =
                                Modifier
                                    .padding(start = spacing.s4)
                                    .size(AppTheme.sizes.icon)
                                    .rotate(if (expanded) 180f else 0f),
                        )
                    }
                }
            },
            trailing = { MoneyText(amount, MoneySize.SMALL, sensitive = sensitive) },
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(AppTheme.sizes.rankingBar)
                .background(
                    MaterialTheme.colorScheme.surfaceContainerHigh,
                    MaterialTheme.shapes.small,
                ),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(AppTheme.sizes.rankingBar)
                    .background(AppTheme.colors.chartBar, MaterialTheme.shapes.small),
            )
        }
    }
}

/**
 * Una fila quieta se lee como un solo foco; una que se despliega es un botón que dice si está
 * abierta, y su toque cubre la fila entera, que mide más de 48 dp.
 */
@Composable
private fun Modifier.rankingSemantics(expanded: Boolean?, onToggle: () -> Unit): Modifier {
    if (expanded == null) return semantics(mergeDescendants = true) {}
    val state =
        stringResource(
            if (expanded) R.string.a11y_ranking_expanded else R.string.a11y_ranking_collapsed,
        )
    val action =
        stringResource(
            if (expanded) R.string.a11y_ranking_collapse else R.string.a11y_ranking_expand,
        )
    return clickable(role = Role.Button, onClickLabel = action, onClick = onToggle)
        .semantics { stateDescription = state }
}

@ThemePreviews
@Composable
private fun RankingRowPreview() {
    val s = PreviewSamples
    PreviewTheme {
        Column {
            RankingRow(s.SHORT_NAME, s.MONTH_SALES, s.MONTH_SALES)
            RankingRow(s.LONG_NAME, Money(1_250_000L), s.MONTH_SALES)
            RankingRow(s.THIRD_NAME, s.PRICE, s.MONTH_SALES, sensitive = true)
        }
    }
}

@ThemePreviews
@Composable
private fun RankingRowExpandedPreview() {
    val s = PreviewSamples
    PreviewTheme {
        Column {
            RankingRow(s.LONG_NAME, s.MONTH_SALES, s.MONTH_SALES, expanded = true)
            RankingRow("M · Rojo", Money(2_500_000L), s.MONTH_SALES, nested = true)
            RankingRow(s.LONG_NAME, Money(1_750_000L), s.MONTH_SALES, nested = true)
            RankingRow(s.THIRD_NAME, s.PRICE, s.MONTH_SALES, expanded = false)
        }
    }
}

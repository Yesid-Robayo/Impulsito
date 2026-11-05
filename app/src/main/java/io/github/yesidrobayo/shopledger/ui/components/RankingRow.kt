package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
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
 */
@Composable
fun RankingRow(
    name: String,
    amount: Money,
    leader: Money,
    modifier: Modifier = Modifier,
    sensitive: Boolean = false,
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
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {}
                .padding(horizontal = AppTheme.spacing.s16, vertical = AppTheme.spacing.s8),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s4),
    ) {
        LedgerLineLayout(
            gap = AppTheme.spacing.s12,
            leading = {
                Text(
                    name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
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

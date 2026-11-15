package io.github.yesidrobayo.shopledger.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.report.BreakEven
import io.github.yesidrobayo.shopledger.ui.components.BreakEvenBar
import io.github.yesidrobayo.shopledger.ui.components.DISCREET_TEXT
import io.github.yesidrobayo.shopledger.ui.components.LedgerLineLayout
import io.github.yesidrobayo.shopledger.ui.components.MoneySize
import io.github.yesidrobayo.shopledger.ui.components.MoneyText
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.formatAnnotated
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews

/**
 * `Punto de equilibrio`, en `Herramientas`: cuánto habría que vender con el margen del período
 * para cubrir los gastos de las categorías marcadas como fijas.
 *
 * - **Con margen:** los gastos fijos, el margen y las ventas para cubrirlos, la barra con lo
 *   vendido frente a la marca del punto, y cuánto falta o que ya se cubrieron.
 * - **Sin margen o sin ventas:** las ventas para cubrirlos dicen `—`, nunca cero, con un aviso.
 * - **Sin gastos fijos:** un aviso lleva a marcar categorías.
 *
 * Montos y margen se ocultan en modo discreto. Va en su propio archivo, aparte de la pantalla,
 * para que las demás herramientas no lo toquen.
 *
 * @param onMarkCategories abre las categorías, para marcar cuáles son gastos fijos.
 */
@Composable
internal fun BreakEvenTool(
    breakEven: BreakEven,
    onMarkCategories: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionHeader(stringResource(R.string.stats_tool_break_even))
    Column(modifier, verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8)) {
        when (breakEven) {
            BreakEven.NoFixedExpenses ->
                NoticeCard(
                    text = stringResource(R.string.stats_break_even_no_fixed),
                    variant = NoticeVariant.INFO,
                    actionLabel = stringResource(R.string.stats_break_even_no_fixed_action),
                    onAction = onMarkCategories,
                )

            is BreakEven.NoMargin -> {
                Column {
                    BreakEvenRow(
                        stringResource(R.string.stats_break_even_fixed),
                        breakEven.fixedExpenses,
                    )
                    BreakEvenRow(
                        stringResource(R.string.stats_break_even_sales),
                        null,
                        strong = true,
                    )
                }
                NoticeCard(
                    stringResource(R.string.stats_break_even_no_margin),
                    NoticeVariant.WARNING,
                )
            }

            BreakEven.Unavailable ->
                BreakEvenRow(stringResource(R.string.stats_break_even_sales), null, strong = true)

            is BreakEven.Calculated -> Calculated(breakEven)
        }
    }
}

@Composable
private fun Calculated(breakEven: BreakEven.Calculated) {
    Column {
        BreakEvenRow(stringResource(R.string.stats_break_even_fixed), breakEven.fixedExpenses)
        MarginLine(breakEven.margin)
        BreakEvenRow(
            stringResource(R.string.stats_break_even_sales),
            breakEven.breakEvenSales,
            strong = true,
        )
    }
    BreakEvenBar(sold = breakEven.netSales, breakEven = breakEven.breakEvenSales)
    if (breakEven.remaining > Money.ZERO) {
        val (amount, spoken) = moneyInText(breakEven.remaining)
        val description = stringResource(R.string.stats_break_even_missing, spoken)
        Text(
            formatAnnotated(stringResource(R.string.stats_break_even_missing), amount),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { contentDescription = description },
        )
    } else {
        Text(
            stringResource(R.string.stats_break_even_covered),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    Text(
        stringResource(R.string.stats_break_even_help),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Una fila de la herramienta: el rótulo y su monto, o «sin valor» si [amount] falta. */
@Composable
private fun BreakEvenRow(label: String, amount: Money?, strong: Boolean = false) {
    LedgerLineLayout(
        gap = AppTheme.spacing.s16,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = AppTheme.sizes.touchTarget)
                .semantics(mergeDescendants = true) {},
        leading = {
            Text(
                label,
                style =
                    if (strong) {
                        MaterialTheme.typography.titleSmall
                    } else {
                        MaterialTheme.typography.bodyLarge
                    },
            )
        },
        trailing = {
            MoneyText(
                amount,
                if (strong) MoneySize.TILE else MoneySize.ROW,
                sensitive = true,
            )
        },
    )
}

/** El margen con que se calculó, oculto en modo discreto como en la cascada. */
@Composable
private fun MarginLine(margin: BasisPoints) {
    val discreet = LocalDiscreetMode.current
    val text = if (discreet) DISCREET_TEXT else LocalAppFormats.current.percent.format(margin)
    val spoken = if (discreet) stringResource(R.string.a11y_discreet_hidden) else text
    val label = stringResource(R.string.term_margin)
    val description = stringResource(R.string.a11y_label_value, label, spoken)
    LedgerLineLayout(
        gap = AppTheme.spacing.s16,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = AppTheme.sizes.touchTarget)
                .semantics(mergeDescendants = true) { contentDescription = description },
        leading = { Text(label, style = MaterialTheme.typography.bodyLarge) },
        trailing = { Text(text, style = AppTheme.amounts.row, maxLines = 1, softWrap = false) },
    )
}

@ThemePreviews
@Composable
private fun BreakEvenToolPreview() {
    PreviewTheme {
        Column {
            BreakEvenTool(
                BreakEven.Calculated(
                    fixedExpenses = Money(640_000),
                    margin = BasisPoints(3_600),
                    breakEvenSales = Money(1_777_778),
                    netSales = Money(1_000_000),
                    remaining = Money(777_778),
                ),
                onMarkCategories = {},
            )
            BreakEvenTool(BreakEven.NoMargin(Money(640_000)), onMarkCategories = {})
            BreakEvenTool(BreakEven.NoFixedExpenses, onMarkCategories = {})
        }
    }
}

@ThemePreviews
@Composable
private fun BreakEvenToolDiscreetPreview() {
    PreviewTheme(discreet = true) {
        BreakEvenTool(
            BreakEven.Calculated(
                fixedExpenses = Money(640_000),
                margin = BasisPoints(3_600),
                breakEvenSales = Money(1_777_778),
                netSales = Money(2_000_000),
                remaining = Money.ZERO,
            ),
            onMarkCategories = {},
        )
    }
}

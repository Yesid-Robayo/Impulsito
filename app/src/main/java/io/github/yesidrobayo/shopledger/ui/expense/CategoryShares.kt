package io.github.yesidrobayo.shopledger.ui.expense

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import io.github.yesidrobayo.shopledger.domain.rule.expense.ExpenseCategorySummary
import io.github.yesidrobayo.shopledger.domain.rule.expense.ExpenseShareRow
import io.github.yesidrobayo.shopledger.localization.SeedText
import io.github.yesidrobayo.shopledger.ui.components.LedgerLineLayout
import io.github.yesidrobayo.shopledger.ui.components.MoneySize
import io.github.yesidrobayo.shopledger.ui.components.MoneyText
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.currency.rememberMoneyDisplay
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * `Por categoría`: lo gastado en el mes en cada categoría, de mayor a menor, con una barra que mide
 * su parte del total del mes. Con más de cinco categorías, las demás van juntas en una fila que
 * abre la hoja de categorías.
 *
 * Tocar una categoría filtra la lista por ella, igual que elegirla en el filtro; tocar la que ya
 * está elegida quita el filtro. La elegida lleva el fondo de selección, una marca y su barra en el
 * color de lo elegido: no depende solo del color.
 *
 * En modo discreto los montos se ocultan y las barras quedan: dicen la proporción, no el monto.
 * TalkBack lee cada fila como `Alquiler, 500.000, 61 % del mes`.
 *
 * @param selectedCategoryId la categoría del filtro, o `null` si no hay.
 */
@Composable
internal fun CategoryShares(
    summary: ExpenseCategorySummary,
    selectedCategoryId: Long?,
    onAction: (ExpensesAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(bottom = AppTheme.spacing.s8)) {
        SectionHeader(stringResource(R.string.expense_by_category))
        summary.rows.forEach { row ->
            val selected =
                row is ExpenseShareRow.Category && row.total.categoryId == selectedCategoryId
            ShareRow(row, summary.total, selected) {
                onAction(
                    when (row) {
                        is ExpenseShareRow.Category ->
                            ExpensesAction.CategoryFilterChange(
                                if (selected) null else row.total.categoryId,
                            )

                        is ExpenseShareRow.Others -> ExpensesAction.OpenCategories
                    },
                )
            }
        }
    }
}

/** Una fila: el nombre y el monto en una línea, y debajo la barra de su parte del mes. */
@Composable
private fun ShareRow(row: ExpenseShareRow, total: Money, selected: Boolean, onSelect: () -> Unit) {
    val spacing = AppTheme.spacing
    val scheme = MaterialTheme.colorScheme
    val name = shareName(row)
    val formats = LocalAppFormats.current
    val display = rememberMoneyDisplay(row.amount, formats.money)
    val spokenAmount =
        if (LocalDiscreetMode.current) {
            stringResource(R.string.a11y_discreet_hidden)
        } else {
            display.description
        }
    val description =
        stringResource(
            R.string.a11y_expense_share,
            name,
            spokenAmount,
            formats.percent.format(row.share),
        )
    // La proporción solo dibuja la barra: ningún monto pasa por decimales binarios.
    val fraction =
        if (total <= Money.ZERO) {
            0f
        } else {
            (row.amount.minor.toFloat() / total.minor.toFloat()).coerceIn(0f, 1f)
        }
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = AppTheme.sizes.touchTarget)
            .background(if (selected) scheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onSelect)
            .clearAndSetSemantics {
                contentDescription = description
                this.selected = selected
                role = Role.Button
                onClick {
                    onSelect()
                    true
                }
            }.padding(horizontal = spacing.s16, vertical = spacing.s8),
        verticalArrangement = Arrangement.spacedBy(spacing.s4),
    ) {
        LedgerLineLayout(
            gap = spacing.s12,
            leading = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.s4),
                ) {
                    if (selected) {
                        Icon(
                            painter = painterResource(R.drawable.ic_check),
                            contentDescription = null,
                            tint = scheme.onSecondaryContainer,
                            modifier = Modifier.size(AppTheme.sizes.icon),
                        )
                    }
                    Text(
                        name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selected) FontWeight.SemiBold else null,
                        color = if (selected) scheme.onSecondaryContainer else scheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            },
            trailing = { MoneyText(row.amount, MoneySize.SMALL, sensitive = true) },
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(AppTheme.sizes.rankingBar)
                .background(scheme.surfaceContainerHigh, MaterialTheme.shapes.small),
        ) {
            val bar = if (selected) AppTheme.colors.chartBarSelected else AppTheme.colors.chartBar
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(AppTheme.sizes.rankingBar)
                    .background(bar, MaterialTheme.shapes.small),
            )
        }
    }
}

/** El nombre de la fila: el de la categoría en el idioma de la app, o cuántas otras van juntas. */
@Composable
private fun shareName(row: ExpenseShareRow): String = when (row) {
    is ExpenseShareRow.Category ->
        SeedText.displayName(
            LocalResources.current,
            SeedTable.EXPENSE_CATEGORIES,
            row.total.systemKey,
            row.total.name,
        )

    is ExpenseShareRow.Others ->
        pluralStringResource(R.plurals.expense_share_others, row.count, row.count)
}

package io.github.yesidrobayo.shopledger.screenshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.android.tools.screenshot.PreviewTest
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.BackupStatusBanner
import io.github.yesidrobayo.shopledger.ui.components.BackupStatusChip
import io.github.yesidrobayo.shopledger.ui.components.BackupStatusLook
import io.github.yesidrobayo.shopledger.ui.components.BackupTone
import io.github.yesidrobayo.shopledger.ui.components.BalanceBadge
import io.github.yesidrobayo.shopledger.ui.components.BalanceBadgeVariant
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.LedgerRow
import io.github.yesidrobayo.shopledger.ui.components.MoneySize
import io.github.yesidrobayo.shopledger.ui.components.MoneyText
import io.github.yesidrobayo.shopledger.ui.components.MoneyTone
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.ProductRow
import io.github.yesidrobayo.shopledger.ui.components.ProductRowData
import io.github.yesidrobayo.shopledger.ui.components.ProductRowTrailing
import io.github.yesidrobayo.shopledger.ui.components.ProductStock
import io.github.yesidrobayo.shopledger.ui.components.SearchField
import io.github.yesidrobayo.shopledger.ui.components.StatChange
import io.github.yesidrobayo.shopledger.ui.components.StatTile
import io.github.yesidrobayo.shopledger.ui.components.StatTileVariant
import io.github.yesidrobayo.shopledger.ui.components.StatusTag
import io.github.yesidrobayo.shopledger.ui.components.StatusTone
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/** Los datos inventados, con un nombre corto para que las filas quepan en el ancho. */
private val S = CaptureSamples

// Los componentes del sistema de diseño, agrupados en cuatro hojas: montos, avisos, filas y
// acciones. Cada hoja sale con la paleta inicial y sin decimales y con la paleta de contraste
// más justo, y las que llevan montos, también con tres decimales, en las nueve combinaciones de
// idioma, modo y letra de la matriz. Sin esqueletos ni indicadores que giran: una captura solo
// vale si sale igual en cada corrida.

@PreviewTest
@ComponentMatrix
@Composable
fun AmountsDefault() {
    CaptureTheme { AmountsSheet() }
}

@PreviewTest
@ComponentMatrix
@Composable
fun AmountsVivid() {
    CaptureTheme(palette = ColorPalette.VIVID_RED_CREAM) { AmountsSheet() }
}

@PreviewTest
@ComponentMatrix
@Composable
fun AmountsThreeDecimals() {
    CaptureTheme(format = CaptureFormat.THREE_DECIMALS) { AmountsSheet() }
}

@PreviewTest
@ComponentMatrix
@Composable
fun NoticesDefault() {
    CaptureTheme { NoticesSheet() }
}

@PreviewTest
@ComponentMatrix
@Composable
fun NoticesVivid() {
    CaptureTheme(palette = ColorPalette.VIVID_RED_CREAM) { NoticesSheet() }
}

@PreviewTest
@ComponentMatrix
@Composable
fun RowsDefault() {
    CaptureTheme { RowsSheet() }
}

@PreviewTest
@ComponentMatrix
@Composable
fun RowsVivid() {
    CaptureTheme(palette = ColorPalette.VIVID_RED_CREAM) { RowsSheet() }
}

@PreviewTest
@ComponentMatrix
@Composable
fun RowsThreeDecimals() {
    CaptureTheme(format = CaptureFormat.THREE_DECIMALS) { RowsSheet() }
}

@PreviewTest
@ComponentMatrix
@Composable
fun ActionsDefault() {
    CaptureTheme { ActionsSheet() }
}

@PreviewTest
@ComponentMatrix
@Composable
fun ActionsVivid() {
    CaptureTheme(palette = ColorPalette.VIVID_RED_CREAM) { ActionsSheet() }
}

/** Montos de todos los tamaños y tonos, saldos de cliente y de proveedor, y cifras de período. */
@Composable
private fun AmountsSheet() {
    val spacing = AppTheme.spacing
    Column(
        Modifier.fillMaxWidth().padding(spacing.s16),
        verticalArrangement = Arrangement.spacedBy(spacing.s12),
    ) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(spacing.s8),
        ) {
            MoneyText(S.LONG_AMOUNT, MoneySize.HERO)
            MoneyText(S.MONTH_SALES, MoneySize.LARGE)
            MoneyText(S.SECOND_PRICE, MoneySize.TITLE)
            MoneyText(S.NEGATIVE_AMOUNT, MoneySize.TILE, tone = MoneyTone.LOSS)
            MoneyText(S.PRICE, MoneySize.ROW, tone = MoneyTone.PROFIT)
            MoneyText(Money.ZERO, MoneySize.SMALL, tone = MoneyTone.MUTED)
            MoneyText(null, MoneySize.ROW)
            MoneyText(S.SECOND_PRICE, MoneySize.ROW, voided = true)
        }
        BalanceBadge(BalanceBadgeVariant.OWES, S.PRICE)
        BalanceBadge(BalanceBadgeVariant.OVERDUE, S.LONG_AMOUNT)
        BalanceBadge(BalanceBadgeVariant.CREDIT, S.NEGATIVE_AMOUNT)
        BalanceBadge(BalanceBadgeVariant.SUPPLIER_OWED, S.SECOND_PRICE)
        StatTile(
            stringResource(R.string.records_tab_sales),
            S.MONTH_SALES,
            StatTileVariant.CARD,
            Modifier.fillMaxWidth(),
            change =
                StatChange(BasisPoints(800L), favorable = true, comparedTo = S.COMPARED_PERIOD),
        )
        StatTile(
            stringResource(R.string.records_tab_expenses),
            S.LONG_AMOUNT,
            StatTileVariant.CARD,
            Modifier.fillMaxWidth(),
            change =
                StatChange(BasisPoints(320L), favorable = false, comparedTo = S.COMPARED_PERIOD),
        )
        StatTile(stringResource(R.string.term_margin), S.NEGATIVE_AMOUNT, StatTileVariant.PLAIN)
    }
}

/** Etiquetas de estado, avisos en sus tres tonos y el estado del respaldo. */
@Composable
private fun NoticesSheet() {
    val spacing = AppTheme.spacing
    Column(
        Modifier.fillMaxWidth().padding(spacing.s16),
        verticalArrangement = Arrangement.spacedBy(spacing.s12),
    ) {
        StatusTag(stringResource(R.string.status_no_cost))
        StatusTag(stringResource(R.string.status_archived))
        StatusTag(stringResource(R.string.status_voided))
        StatusTag(
            stringResource(R.string.term_low_stock),
            icon = R.drawable.ic_warning,
            tone = StatusTone.WARNING,
        )
        StatusTag(
            stringResource(R.string.term_out_of_stock),
            icon = R.drawable.ic_block,
            tone = StatusTone.LOSS,
        )
        StatusTag(stringResource(R.string.a11y_today), tone = StatusTone.INFO)
        NoticeCard(
            stringResource(R.string.notice_negative_margin),
            NoticeVariant.WARNING,
            icon = R.drawable.ic_trending_down,
        )
        NoticeCard(
            stringResource(R.string.state_error_read),
            NoticeVariant.ERROR,
            actionLabel = stringResource(R.string.action_retry),
        )
        BackupStatusChip(
            BackupStatusLook(
                stringResource(R.string.backup_last_copy_moment),
                BackupTone.OK,
                R.drawable.ic_cloud_done,
            ),
            onClick = {},
        )
        BackupStatusBanner(
            BackupStatusLook(
                stringResource(R.string.backup_state_failed),
                BackupTone.ERROR,
                R.drawable.ic_error,
            ),
            actionLabel = stringResource(R.string.backup_action_review),
        )
        BackupStatusBanner(
            BackupStatusLook(
                stringResource(R.string.backup_state_not_connected_detail),
                BackupTone.WARNING,
                R.drawable.ic_cloud_off,
            ),
            actionLabel = stringResource(R.string.backup_action_choose_destination),
            secondaryLabel = stringResource(R.string.backup_action_understood),
        )
    }
}

/** Filas de producto con cada forma del stock y de las cifras, y filas de una libreta. */
@Composable
private fun RowsSheet() {
    val rows =
        listOf(
            ProductRowData(
                S.SHORT_NAME,
                ProductStock.Normal(S.STOCK, S.UNIT_LABEL),
                ProductRowTrailing.Catalog(S.PRICE, Margin.Defined(BasisPoints(2_400L)), true),
            ),
            ProductRowData(
                S.LONG_NAME,
                ProductStock.Low(S.LOW_STOCK, S.UNIT_LABEL),
                ProductRowTrailing.Catalog(
                    S.LONG_AMOUNT,
                    Margin.Defined(BasisPoints(3_210L)),
                    true,
                ),
            ),
            ProductRowData(
                S.SECOND_NAME,
                ProductStock.Out,
                ProductRowTrailing.Catalog(null, Margin.NoData, true),
            ),
            ProductRowData(
                S.THIRD_NAME,
                ProductStock.Negative(S.NEGATIVE_STOCK, S.UNIT_LABEL),
                ProductRowTrailing.Catalog(S.PRICE, Margin.Defined(BasisPoints(-500L)), true),
            ),
            ProductRowData(
                S.SECOND_NAME,
                ProductStock.Service,
                ProductRowTrailing.Catalog(S.SECOND_PRICE, Margin.NoData, false),
                archived = true,
            ),
            ProductRowData(
                S.SHORT_NAME,
                ProductStock.Low(S.LOW_STOCK, S.UNIT_LABEL),
                ProductRowTrailing.Stock(S.LOW_STOCK, S.MINIMUM, S.UNIT_LABEL),
            ),
            ProductRowData(
                S.SECOND_NAME,
                ProductStock.Normal(S.WEIGHT, S.KILOGRAM_LABEL),
                ProductRowTrailing.SaleLine(S.SECOND_PRICE, S.WEIGHT, S.PRICE, S.KILOGRAM_LABEL),
            ),
            ProductRowData(
                S.THIRD_NAME,
                ProductStock.Normal(S.TWO, S.UNIT_LABEL),
                ProductRowTrailing.PricePreview(S.PRICE, S.OLD_PRICE),
            ),
        )
    Column(Modifier.fillMaxWidth()) {
        rows.forEach { ProductRow(it, onClick = {}) }
        LedgerRow(
            title = S.LEDGER_PAYMENT,
            detail = S.LEDGER_DETAIL,
            amount = Money(-S.PRICE.minor),
            balanceAfter = S.SECOND_PRICE,
            balanceText = R.string.customer_ledger_balance_after,
            onClick = {},
        )
        LedgerRow(
            title = S.LEDGER_PAYMENT,
            detail = S.LONG_NAME,
            amount = S.LONG_AMOUNT,
            balanceAfter = S.LONG_AMOUNT,
            balanceText = R.string.customer_ledger_balance_after,
            voided = true,
        )
    }
}

/** Los botones en cada variante y estado, la búsqueda y un estado vacío con su acción. */
@Composable
private fun ActionsSheet() {
    val spacing = AppTheme.spacing
    Column(
        Modifier.fillMaxWidth().padding(spacing.s16),
        verticalArrangement = Arrangement.spacedBy(spacing.s12),
    ) {
        ActionVariant.entries.filter { it != ActionVariant.ON_HERO }.forEach { variant ->
            PrimaryActionButton(stringResource(R.string.empty_home_action), {}, variant)
        }
        PrimaryActionButton(
            stringResource(R.string.empty_home_action),
            {},
            ActionVariant.TONAL,
            state = ActionState.DONE,
        )
        PrimaryActionButton(
            stringResource(R.string.empty_home_action),
            {},
            ActionVariant.PRIMARY,
            enabled = false,
        )
        InlineTextButton(stringResource(R.string.action_retry), {})
        SearchField("", {}, stringResource(R.string.sale_search_hint), onScan = {})
        SearchField(S.SEARCH_QUERY, {}, stringResource(R.string.sale_search_hint))
        EmptyState(
            icon = R.drawable.ic_inventory_2,
            title = stringResource(R.string.empty_catalog_title),
            body = stringResource(R.string.empty_catalog_body),
        ) {
            PrimaryActionButton(
                stringResource(R.string.empty_catalog_action),
                {},
                ActionVariant.PRIMARY,
            )
        }
    }
}

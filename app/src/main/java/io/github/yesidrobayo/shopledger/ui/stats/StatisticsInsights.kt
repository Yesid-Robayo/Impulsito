package io.github.yesidrobayo.shopledger.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.report.PaymentMethodTotal
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodNoticeCounts
import io.github.yesidrobayo.shopledger.domain.rule.report.ExpenseRow
import io.github.yesidrobayo.shopledger.domain.rule.report.RankedProduct
import io.github.yesidrobayo.shopledger.domain.rule.report.TopProducts
import io.github.yesidrobayo.shopledger.domain.usecase.report.ComparedPeriod
import io.github.yesidrobayo.shopledger.localization.SeedText
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.CompactChipRow
import io.github.yesidrobayo.shopledger.ui.components.DateField
import io.github.yesidrobayo.shopledger.ui.components.LedgerLineLayout
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.RankingRow
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.StatChange
import io.github.yesidrobayo.shopledger.ui.components.StatChangeLine
import io.github.yesidrobayo.shopledger.ui.components.formatAnnotated
import io.github.yesidrobayo.shopledger.ui.components.keepsMessagesAbove
import io.github.yesidrobayo.shopledger.ui.components.periodLabel
import io.github.yesidrobayo.shopledger.ui.components.quantityText
import io.github.yesidrobayo.shopledger.ui.components.unitLabel
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import kotlinx.datetime.DayOfWeek

// Lo que explica las cifras de `Números`: la comparación, los avisos, los más vendidos y los
// desgloses por método y por categoría, y la elección de un rango propio.

/** Los rankings, en el orden de sus chips. */
private val RANKINGS = listOf(RankingKind.SALES, RankingKind.PROFIT, RankingKind.QUANTITY)

/**
 * La variación de la cifra grande frente al período comparable: `+8,0 % frente a octubre`. Sin
 * variación medible no hay línea: nunca un `0 %` que parezca medido.
 */
@Composable
internal fun VariationLine(compared: ComparedPeriod?, metric: ChartMetric, today: BusinessDate) {
    val change: BasisPoints =
        when (metric) {
            ChartMetric.SALES -> compared?.variation?.netSales
            ChartMetric.PROFIT -> compared?.variation?.grossProfit
        } ?: return
    val period = requireNotNull(compared).comparedTo
    // Que suban las ventas o la ganancia es buena noticia.
    StatChangeLine(
        StatChange(change, favorable = change >= BasisPoints.ZERO, periodLabel(period, today)),
        hero = false,
    )
}

/** Los avisos que explican una cifra, sin bloquear nada. */
@Composable
internal fun Notices(counts: PeriodNoticeCounts) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8)) {
        NoticeIf(R.plurals.notice_sales_no_cost, counts.salesWithoutCost)
        NoticeIf(R.plurals.notice_negative_stock, counts.negativeStock)
        NoticeIf(R.plurals.notice_period_voided, counts.voidedAfterPeriod)
        NoticeIf(R.plurals.notice_period_late, counts.lateRecords)
    }
}

@Composable
private fun NoticeIf(plural: Int, count: Long) {
    if (count <= 0L) return
    val shown = count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    NoticeCard(pluralStringResource(plural, shown, shown), NoticeVariant.WARNING)
}

/** `Anulados en este período: 2`, solo si hubo alguno. */
@Composable
internal fun VoidedRow(counts: PeriodNoticeCounts, modifier: Modifier) {
    if (counts.voidedCount <= 0L) return
    Text(
        stringResource(R.string.stats_voided_count, counts.voidedCount.toString()),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = AppTheme.spacing.s8),
    )
}

/** `Lo que más se vendió`: los diez primeros por ventas, por ganancia o por cantidad. */
@Composable
internal fun TopSold(
    top: TopProducts,
    ranking: RankingKind,
    units: Map<Long, MeasureUnit>,
    onSelect: (RankingKind) -> Unit,
    modifier: Modifier,
) {
    if (top.bySales.isEmpty() && top.byProfit.isEmpty() && top.byQuantity.isEmpty()) return
    SectionHeader(stringResource(R.string.stats_top_sold))
    Column(modifier, verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8)) {
        CompactChipRow(
            options = RANKINGS,
            label = { rankingLabel(it) },
            selected = setOf(ranking),
            onToggle = onSelect,
            multiple = false,
        )
        when (ranking) {
            RankingKind.SALES -> MoneyRanking(top.bySales) { it.netSales }

            RankingKind.PROFIT -> MoneyRanking(top.byProfit) { it.grossProfit }

            RankingKind.QUANTITY ->
                top.byQuantity.forEach { inUnit ->
                    val unit = units[inUnit.unitId]
                    inUnit.products.forEach { QuantityRow(it, unit) }
                }
        }
    }
}

@Composable
private fun MoneyRanking(products: List<RankedProduct>, amountOf: (RankedProduct) -> Money) {
    val leader = products.firstOrNull()?.let(amountOf) ?: return
    Column {
        products.forEach { RankingRow(it.name, amountOf(it), leader, sensitive = true) }
    }
}

/** Una fila por cantidad: el nombre y lo que salió, en su unidad. La cantidad no es dinero. */
@Composable
private fun QuantityRow(product: RankedProduct, unit: MeasureUnit?) {
    LedgerLineLayout(
        gap = AppTheme.spacing.s16,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = AppTheme.sizes.touchTarget)
                .semantics(mergeDescendants = true) {},
        leading = { Text(product.name, style = MaterialTheme.typography.bodyLarge) },
        trailing = {
            Text(
                if (unit == null) {
                    plainQuantity(product)
                } else {
                    quantityText(product.quantity, unitLabel(unit))
                },
                style = AppTheme.amounts.row,
            )
        },
    )
}

/** Sin la unidad a mano, la cantidad sola, sin unidad que la nombre mal. */
@Composable
private fun plainQuantity(product: RankedProduct): String =
    LocalAppFormats.current.quantity.format(product.quantity, allowsFraction = true)

/**
 * `Por método de pago`: cada método por el nombre que quedó en sus pagos, con lo cobrado, la
 * comisión y el neto recibido. La suma de los métodos es lo que se cobró en las ventas.
 */
@Composable
internal fun ByPaymentMethod(methods: List<PaymentMethodTotal>, modifier: Modifier) {
    val leader = methods.firstOrNull()?.amount ?: return
    SectionHeader(stringResource(R.string.stats_by_payment))
    Column(modifier) {
        methods.forEach { method ->
            RankingRow(method.name, method.amount, leader, sensitive = true)
            if (method.fees != Money.ZERO) MethodDetail(method)
        }
    }
}

@Composable
private fun MethodDetail(method: PaymentMethodTotal) {
    val pattern = stringResource(R.string.stats_method_detail)
    val (fee, feeSpoken) = moneyInText(method.fees)
    val (net, netSpoken) = moneyInText(method.amount - method.fees)
    val description = stringResource(R.string.stats_method_detail, feeSpoken, netSpoken)
    Text(
        text = formatAnnotated(pattern, fee, net),
        style = AppTheme.amounts.caption,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            Modifier
                .padding(bottom = AppTheme.spacing.s8)
                .semantics { contentDescription = description },
    )
}

/**
 * `Gastos por categoría`: cada categoría, y `Daños y pérdidas` o `Sobrante` y `Comisiones` como
 * filas aparte, para que la suma sea la de los gastos totales.
 */
@Composable
internal fun ByExpenseCategory(rows: List<ExpenseRow>, modifier: Modifier) {
    if (rows.isEmpty()) return
    val leader = rows.maxOf { if (it.amount < Money.ZERO) -it.amount else it.amount }
    val resources = LocalResources.current
    SectionHeader(stringResource(R.string.stats_by_expense_category))
    Column(modifier) {
        rows.forEach { row ->
            val name =
                when (row) {
                    is ExpenseRow.Category ->
                        SeedText.displayName(
                            resources,
                            SeedTable.EXPENSE_CATEGORIES,
                            row.total.systemKey,
                            row.total.name,
                        )

                    is ExpenseRow.Shrinkage ->
                        stringResource(
                            if (row.amount <
                                Money.ZERO
                            ) {
                                R.string.term_surplus
                            } else {
                                R.string.term_damage_loss
                            },
                        )

                    is ExpenseRow.Fees -> stringResource(R.string.stats_row_fees)
                }
            RankingRow(name, row.amount, leader, sensitive = true)
        }
    }
}

/**
 * La elección de un rango propio: desde y hasta, los dos incluidos, sin fechas futuras. Sus cifras
 * no se comparan con nada y no llevan gráfica: un rango largo no cabe en barras por día.
 */
@Composable
internal fun RangeSheet(
    current: ReportPeriod,
    today: BusinessDate,
    weekStartDay: DayOfWeek,
    onShow: (BusinessDate, BusinessDate) -> Unit,
    onDismiss: () -> Unit,
) {
    var from by rememberSaveable { mutableStateOf(current.start.toString()) }
    var to by rememberSaveable { mutableStateOf(minOf(current.end, today).toString()) }
    val start = requireNotNull(BusinessDate.parse(from)) { "Fecha guardada inválida" }
    val end = requireNotNull(BusinessDate.parse(to)) { "Fecha guardada inválida" }
    AppBottomSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.stats_choose_dates),
        action = {
            PrimaryActionButton(
                text = stringResource(R.string.stats_range_show),
                onClick = { onShow(start, end) },
                variant = ActionVariant.PRIMARY,
                modifier = Modifier.fillMaxWidth().keepsMessagesAbove(),
            )
        },
    ) {
        DateField(
            label = stringResource(R.string.stats_range_from),
            date = start,
            onDateChange = { from = it.toString() },
            today = today,
            weekStartDay = weekStartDay,
        )
        DateField(
            label = stringResource(R.string.stats_range_to),
            date = end,
            onDateChange = { to = it.toString() },
            today = today,
            weekStartDay = weekStartDay,
        )
    }
}

@Composable
private fun rankingLabel(ranking: RankingKind): String = stringResource(
    when (ranking) {
        RankingKind.SALES -> R.string.stats_by_sales
        RankingKind.PROFIT -> R.string.stats_by_profit
        RankingKind.QUANTITY -> R.string.stats_by_quantity
    },
)

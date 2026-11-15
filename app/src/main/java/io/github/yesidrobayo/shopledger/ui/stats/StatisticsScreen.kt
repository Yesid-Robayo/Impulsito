package io.github.yesidrobayo.shopledger.ui.stats

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.DayPoint
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodKind
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodMetrics
import io.github.yesidrobayo.shopledger.domain.model.report.ReplacementProfit
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxEstimate
import io.github.yesidrobayo.shopledger.domain.usecase.report.SoldAndCollectedView
import io.github.yesidrobayo.shopledger.format.NO_VALUE_TEXT
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppCard
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.CompactChipRow
import io.github.yesidrobayo.shopledger.ui.components.DISCREET_TEXT
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.DiscreetModeButton
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.LedgerLineLayout
import io.github.yesidrobayo.shopledger.ui.components.MoneySize
import io.github.yesidrobayo.shopledger.ui.components.MoneyText
import io.github.yesidrobayo.shopledger.ui.components.MoneyTone
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.OverflowAction
import io.github.yesidrobayo.shopledger.ui.components.OverflowMenu
import io.github.yesidrobayo.shopledger.ui.components.PeriodSelector
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.SegmentedTabs
import io.github.yesidrobayo.shopledger.ui.components.SettingsButton
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.formatAnnotated
import io.github.yesidrobayo.shopledger.ui.components.periodLabel
import io.github.yesidrobayo.shopledger.ui.currency.rememberMoneyDisplay
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/** Desde este ancho, cifras y gráfica a la izquierda, y el detalle a la derecha. */
private val EXPANDED_WIDTH = 840.dp

/** Las pestañas de período, en el orden en que se muestran. */
private val KINDS = listOf(PeriodKind.DAY, PeriodKind.WEEK, PeriodKind.MONTH)

/**
 * `Números`: cuánto se vendió y cuánto se ganó en un día, una semana o un mes.
 *
 * De arriba abajo: las pestañas de período, el selector con sus flechas, la cifra del período con
 * sus ventas y su venta promedio, los chips de la serie, la gráfica por día con el valor del día
 * tocado y su lista, la explicación de la ganancia en cascada y el impuesto estimado. Un día no
 * lleva gráfica: es una cifra. En una ventana ancha, cifras y gráfica a la izquierda y lo demás a
 * la derecha.
 *
 * Es pura: recibe el estado y avisa cada acción. La comparación, los rankings, los desgloses y los
 * avisos sobre las cifras se suman debajo.
 *
 * @param backupBanner el banner del respaldo bajo la barra, que solo aparece si el estado pide
 *   atención.
 */
@Composable
fun StatisticsScreen(
    uiState: StatisticsUiState,
    onAction: (StatisticsAction) -> Unit,
    modifier: Modifier = Modifier,
    backupBanner: @Composable () -> Unit = {},
) {
    Surface(
        modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val expanded = maxWidth >= EXPANDED_WIDTH
            Column(Modifier.fillMaxSize()) {
                AppTopBar(
                    title = stringResource(R.string.nav_numbers),
                    navigation = TopBarNavigation.NONE,
                    onNavigate = {},
                    navigationLabel = null,
                    actions = {
                        DiscreetModeButton()
                        // Solo con cifras se elige un rango: sin datos no hay nada que mirar.
                        if (uiState is StatisticsUiState.Content) {
                            OverflowMenu(
                                listOf(
                                    OverflowAction(stringResource(R.string.stats_choose_dates), {
                                        onAction(StatisticsAction.ChooseRange)
                                    }),
                                    OverflowAction(stringResource(R.string.report_title), {
                                        onAction(StatisticsAction.OpenSummary)
                                    }),
                                ),
                            )
                        }
                        SettingsButton()
                    },
                )
                backupBanner()
                val bottom = bottomSpace()
                val rest = Modifier.weight(1f).fillMaxWidth()
                when (uiState) {
                    StatisticsUiState.Loading ->
                        Box(rest, contentAlignment = Alignment.TopCenter) {
                            DelayedSkeleton { StatisticsSkeleton() }
                        }

                    StatisticsUiState.ReadError ->
                        Message(rest, bottom) {
                            EmptyState(
                                icon = R.drawable.ic_error,
                                title = stringResource(R.string.state_error_read),
                            ) {
                                PrimaryActionButton(
                                    text = stringResource(R.string.action_retry),
                                    onClick = { onAction(StatisticsAction.Retry) },
                                    variant = ActionVariant.TONAL,
                                )
                            }
                        }

                    StatisticsUiState.UnknownZone ->
                        Message(rest, bottom) {
                            EmptyState(
                                icon = R.drawable.ic_bar_chart,
                                title = stringResource(R.string.stats_zone_unknown_title),
                            ) {
                                PrimaryActionButton(
                                    text = stringResource(R.string.stats_zone_unknown_action),
                                    onClick = { onAction(StatisticsAction.ChooseZone) },
                                    variant = ActionVariant.TONAL,
                                )
                            }
                        }

                    StatisticsUiState.NoData ->
                        Message(rest, bottom) {
                            EmptyState(
                                icon = R.drawable.ic_bar_chart,
                                title = stringResource(R.string.empty_stats_title),
                            ) {
                                PrimaryActionButton(
                                    text = stringResource(R.string.empty_stats_action),
                                    onClick = { onAction(StatisticsAction.NewSale) },
                                    variant = ActionVariant.TONAL,
                                )
                            }
                        }

                    is StatisticsUiState.Content -> {
                        if (expanded) {
                            TwoPanes(uiState, onAction, bottom, rest)
                        } else {
                            OnePane(uiState, onAction, bottom, rest)
                        }
                        val weekStartDay = uiState.weekStartDay
                        if (uiState.choosingRange && weekStartDay != null) {
                            RangeSheet(
                                current = uiState.period,
                                today = uiState.today,
                                weekStartDay = weekStartDay,
                                onShow = { from, to ->
                                    onAction(StatisticsAction.ShowRange(from, to))
                                },
                                onDismiss = { onAction(StatisticsAction.DismissRange) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Lo que el contenido deja libre al final: la barra del sistema, la barra inferior y lo que
 * sobresale `Vender`, para que la última fila no quede debajo.
 */
@Composable
private fun bottomSpace(): Dp {
    val sizes = AppTheme.sizes
    val system = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return system + sizes.bottomBar + sizes.sellButtonOverhang + AppTheme.spacing.s16
}

@Composable
private fun Message(modifier: Modifier, bottom: Dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(bottom = bottom),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

/** Una columna de hasta 600 dp, centrada: el teléfono y la ventana mediana. */
@Composable
private fun OnePane(
    content: StatisticsUiState.Content,
    onAction: (StatisticsAction) -> Unit,
    bottom: Dp,
    modifier: Modifier,
) {
    Box(modifier, contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = AppTheme.sizes.maxContentWidth).fillMaxSize(),
            contentPadding = PaddingValues(bottom = bottom),
        ) {
            item(key = OVERVIEW) {
                PaneColumn { Overview(content, onAction, AppTheme.sizes.chartArea) }
            }
            dayList(content)
            item(key = DETAIL) { Column { Detail(content, onAction) } }
        }
    }
}

/** En una ventana ancha: cifras y gráfica a la izquierda, la cascada a la derecha. */
@Composable
private fun TwoPanes(
    content: StatisticsUiState.Content,
    onAction: (StatisticsAction) -> Unit,
    bottom: Dp,
    modifier: Modifier,
) {
    Row(
        modifier.padding(horizontal = AppTheme.spacing.s8),
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s16),
    ) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            contentPadding = PaddingValues(bottom = bottom),
        ) {
            item(key = OVERVIEW) {
                PaneColumn { Overview(content, onAction, AppTheme.sizes.chartAreaExpanded) }
            }
            dayList(content)
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(bottom = bottom),
        ) {
            Detail(content, onAction)
        }
    }
}

@Composable
private fun PaneColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.padding(horizontal = AppTheme.spacing.s16),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s16),
        content = content,
    )
}

/** Las pestañas, el selector, la cifra del período, los chips y la gráfica. */
@Composable
private fun Overview(
    content: StatisticsUiState.Content,
    onAction: (StatisticsAction) -> Unit,
    chartHeight: Dp,
) {
    SegmentedTabs(
        options = KINDS.map { kindLabel(it) },
        selectedIndex = KINDS.indexOf(content.kind),
        onSelect = { onAction(StatisticsAction.SelectKind(KINDS[it])) },
    )
    if (content.kind == null) {
        // Un rango propio no se recorre con flechas: su nombre basta, y se cambia desde el menú.
        Text(
            periodLabel(content.period, content.today),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth().semantics { heading() },
        )
    } else {
        PeriodSelector(
            period = content.period,
            onPrevious = { if (content.canGoPrevious) onAction(StatisticsAction.Previous) },
            onNext = { onAction(StatisticsAction.Next) },
            showNext = content.canGoNext,
            today = content.today,
        )
    }
    PeriodFigure(content)
    CompactChipRow(
        options = content.offeredMetrics,
        label = { chipLabel(it) },
        selected = setOf(content.metric),
        onToggle = { onAction(StatisticsAction.SelectMetric(it)) },
        multiple = false,
    )
    val points = content.series
    if (points != null) {
        // Con la lista, las filas de cada día reemplazan la gráfica: el mismo dato, sin dibujo. Un
        // período todo en cero no se dibuja: el texto de arriba ya dice que no hubo movimientos.
        val anyValue = points.any { it.valueOf(content.metric) != Money.ZERO }
        if (!content.showList && anyValue) {
            AppCard(contentPadding = PaddingValues(AppTheme.spacing.s12)) {
                DailyBarChart(
                    points = points,
                    metric = content.metric,
                    selectedDay = content.selectedDay,
                    onSelectDay = { onAction(StatisticsAction.SelectDay(it)) },
                    summary = chartSummary(content.period, content.today, points, content.metric),
                    height = chartHeight,
                )
            }
            SelectedDay(points, content)
        }
        InlineTextButton(
            text =
                stringResource(
                    if (content.showList) R.string.stats_view_chart else R.string.stats_view_list,
                ),
            onClick = { onAction(StatisticsAction.ToggleList) },
        )
    }
    content.breakdown?.let { Notices(it.notices) }
}

/**
 * La cifra del período en la serie elegida, y debajo cuántas ventas hubo y su venta promedio. Sin
 * movimientos, lo dice, para que los ceros no parezcan un error.
 */
@Composable
private fun PeriodFigure(content: StatisticsUiState.Content) {
    val metrics = content.metrics
    val value = figureOf(content)
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s4)) {
        Text(
            metricLabel(content.metric),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MoneyText(
            amount = value,
            size = MoneySize.LARGE,
            tone = if (value != null && value < Money.ZERO) MoneyTone.LOSS else MoneyTone.DEFAULT,
            sensitive = true,
        )
        VariationLine(content.comparison, content.metric, content.today)
        if (content.metric == ChartMetric.COLLECTED) {
            content.collection?.let { SoldApart(it) }
        }
        if (metrics != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12)) {
                // Cuántas ventas hubo se ve aun en modo discreto: no es dinero.
                val count = metrics.salesCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                Text(
                    pluralStringResource(R.plurals.stats_sales_count, count, count),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AverageTicket(metrics.averageTicket)
            }
            if (!metrics.hasMovements) {
                Text(
                    stringResource(R.string.empty_stats_period_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Lo vendido, lo abonado y lo que queda por cobrar, bajo la cifra de lo cobrado: `Vendido ¤ 10.000`,
 * `En abonos ¤ 4.000` y `Por cobrar ¤ 3.000`. Son cifras aparte, nunca una suma: la venta a crédito
 * cuenta al venderse, y el abono al cobrarse.
 */
@Composable
private fun SoldApart(view: SoldAndCollectedView) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12)) {
        view.totals?.let { totals ->
            PhraseWithAmount(R.string.stats_sold_amount, totals.sold)
            PhraseWithAmount(R.string.stats_on_account_amount, totals.collectedOnAccount)
        }
        // Lo que se debe hoy, de todos los clientes: no cambia con el período.
        view.receivable?.let { PhraseWithAmount(R.string.stats_receivable_amount, it) }
    }
}

/** Una frase con un monto del período, que el modo discreto oculta. */
@Composable
private fun PhraseWithAmount(@StringRes text: Int, amount: Money) {
    val pattern = stringResource(text)
    val (shown, spoken) = moneyInText(amount)
    val description = stringResource(text, spoken)
    Text(
        text = formatAnnotated(pattern, shown),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { contentDescription = description },
    )
}

/**
 * `Venta promedio ¤ 13.622`, con `—` sin ventas. Es un total del período: el modo discreto lo
 * oculta.
 */
@Composable
private fun AverageTicket(ticket: Money?) {
    val pattern = stringResource(R.string.stats_average_ticket)
    val (shown, spoken) = moneyInText(ticket)
    val description = stringResource(R.string.stats_average_ticket, spoken)
    Text(
        text = formatAnnotated(pattern, shown),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { contentDescription = description },
    )
}

/**
 * El valor del día tocado, como texto bajo la gráfica: `vie 14: ¤ 210.000`. Su renglón queda
 * reservado aunque no haya día tocado: al tocar o soltar una barra, lo de abajo no salta.
 */
@Composable
private fun SelectedDay(points: List<DayPoint>, content: StatisticsUiState.Content) {
    val style = AppTheme.amounts.caption
    val line = with(LocalDensity.current) { style.lineHeight.toDp() }
    Box(Modifier.fillMaxWidth().heightIn(min = line)) {
        val point = points.firstOrNull { it.date == content.selectedDay } ?: return@Box
        val day = LocalAppFormats.current.date.weekdayDayMonth(point.date)
        val pattern = stringResource(R.string.stats_bar_value)
        val (shown, spoken) = moneyInText(point.valueOf(content.metric))
        val description = stringResource(R.string.stats_bar_value, day, spoken)
        Text(
            text = formatAnnotated(pattern, day, shown),
            style = style,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { contentDescription = description },
        )
    }
}

/** Los mismos días de la gráfica, como filas: el camino accesible a cada dato. */
private fun LazyListScope.dayList(content: StatisticsUiState.Content) {
    val points = content.series ?: return
    if (!content.showList) return
    items(points, key = { "day-${it.date}" }) { point ->
        DayRow(point, content.metric)
    }
}

@Composable
private fun DayRow(point: DayPoint, metric: ChartMetric) {
    val value = point.valueOf(metric)
    LedgerLineLayout(
        gap = AppTheme.spacing.s16,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = AppTheme.sizes.listRowSingle)
                .padding(horizontal = AppTheme.spacing.s16)
                .semantics(mergeDescendants = true) {},
        leading = {
            Text(
                LocalAppFormats.current.date.weekdayDayMonth(point.date),
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        trailing = {
            MoneyText(
                value,
                MoneySize.ROW,
                tone = if (value < Money.ZERO) MoneyTone.LOSS else MoneyTone.DEFAULT,
                sensitive = true,
            )
        },
    )
}

/**
 * La explicación de la ganancia en cascada y el impuesto estimado. Los encabezados llevan su propio
 * margen; las filas, el de la pantalla.
 */
@Composable
private fun ColumnScope.Detail(
    content: StatisticsUiState.Content,
    onAction: (StatisticsAction) -> Unit,
) {
    val metrics = content.metrics
    val rows = Modifier.padding(horizontal = AppTheme.spacing.s16)
    SectionHeader(stringResource(R.string.stats_how_calculated))
    if (metrics == null) {
        Text(NO_VALUE_TEXT, style = MaterialTheme.typography.bodyLarge, modifier = rows)
    } else {
        Cascade(metrics, rows)
    }
    val breakdown = content.breakdown
    if (breakdown != null) {
        VoidedRow(breakdown.notices, rows)
        breakdown.top?.let { top ->
            TopSold(
                top = top,
                ranking = content.ranking,
                units = content.units,
                expanded = content.expandedProducts,
                onSelect = { onAction(StatisticsAction.SelectRanking(it)) },
                onToggle = { onAction(StatisticsAction.ToggleVariants(it)) },
                modifier = rows,
            )
        }
        ByPaymentMethod(breakdown.methods, rows)
        breakdown.expenses?.let { ByExpenseCategory(it, rows) }
    }
    content.replacement?.let { ReplacementTool(it, rows) }
    val tax = content.tax
    if (tax != null) EstimatedTax(tax, rows)
    content.otherIncome?.let { OtherIncome(it, rows) }
}

/**
 * De las ventas a la ganancia final, en palabras simples. Daños y pérdidas pasa a `Sobrante` si
 * sobró más de lo que se perdió, y las comisiones solo aparecen si hubo alguna.
 */
@Composable
private fun Cascade(metrics: PeriodMetrics, modifier: Modifier) {
    val profit = metrics.profit
    Column(modifier) {
        CascadeRow(stringResource(R.string.stats_metric_sales), profit.netSales)
        CascadeRow(stringResource(R.string.term_cogs), profit.cogs, subtract = true)
        CascadeRow(stringResource(R.string.term_sales_profit), profit.grossProfit, strong = true)
        MarginRow(profit.grossMargin)
        CascadeRow(
            stringResource(R.string.stats_row_expenses),
            profit.recordedExpenses,
            subtract = true,
        )
        if (profit.paymentCosts != Money.ZERO) {
            CascadeRow(
                stringResource(R.string.stats_row_fees),
                profit.paymentCosts,
                subtract = true,
            )
        }
        if (profit.netShrinkage < Money.ZERO) {
            // Un sobrante reduce los gastos: suma.
            CascadeRow(stringResource(R.string.term_surplus), -profit.netShrinkage)
        } else if (profit.netShrinkage > Money.ZERO) {
            CascadeRow(
                stringResource(R.string.term_damage_loss),
                profit.netShrinkage,
                subtract = true,
            )
        }
        HorizontalDivider(
            Modifier.padding(vertical = AppTheme.spacing.s4),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        CascadeRow(stringResource(R.string.term_final_profit), profit.netProfit, strong = true)
    }
}

@Composable
private fun CascadeRow(
    label: String,
    amount: Money,
    subtract: Boolean = false,
    strong: Boolean = false,
) {
    // Lo que se resta se muestra con su signo: la cascada se lee de arriba abajo.
    val shown = if (subtract) -amount else amount
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
                shown,
                if (strong) MoneySize.TILE else MoneySize.ROW,
                tone = if (strong && shown < Money.ZERO) MoneyTone.LOSS else MoneyTone.DEFAULT,
                sensitive = true,
            )
        },
    )
}

/** El margen, el único indicador de rentabilidad: `—` si no hubo ventas. */
@Composable
private fun MarginRow(margin: Margin) {
    val discreet = LocalDiscreetMode.current
    val text =
        when (margin) {
            is Margin.Defined ->
                if (discreet) {
                    DISCREET_TEXT
                } else {
                    LocalAppFormats.current.percent.format(
                        margin.value,
                    )
                }

            Margin.NoData, Margin.OutOfRange -> NO_VALUE_TEXT
        }
    val spoken =
        when {
            margin !is Margin.Defined -> stringResource(R.string.a11y_no_value)
            discreet -> stringResource(R.string.a11y_discreet_hidden)
            else -> text
        }
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
        trailing = { Text(text, style = AppTheme.amounts.row) },
    )
}

/**
 * La ganancia si se repone: las ventas, menos lo que costaría reponer lo vendido, rotulada aparte de
 * la ganancia en ventas, que sigue usando el costo promedio. Una línea explica la diferencia, y si
 * algo se vendió sin costo de reposición ni costo, un aviso dice que la cifra se queda corta. Las
 * cifras se ocultan en modo discreto.
 */
@Composable
private fun ReplacementTool(replacement: ReplacementProfit, modifier: Modifier) {
    SectionHeader(stringResource(R.string.stats_tool_replacement))
    Column(modifier, verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8)) {
        Column {
            CascadeRow(stringResource(R.string.stats_metric_sales), replacement.netSales)
            CascadeRow(
                stringResource(R.string.product_replacement_cost),
                replacement.replacementCogs,
                subtract = true,
            )
            CascadeRow(
                stringResource(R.string.stats_tool_replacement),
                replacement.replacementProfit,
                strong = true,
            )
        }
        Text(
            stringResource(R.string.stats_replacement_help),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val missing = replacement.linesWithoutReplacement
        if (missing > 0L) {
            val count = missing.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            NoticeCard(
                pluralStringResource(R.plurals.stats_replacement_missing, count, count),
                NoticeVariant.INFO,
            )
        }
    }
}

/** Lo cobrado y lo pagado de impuesto, rotulado como estimación que no es una declaración. */
@Composable
private fun EstimatedTax(tax: TaxEstimate, modifier: Modifier) {
    SectionHeader(stringResource(R.string.stats_tool_estimated_tax))
    Column(modifier, verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8)) {
        Column {
            CascadeRow(stringResource(R.string.stats_tax_collected), tax.collected)
            CascadeRow(stringResource(R.string.stats_tax_paid), tax.paid)
        }
        NoticeCard(stringResource(R.string.tax_estimate_notice), NoticeVariant.INFO)
    }
}

/**
 * Los otros ingresos del período: dinero que no vino de una venta. Van aparte, debajo de todo, porque
 * no entran en la ganancia final.
 */
@Composable
private fun OtherIncome(amount: Money, modifier: Modifier) {
    SectionHeader(stringResource(R.string.stats_other_income))
    Column(modifier, verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8)) {
        CascadeRow(stringResource(R.string.stats_other_income), amount)
        Text(
            stringResource(R.string.money_other_income_help),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** El esqueleto: la cifra, la gráfica y cuatro filas, solo si la espera se nota. */
@Composable
private fun StatisticsSkeleton() {
    Column(
        Modifier
            .widthIn(max = AppTheme.sizes.maxContentWidth)
            .fillMaxWidth()
            .padding(AppTheme.spacing.s16),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s16),
    ) {
        MoneyText(null, MoneySize.LARGE, loading = true)
        SkeletonBlock(Modifier.fillMaxWidth().height(AppTheme.sizes.chartArea))
        repeat(SKELETON_ROWS) {
            SkeletonBlock(Modifier.width(AppTheme.sizes.sideSheet).height(AppTheme.spacing.s16))
        }
    }
}

/** La cifra grande de [metric]: lo cobrado sale aparte, del resumen de vendido y cobrado. */
private fun figureOf(content: StatisticsUiState.Content): Money? = when (content.metric) {
    ChartMetric.SALES -> content.metrics?.profit?.netSales
    ChartMetric.COLLECTED -> content.collection?.totals?.collected
    ChartMetric.PROFIT -> content.metrics?.profit?.grossProfit
}

@Composable
private fun kindLabel(kind: PeriodKind): String = stringResource(
    when (kind) {
        PeriodKind.DAY -> R.string.period_day
        PeriodKind.WEEK -> R.string.period_week
        PeriodKind.MONTH -> R.string.period_month
    },
)

/** El chip de cada serie: corto, como en la gráfica. */
@Composable
private fun chipLabel(metric: ChartMetric): String = stringResource(
    when (metric) {
        ChartMetric.SALES -> R.string.stats_metric_sales
        ChartMetric.COLLECTED -> R.string.stats_metric_collected
        ChartMetric.PROFIT -> R.string.stats_metric_profit
    },
)

/** La etiqueta de la cifra grande: la ganancia se nombra entera, para no confundirla con la final. */
@Composable
private fun metricLabel(metric: ChartMetric): String = stringResource(
    when (metric) {
        ChartMetric.SALES -> R.string.stats_metric_sales
        ChartMetric.COLLECTED -> R.string.stats_metric_collected
        ChartMetric.PROFIT -> R.string.term_sales_profit
    },
)

/**
 * Un monto dentro de una frase: lo que se dibuja, con el símbolo en su corrida, y lo que lee
 * TalkBack, con el nombre de la moneda. En modo discreto, oculto en los dos.
 */
@Composable
internal fun moneyInText(amount: Money?): Pair<CharSequence, String> {
    val discreet = LocalDiscreetMode.current
    val display = amount?.let { rememberMoneyDisplay(it, LocalAppFormats.current.money) }
    return when {
        discreet -> DISCREET_TEXT to stringResource(R.string.a11y_discreet_hidden)
        display == null -> NO_VALUE_TEXT to stringResource(R.string.a11y_no_value)
        else -> display.text to display.description
    }
}

/**
 * Lo que TalkBack dice de la gráfica: la serie, el rango, el total y los días mayor y menor. En modo
 * discreto, sin montos.
 */
@Composable
private fun chartSummary(
    period: ReportPeriod,
    today: BusinessDate,
    points: List<DayPoint>,
    metric: ChartMetric,
): String {
    val date = LocalAppFormats.current.date
    val values = points.map { it.valueOf(metric) }
    val highest = points.maxByOrNull { it.valueOf(metric) }
    val lowest = points.minByOrNull { it.valueOf(metric) }
    val total = values.fold(Money.ZERO) { sum, value -> sum + value }
    return stringResource(
        when (metric) {
            ChartMetric.SALES -> R.string.stats_chart_summary_sales
            ChartMetric.COLLECTED -> R.string.stats_chart_summary_collected
            ChartMetric.PROFIT -> R.string.stats_chart_summary_profit
        },
        periodLabel(period, today),
        moneyInText(total).second,
        highest?.let { date.weekdayDayMonth(it.date) }.orEmpty(),
        moneyInText(highest?.valueOf(metric)).second,
        lowest?.let { date.weekdayDayMonth(it.date) }.orEmpty(),
        moneyInText(lowest?.valueOf(metric)).second,
    )
}

private const val OVERVIEW = "overview"
private const val DETAIL = "detail"
private const val SKELETON_ROWS = 4

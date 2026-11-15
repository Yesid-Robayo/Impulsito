package io.github.yesidrobayo.shopledger.ui.report

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.report.ManagementSummary
import io.github.yesidrobayo.shopledger.domain.model.report.SummaryContent
import io.github.yesidrobayo.shopledger.domain.rule.report.ManagementSummaryRules
import io.github.yesidrobayo.shopledger.format.DataSizeFormatter
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.AppCard
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.OverflowAction
import io.github.yesidrobayo.shopledger.ui.components.OverflowMenu
import io.github.yesidrobayo.shopledger.ui.components.PeriodSelector
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.RankingRow
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.SettingsRow
import io.github.yesidrobayo.shopledger.ui.components.SettingsRowAction
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.StatTile
import io.github.yesidrobayo.shopledger.ui.components.StatTileVariant
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.keepsMessagesAbove
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.help.HelpTopic
import io.github.yesidrobayo.shopledger.ui.help.ScreenHelpLink
import io.github.yesidrobayo.shopledger.ui.stats.RangeSheet
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme

/**
 * `Resumen de gestión`: lo que suele pedir quien lleva la contabilidad, en un `.zip` por período
 * con un PDF de resumen y CSV. Es material de apoyo, y lo dice fijo abajo: no es un libro contable
 * ni una declaración.
 *
 * - Arriba, el período por mes con sus flechas, y en el menú `Elegir fechas`.
 * - La tarjeta de cifras: ventas, impuesto estimado si el negocio lo cobra, gastos y ganancias, y
 *   lo cobrado por método, los diez primeros. Todas las cifras se ocultan en modo discreto.
 * - `Qué incluye`: el PDF siempre, y un interruptor por grupo de archivos.
 * - El aviso de que el archivo lleva ventas, costos y ganancias sin cifrar.
 * - Abajo, fija, `Generar resumen`. Al terminar abre la hoja `Resumen listo`.
 * - Desde `Medium`, cifras a la izquierda y opciones a la derecha, con la acción bajo ellas.
 *
 * Todo se genera en el teléfono. Es pura: recibe el estado y avisa cada acción.
 */
@Composable
fun ManagementSummaryScreen(
    uiState: ManagementSummaryUiState,
    onAction: (SummaryAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= AppTheme.sizes.maxContentWidth
            Column(Modifier.fillMaxSize()) {
                AppTopBar(
                    title = stringResource(R.string.report_title),
                    navigation = TopBarNavigation.BACK,
                    onNavigate = { onAction(SummaryAction.Back) },
                    navigationLabel = stringResource(R.string.a11y_back),
                    actions = {
                        ScreenHelpLink(HelpTopic.NOT_ACCOUNTING)
                        // Sin calendario no hay fechas que elegir.
                        if (uiState.period != null) {
                            OverflowMenu(
                                listOf(
                                    OverflowAction(stringResource(R.string.report_choose_dates), {
                                        onAction(SummaryAction.ChooseRange)
                                    }),
                                ),
                            )
                        }
                    },
                )
                if (wide) {
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        Column(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState())
                                .padding(bottom = AppTheme.spacing.s16),
                        ) { Figures(uiState, onAction) }
                        VerticalDivider(
                            thickness = AppTheme.sizes.hairline,
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            Column(
                                Modifier
                                    .weight(1f)
                                    .verticalScroll(rememberScrollState()),
                            ) { Options(uiState, onAction) }
                            BottomAction(uiState, onAction)
                        }
                    }
                } else {
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = AppTheme.spacing.s16),
                    ) {
                        Figures(uiState, onAction)
                        Options(uiState, onAction)
                    }
                    BottomAction(uiState, onAction)
                }
            }
        }
    }
    val today = uiState.today
    val period = uiState.period
    val weekStartDay = uiState.weekStartDay
    if (uiState.choosingRange && today != null && period != null && weekStartDay != null) {
        RangeSheet(
            current = period,
            today = today,
            weekStartDay = weekStartDay,
            onShow = { from, to -> onAction(SummaryAction.ShowRange(from, to)) },
            onDismiss = { onAction(SummaryAction.DismissRange) },
        )
    }
    (uiState.generation as? SummaryGeneration.Ready)?.let { ReadySheet(it, onAction) }
}

/** El período, sus avisos y la tarjeta de cifras, o el estado que la reemplaza. */
@Composable
private fun ColumnScope.Figures(
    uiState: ManagementSummaryUiState,
    onAction: (SummaryAction) -> Unit,
) {
    val spacing = AppTheme.spacing
    val period = uiState.period
    val today = uiState.today
    if (period != null && today != null) {
        PeriodSelector(
            period = period,
            onPrevious = { onAction(SummaryAction.PreviousPeriod) },
            onNext = { onAction(SummaryAction.NextPeriod) },
            showNext = uiState.canGoNext,
            today = today,
            modifier = Modifier.padding(horizontal = spacing.s16, vertical = spacing.s8),
        )
    }
    when (val content = uiState.content) {
        SummaryScreenContent.Loading -> DelayedSkeleton { FiguresSkeleton() }

        SummaryScreenContent.ReadError ->
            EmptyState(
                icon = R.drawable.ic_error,
                title = stringResource(R.string.state_error_read),
            ) {
                PrimaryActionButton(
                    text = stringResource(R.string.action_retry),
                    onClick = { onAction(SummaryAction.Retry) },
                    variant = ActionVariant.TONAL,
                )
            }

        SummaryScreenContent.UnknownZone ->
            EmptyState(
                icon = R.drawable.ic_error,
                title = stringResource(R.string.stats_zone_unknown_title),
            ) {
                PrimaryActionButton(
                    text = stringResource(R.string.stats_zone_unknown_action),
                    onClick = { onAction(SummaryAction.ChooseZone) },
                    variant = ActionVariant.TONAL,
                )
            }

        is SummaryScreenContent.Ready -> {
            val summary = content.summary
            Column(
                Modifier.padding(horizontal = spacing.s16),
                verticalArrangement = Arrangement.spacedBy(spacing.s12),
            ) {
                if (period != null && today != null &&
                    ManagementSummaryRules.inProgress(period, today)
                ) {
                    NoticeCard(
                        stringResource(R.string.report_in_progress_notice),
                        NoticeVariant.INFO,
                    )
                }
                if (summary.salesWithoutCost > 0) {
                    val count = summary.salesWithoutCost.toInt()
                    NoticeCard(
                        pluralStringResource(R.plurals.notice_sales_no_cost, count, count),
                        NoticeVariant.WARNING,
                    )
                }
            }
            if (summary.hasMovements) {
                SummaryCard(summary)
            } else {
                EmptyState(
                    icon = R.drawable.ic_bar_chart,
                    title = stringResource(R.string.report_empty_title),
                    body = stringResource(R.string.report_empty_reason),
                )
            }
        }
    }
}

/** Las cifras del período, como en `Números`, y lo cobrado por método. */
@Composable
private fun SummaryCard(summary: ManagementSummary) {
    val spacing = AppTheme.spacing
    val profit = summary.profit
    AppCard(Modifier.padding(horizontal = spacing.s16, vertical = spacing.s12)) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.s12)) {
            StatTile(
                stringResource(R.string.report_sales),
                profit.netSales,
                StatTileVariant.PLAIN,
                sensitive = true,
            )
            summary.tax?.let { tax ->
                StatTile(
                    stringResource(R.string.report_tax_estimated),
                    tax.collected,
                    StatTileVariant.PLAIN,
                    sensitive = true,
                )
            }
            StatTile(
                stringResource(R.string.report_expenses),
                profit.recordedExpenses,
                StatTileVariant.PLAIN,
                sensitive = true,
            )
            StatTile(
                stringResource(R.string.report_sales_profit),
                profit.grossProfit,
                StatTileVariant.PLAIN,
                sensitive = true,
            )
            StatTile(
                stringResource(R.string.report_final_profit),
                profit.netProfit,
                StatTileVariant.PLAIN,
                sensitive = true,
            )
        }
    }
    val methods = ManagementSummaryRules.screenMethods(summary.methods)
    val leader = methods.firstOrNull()?.amount ?: return
    SectionHeader(stringResource(R.string.report_by_method))
    methods.forEach { method ->
        RankingRow(method.name, method.amount, leader, sensitive = true)
    }
}

/** Lo que se incluye, el aviso de datos sin cifrar y la nota de alcance. */
@Composable
private fun Options(uiState: ManagementSummaryUiState, onAction: (SummaryAction) -> Unit) {
    val summary = uiState.summary ?: return
    val spacing = AppTheme.spacing
    SectionHeader(stringResource(R.string.report_includes))
    SettingsRow(
        title = stringResource(R.string.report_include_pdf),
        action = SettingsRowAction.Value {
            Text(
                stringResource(R.string.report_include_pdf_value),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
    summary.contents.forEach { content ->
        val on = content in uiState.chosen
        SettingsRow(
            title = stringResource(includeLabel(content)),
            action = SettingsRowAction.Toggle(on) { checked ->
                onAction(SummaryAction.Include(content, checked))
            },
        )
    }
    Column(
        Modifier.padding(horizontal = spacing.s16, vertical = spacing.s8),
        verticalArrangement = Arrangement.spacedBy(spacing.s12),
    ) {
        NoticeCard(stringResource(R.string.report_sensitive_notice), NoticeVariant.WARNING)
        Text(
            stringResource(R.string.report_support_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * La barra fija: el error de la última vez, si hubo, la razón por la que no se puede generar, y
 * `Generar resumen`, que tras un error dice `Reintentar`.
 */
@Composable
private fun BottomAction(uiState: ManagementSummaryUiState, onAction: (SummaryAction) -> Unit) {
    val spacing = AppTheme.spacing
    val generation = uiState.generation
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(spacing.s16)
            .keepsMessagesAbove(),
        verticalArrangement = Arrangement.spacedBy(spacing.s8),
    ) {
        when (generation) {
            SummaryGeneration.Failed ->
                NoticeCard(stringResource(R.string.report_error_generate), NoticeVariant.ERROR)

            SummaryGeneration.NoSpace ->
                NoticeCard(stringResource(R.string.report_error_space), NoticeVariant.ERROR)

            SummaryGeneration.Idle,
            SummaryGeneration.Working,
            is SummaryGeneration.Ready,
            -> Unit
        }
        if (uiState.summary?.hasMovements == false) {
            Text(
                stringResource(R.string.report_empty_reason),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val failed =
            generation == SummaryGeneration.Failed || generation == SummaryGeneration.NoSpace
        PrimaryActionButton(
            text = stringResource(
                if (failed) R.string.action_retry else R.string.report_generate_action,
            ),
            onClick = { onAction(SummaryAction.Generate) },
            variant = ActionVariant.PRIMARY,
            enabled = uiState.summary?.hasMovements == true,
            state = if (generation == SummaryGeneration.Working) {
                ActionState.WORKING
            } else {
                ActionState.IDLE
            },
        )
    }
}

/** La hoja `Resumen listo`: nombre y tamaño del archivo, y compartirlo o guardarlo. */
@Composable
private fun ReadySheet(ready: SummaryGeneration.Ready, onAction: (SummaryAction) -> Unit) {
    val formats = LocalAppFormats.current
    val size = DataSizeFormatter(formats.date.formatLocale, formats.uiLocale)
        .megabytes(ready.sizeBytes)
    AppBottomSheet(
        onDismissRequest = { onAction(SummaryAction.DismissResult) },
        title = stringResource(R.string.report_ready_title),
        action = {
            Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s8)) {
                PrimaryActionButton(
                    text = stringResource(R.string.action_share),
                    onClick = { onAction(SummaryAction.Share) },
                    variant = ActionVariant.PRIMARY,
                    modifier = Modifier.fillMaxWidth().keepsMessagesAbove(),
                )
                PrimaryActionButton(
                    text = stringResource(R.string.report_save_folder_action),
                    onClick = { onAction(SummaryAction.SaveToFolder) },
                    variant = ActionVariant.TONAL,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    ) {
        Text(
            stringResource(R.string.report_ready_file, ready.fileName, size),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** El esqueleto de la tarjeta, mientras llegan las cifras la primera vez. */
@Composable
private fun FiguresSkeleton() {
    val spacing = AppTheme.spacing
    Column(
        Modifier.padding(spacing.s16),
        verticalArrangement = Arrangement.spacedBy(spacing.s12),
    ) {
        repeat(SKELETON_ROWS) {
            SkeletonBlock(Modifier.fillMaxWidth().height(SKELETON_ROW_HEIGHT))
        }
    }
}

private fun includeLabel(content: SummaryContent): Int = when (content) {
    SummaryContent.SALES_AND_PAYMENTS -> R.string.report_include_sales
    SummaryContent.PURCHASES_AND_EXPENSES -> R.string.report_include_purchases
    SummaryContent.CLOSING_INVENTORY -> R.string.report_include_inventory
    SummaryContent.CUSTOMERS_AND_SUPPLIERS -> R.string.report_include_parties
}

private const val SKELETON_ROWS = 5
private val SKELETON_ROW_HEIGHT = 40.dp

package io.github.yesidrobayo.shopledger.ui.priceindex

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.PriceIndexEntry
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.rule.report.RealTermsRules
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.report.DeletePriceIndexMonthUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.report.ObservePriceIndexUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.report.SavePriceIndexMonthUseCase
import io.github.yesidrobayo.shopledger.format.IcuTextNormalizer
import io.github.yesidrobayo.shopledger.navigation.AppNavKey
import io.github.yesidrobayo.shopledger.navigation.AppNavigator
import io.github.yesidrobayo.shopledger.navigation.LocalAppMessages
import io.github.yesidrobayo.shopledger.navigation.PlaceholderRoute
import io.github.yesidrobayo.shopledger.navigation.ScreenModel
import io.github.yesidrobayo.shopledger.navigation.rememberAppViewModel
import io.github.yesidrobayo.shopledger.ui.components.ActionState
import io.github.yesidrobayo.shopledger.ui.components.ActionVariant
import io.github.yesidrobayo.shopledger.ui.components.AppBottomSheet
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarVisuals
import io.github.yesidrobayo.shopledger.ui.components.AppTextField
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.ConfirmDialog
import io.github.yesidrobayo.shopledger.ui.components.ConfirmVariant
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.FieldError
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.OverflowAction
import io.github.yesidrobayo.shopledger.ui.components.OverflowMenu
import io.github.yesidrobayo.shopledger.ui.components.PercentField
import io.github.yesidrobayo.shopledger.ui.components.PeriodSelector
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.SnackbarVariant
import io.github.yesidrobayo.shopledger.ui.components.TonalFab
import io.github.yesidrobayo.shopledger.ui.components.TonalFabDefaults
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.saveErrorText
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import kotlinx.coroutines.flow.map
import kotlinx.datetime.YearMonth

/**
 * `Índice de precios` en la navegación. La hoja y el diálogo se cierran con atrás antes que la
 * pantalla. `Ver comparación en Números` deja pedida la comparación real y abre `Números`.
 */
@Composable
fun PriceIndexRoute(navigator: AppNavigator) {
    val symbols = LocalAppFormats.current.percent.inputSymbols
    val model = rememberAppViewModel(key = "price-index") { dependencies ->
        val ports = dependencies.ports
        val clock = dependencies.container.clock
        val observe = ObservePriceIndexUseCase(ports.priceIndex)
        val save =
            SavePriceIndexMonthUseCase(ports.transactions, ports.settings, ports.priceIndex, clock)
        val delete = DeletePriceIndexMonthUseCase(ports.transactions, ports.priceIndex)
        PriceIndexViewModel(
            observe = { observe() },
            currentMonth =
                BusinessCalendarSource(ports.settings).observe().map { result ->
                    when (result) {
                        is BusinessCalendarResult.Ready -> {
                            val today = result.calendar.stamp(clock.now()).businessDate.date
                            YearMonth(today.year, today.month)
                        }

                        is BusinessCalendarResult.UnknownZone -> null
                    }
                },
            save = { month, change, note, editing -> save(month, change, note, editing) },
            delete = { delete(it) },
            symbols = symbols,
        )
    }
    when (model) {
        ScreenModel.Opening -> PriceIndexScreen(PriceIndexUiState.Loading) {}

        ScreenModel.Unavailable -> PlaceholderRoute(AppNavKey.PriceIndex, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val ready = uiState as? PriceIndexUiState.Ready
            BackHandler(enabled = ready?.sheet != null || ready?.confirmingDelete != null) {
                viewModel.onAction(PriceIndexAction.Dismiss)
            }
            ShowMessage(ready?.message) { viewModel.onAction(PriceIndexAction.MessageShown) }
            PriceIndexScreen(uiState) { action ->
                when (action) {
                    PriceIndexAction.Back -> navigator.back()

                    PriceIndexAction.Import -> navigator.open(AppNavKey.ImportData)

                    PriceIndexAction.Compare -> {
                        navigator.realComparison.request()
                        navigator.open(AppNavKey.Statistics)
                    }

                    else -> viewModel.onAction(action)
                }
            }
        }
    }
}

/**
 * `Índice de precios`, sin ViewModel: el texto que explica para qué sirve, el aviso de los meses
 * que faltan, la lista del más reciente al más antiguo y `Ver comparación en Números`. La acción
 * es `Agregar mes`. Los porcentajes del índice no son cifras del negocio: el modo discreto no los
 * oculta.
 */
@Composable
fun PriceIndexScreen(
    uiState: PriceIndexUiState,
    modifier: Modifier = Modifier,
    onAction: (PriceIndexAction) -> Unit,
) {
    val ready = uiState as? PriceIndexUiState.Ready
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                AppTopBar(
                    title = stringResource(R.string.index_title),
                    navigation = TopBarNavigation.BACK,
                    onNavigate = { onAction(PriceIndexAction.Back) },
                    navigationLabel = stringResource(R.string.a11y_back),
                    actions = {
                        OverflowMenu(
                            listOf(
                                OverflowAction(stringResource(R.string.index_import_action), {
                                    onAction(PriceIndexAction.Import)
                                }),
                            ),
                        )
                    },
                )
                Column(
                    Modifier
                        .weight(1f)
                        .align(Alignment.CenterHorizontally)
                        .widthIn(max = AppTheme.sizes.maxContentWidth)
                        .fillMaxWidth(),
                ) {
                    when (uiState) {
                        PriceIndexUiState.Loading -> LoadingRows()

                        PriceIndexUiState.ReadFailed -> ReadFailed(onAction)

                        is PriceIndexUiState.Ready ->
                            if (uiState.entries.isEmpty()) {
                                Empty(uiState, onAction)
                            } else {
                                IndexList(uiState, onAction)
                            }
                    }
                }
            }
            if (ready != null && ready.entries.isNotEmpty() && ready.currentMonth != null) {
                TonalFab(
                    text = stringResource(R.string.index_add_action),
                    onClick = { onAction(PriceIndexAction.Add) },
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
            }
        }
    }
    if (ready != null) {
        ready.sheet?.let { MonthSheet(it, ready.currentMonth, onAction) }
        ready.confirmingDelete?.let { ConfirmDelete(it, onAction) }
    }
}

@Composable
private fun LoadingRows() {
    DelayedSkeleton {
        Column(
            Modifier.padding(AppTheme.spacing.s16),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s12),
        ) {
            repeat(SKELETON_ROWS) {
                SkeletonBlock(Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.listRow))
            }
        }
    }
}

@Composable
private fun ReadFailed(onAction: (PriceIndexAction) -> Unit) {
    EmptyState(icon = R.drawable.ic_error, title = stringResource(R.string.state_error_read)) {
        PrimaryActionButton(
            text = stringResource(R.string.action_retry),
            onClick = { onAction(PriceIndexAction.Retry) },
            variant = ActionVariant.TONAL,
        )
    }
}

/** Sin datos: para qué sirve y `Agregar mes`, si se sabe qué mes es hoy. */
@Composable
private fun Empty(state: PriceIndexUiState.Ready, onAction: (PriceIndexAction) -> Unit) {
    EmptyState(
        icon = R.drawable.ic_trending_up,
        title = stringResource(R.string.index_empty_title),
        body = stringResource(R.string.index_empty_body),
    ) {
        if (state.currentMonth != null) {
            PrimaryActionButton(
                text = stringResource(R.string.index_add_action),
                onClick = { onAction(PriceIndexAction.Add) },
                variant = ActionVariant.PRIMARY,
            )
        }
    }
}

@Composable
private fun IndexList(state: PriceIndexUiState.Ready, onAction: (PriceIndexAction) -> Unit) {
    val spacing = AppTheme.spacing
    val rows = Modifier.padding(horizontal = spacing.s16)
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = TonalFabDefaults.listEndPadding),
        verticalArrangement = Arrangement.spacedBy(spacing.s8),
    ) {
        item(key = "intro") {
            Text(
                stringResource(R.string.index_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = rows.padding(top = spacing.s8),
            )
        }
        val first = state.missing.firstOrNull()
        if (first != null) {
            item(key = "missing") {
                NoticeCard(
                    text = missingText(state.missing),
                    variant = NoticeVariant.WARNING,
                    modifier = rows,
                    actionLabel = stringResource(R.string.index_add_action),
                    onAction = { onAction(PriceIndexAction.AddMonth(first)) },
                )
            }
        }
        items(state.entries, key = { it.month.toString() }) { entry ->
            EntryRow(entry, rows) { onAction(PriceIndexAction.Edit(entry.month)) }
        }
        item(key = "compare") {
            InlineTextButton(
                text = stringResource(R.string.index_compare_action),
                onClick = { onAction(PriceIndexAction.Compare) },
                modifier = rows,
            )
        }
    }
}

/** `Faltan 2 meses: septiembre 2026, octubre 2026`, con los meses en el formato de la región. */
@Composable
internal fun missingText(months: List<YearMonth>): String {
    val date = LocalAppFormats.current.date
    val count = months.size
    return pluralStringResource(
        R.plurals.index_missing,
        count,
        LocalAppFormats.current.quantity.formatCount(count.toLong()),
        months.joinToString(", ") { date.monthYear(it) },
    )
}

/** Un mes: su nombre y su fuente a la izquierda, y el cambio con signo a la derecha. */
@Composable
private fun EntryRow(entry: PriceIndexEntry, modifier: Modifier, onClick: () -> Unit) {
    val formats = LocalAppFormats.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = AppTheme.sizes.listRow)
            .clickable(role = Role.Button, onClick = onClick)
            .then(modifier)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                formats.date.monthYear(entry.month),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            entry.sourceNote?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // TalkBack dice hacia dónde fue el cambio, `sube 1,9 %`: el signo puede no leerse.
        val spoken = changeSpoken(entry.change)
        Text(
            changeText(entry.change),
            style = AppTheme.amounts.row,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false,
            modifier = if (spoken == null) {
                Modifier
            } else {
                Modifier.semantics { contentDescription = spoken }
            },
        )
    }
}

/**
 * Un cambio de mes con todos sus decimales y siempre con signo: `+1,9 %`, `−0,25 %`. Sin cambio no
 * lleva signo.
 */
@Composable
internal fun changeText(change: BasisPoints): String {
    val percent = LocalAppFormats.current.percent
    return if (change > BasisPoints.ZERO) {
        // Un positivo se formatea como su negativo: así el signo va donde lo pone la región.
        percent.formatRate(BasisPoints(-change.bp)).replace(IcuTextNormalizer.MINUS_SIGN, '+')
    } else {
        percent.formatRate(change)
    }
}

/**
 * Lo que TalkBack dice de un cambio de mes, con su dirección en palabras: `sube 1,9 %`,
 * `baja 0,25 %`. Sin cambio no hay dirección: `null`, y se lee lo que se ve.
 */
@Composable
internal fun changeSpoken(change: BasisPoints): String? {
    val percent = LocalAppFormats.current.percent
    return when {
        change > BasisPoints.ZERO ->
            stringResource(R.string.a11y_change_rises, percent.formatRate(change))

        change < BasisPoints.ZERO ->
            stringResource(R.string.a11y_change_falls, percent.formatRate(BasisPoints(-change.bp)))

        else -> null
    }
}

/**
 * La hoja de un mes: el mes, que en uno nuevo se elige con flechas hasta el de hoy, el cambio con
 * signo y hasta dos decimales, y la fuente. En un mes con valor, también `Eliminar mes`. Una falla
 * se dice en la hoja y lo escrito se conserva.
 */
@Composable
private fun MonthSheet(
    sheet: IndexSheet,
    currentMonth: YearMonth?,
    onAction: (PriceIndexAction) -> Unit,
) {
    val formats = LocalAppFormats.current
    AppBottomSheet(
        onDismissRequest = { onAction(PriceIndexAction.Dismiss) },
        title =
            if (sheet.editing) {
                formats.date.monthYear(sheet.month)
            } else {
                stringResource(R.string.index_add_action)
            },
        action = {
            PrimaryActionButton(
                text = stringResource(R.string.index_save_action),
                onClick = { onAction(PriceIndexAction.Save) },
                variant = ActionVariant.PRIMARY,
                state = if (sheet.saving) ActionState.WORKING else ActionState.IDLE,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        if (!sheet.editing) {
            Text(
                stringResource(R.string.index_field_month),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val today = BusinessDate((currentMonth ?: sheet.month).firstDay)
            PeriodSelector(
                period = ReportPeriod.Month(sheet.month),
                onPrevious = { onAction(PriceIndexAction.PreviousMonth) },
                onNext = { onAction(PriceIndexAction.NextMonth) },
                showNext = currentMonth != null && sheet.month < currentMonth,
                today = today,
            )
            // Como el error de un campo: con icono, y anunciado al aparecer.
            monthError(sheet)?.let {
                Box(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) { FieldError(it) }
            }
        }
        PercentField(
            value = sheet.changeText,
            onValueChange = { onAction(PriceIndexAction.ChangeText(it)) },
            label = stringResource(R.string.index_field_change),
            allowNegative = true,
            error = sheet.problem?.let { problemText(it) },
        )
        AppTextField(
            value = sheet.source,
            onValueChange = { onAction(PriceIndexAction.SourceText(it)) },
            label = stringResource(R.string.index_field_source),
            error =
                if (sheet.sourceTooLong) {
                    pluralStringResource(
                        R.plurals.error_text_long,
                        RealTermsRules.NOTE_MAX,
                        RealTermsRules.NOTE_MAX,
                    )
                } else {
                    null
                },
        )
        sheet.saveFailed?.let {
            NoticeCard(stringResource(saveErrorText(it)), NoticeVariant.ERROR)
        }
        if (sheet.editing) {
            InlineTextButton(
                text = stringResource(R.string.index_delete_action),
                onClick = { onAction(PriceIndexAction.AskDelete) },
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun monthError(sheet: IndexSheet): String? = when {
    sheet.monthTaken -> stringResource(R.string.index_error_month_exists)
    sheet.futureMonth -> stringResource(R.string.index_error_future)
    else -> null
}

@Composable
private fun problemText(problem: ChangeProblem): String {
    val percent = LocalAppFormats.current.percent
    return when (problem) {
        ChangeProblem.NOT_A_NUMBER -> stringResource(R.string.field_error_number)

        ChangeProblem.TOO_MANY_DECIMALS -> stringResource(R.string.index_error_decimals)

        ChangeProblem.TOO_LOW ->
            stringResource(
                R.string.index_error_percent,
                percent.format(RealTermsRules.MIN_CHANGE_EXCLUSIVE),
            )

        ChangeProblem.TOO_HIGH ->
            stringResource(
                R.string.index_error_percent_max,
                percent.format(RealTermsRules.MAX_CHANGE),
            )
    }
}

@Composable
private fun ConfirmDelete(month: YearMonth, onAction: (PriceIndexAction) -> Unit) {
    ConfirmDialog(
        title = stringResource(R.string.index_delete_confirm_title),
        body =
            stringResource(
                R.string.index_delete_confirm_body,
                LocalAppFormats.current.date.monthYear(month),
            ),
        confirmLabel = stringResource(R.string.index_delete_action),
        onConfirm = { onAction(PriceIndexAction.ConfirmDelete) },
        onDismiss = { onAction(PriceIndexAction.Dismiss) },
        variant = ConfirmVariant.DESTRUCTIVE,
    )
}

/** Muestra [message] con los mensajes de la navegación, que sobreviven a la pantalla. */
@Composable
private fun ShowMessage(message: IndexMessage?, onShown: () -> Unit) {
    val messages = LocalAppMessages.current
    val text =
        when (message) {
            null -> null
            is IndexMessage.DeleteFailed -> stringResource(saveErrorText(message.outOfSpace))
        }
    LaunchedEffect(message) {
        if (text != null) {
            messages.show(AppSnackbarVisuals(text, SnackbarVariant.ERROR))
            onShown()
        }
    }
}

private const val SKELETON_ROWS = 6

package io.github.yesidrobayo.shopledger.ui.goal

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.GoalAdvance
import io.github.yesidrobayo.shopledger.domain.model.report.MonthGoals
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.report.MonthGoalView
import io.github.yesidrobayo.shopledger.domain.usecase.report.ObserveMonthGoalsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.report.ObservePeriodReportUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.report.ObserveRevenueLimitUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.report.SaveMonthGoalsUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.report.SaveRevenueLimitUseCase
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
import io.github.yesidrobayo.shopledger.ui.components.AppTopBar
import io.github.yesidrobayo.shopledger.ui.components.DelayedSkeleton
import io.github.yesidrobayo.shopledger.ui.components.EmptyState
import io.github.yesidrobayo.shopledger.ui.components.EmptyStateInline
import io.github.yesidrobayo.shopledger.ui.components.GoalProgress
import io.github.yesidrobayo.shopledger.ui.components.InlineTextButton
import io.github.yesidrobayo.shopledger.ui.components.MoneyField
import io.github.yesidrobayo.shopledger.ui.components.NoticeCard
import io.github.yesidrobayo.shopledger.ui.components.NoticeVariant
import io.github.yesidrobayo.shopledger.ui.components.PeriodSelector
import io.github.yesidrobayo.shopledger.ui.components.PrimaryActionButton
import io.github.yesidrobayo.shopledger.ui.components.SectionHeader
import io.github.yesidrobayo.shopledger.ui.components.SkeletonBlock
import io.github.yesidrobayo.shopledger.ui.components.SnackbarVariant
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.components.saveErrorText
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.ThemePreviews
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth

/**
 * `Metas y límites` en la navegación. La hoja abierta se cierra con atrás antes que la pantalla.
 */
@Composable
fun GoalsRoute(navigator: AppNavigator) {
    val money = LocalAppFormats.current.money
    val numbers = GoalNumbers(money.inputSymbols, money.currencyDecimals)
    val model =
        rememberAppViewModel(key = "goals-and-limits") { deps ->
            val ports = deps.ports
            val clock = deps.container.clock
            val report = ObservePeriodReportUseCase(ports.periodReports)
            val observe = ObserveMonthGoalsUseCase(ports.goals, report)
            val save = SaveMonthGoalsUseCase(ports.transactions, ports.goals, clock)
            val limit = ObserveRevenueLimitUseCase(ports.settings, ports.planningReports, report)
            val saveLimit =
                SaveRevenueLimitUseCase(ports.transactions, ports.goals, ports.settings, clock)
            GoalsViewModel(
                today = BusinessCalendarSource(ports.settings).observe().map { result ->
                    when (result) {
                        is BusinessCalendarResult.Ready ->
                            result.calendar.businessDateOf(clock.now())

                        is BusinessCalendarResult.UnknownZone -> null
                    }
                },
                observe = { observe(it) },
                save = { month, sales, profit -> save(month, sales, profit) },
                numbers = numbers,
                limit = { limit(it) },
                saveLimit = { saveLimit(it) },
            )
        }
    when (model) {
        ScreenModel.Opening -> GoalsScreen(GoalsUiState.Loading) {}

        ScreenModel.Unavailable -> PlaceholderRoute(AppNavKey.GoalsAndLimits, navigator)

        is ScreenModel.Ready -> {
            val viewModel = model.viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val ready = uiState as? GoalsUiState.Ready
            BackHandler(enabled = ready?.sheet != null || ready?.limitSheet != null) {
                viewModel.onAction(GoalsAction.DismissSheet)
            }
            ShowMessage(ready?.message) { viewModel.onAction(GoalsAction.MessageShown) }
            GoalsScreen(uiState) { action ->
                when (action) {
                    GoalsAction.Back -> navigator.back()
                    GoalsAction.ChooseZone -> navigator.open(AppNavKey.RegionalSettings())
                    else -> viewModel.onAction(action)
                }
            }
        }
    }
}

/**
 * `Metas y límites`, sin ViewModel: las metas del mes elegido, cada una con su avance, el límite
 * de ingresos con lo acumulado, y las hojas para definirlos. Metas y límite son de la persona: la
 * app no propone ninguno ni conoce ningún régimen. Desde una ventana mediana, en dos columnas.
 *
 * Es de lectura. La acción principal es `Definir metas` solo si el mes no tiene metas.
 */
@Composable
fun GoalsScreen(
    uiState: GoalsUiState,
    modifier: Modifier = Modifier,
    onAction: (GoalsAction) -> Unit,
) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(
                title = stringResource(R.string.goal_screen_title),
                navigation = TopBarNavigation.BACK,
                onNavigate = { onAction(GoalsAction.Back) },
                navigationLabel = stringResource(R.string.a11y_back),
            )
            Column(
                Modifier
                    .weight(1f)
                    .align(Alignment.CenterHorizontally)
                    .widthIn(max = AppTheme.sizes.maxContentWidth * 2)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(bottom = AppTheme.spacing.s16),
            ) {
                when (uiState) {
                    GoalsUiState.Loading -> LoadingSkeleton()
                    GoalsUiState.ReadFailed -> ReadFailed(onAction)
                    GoalsUiState.UnknownZone -> UnknownZone(onAction)
                    is GoalsUiState.Ready -> ReadyBody(uiState, onAction)
                }
            }
        }
    }
    val ready = uiState as? GoalsUiState.Ready
    ready?.sheet?.let { GoalSheetHost(it, onAction) }
    ready?.limitSheet?.let { LimitSheetHost(it, onAction) }
}

/** Las metas y el límite: una columna en el teléfono, y dos desde una ventana mediana. */
@Composable
private fun ReadyBody(state: GoalsUiState.Ready, onAction: (GoalsAction) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= MEDIUM_WIDTH) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s16),
            ) {
                Column(Modifier.weight(1f)) { GoalsSection(state, onAction) }
                Column(Modifier.weight(1f)) { LimitSection(state.limit, onAction) }
            }
        } else {
            Column(Modifier.widthIn(max = AppTheme.sizes.maxContentWidth)) {
                GoalsSection(state, onAction)
                LimitSection(state.limit, onAction)
            }
        }
    }
}

/** El esqueleto de dos barras, solo si la espera se nota. */
@Composable
private fun LoadingSkeleton() {
    DelayedSkeleton {
        Column(
            Modifier.padding(AppTheme.spacing.s16),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s16),
        ) {
            repeat(SKELETON_BARS) {
                SkeletonBlock(Modifier.fillMaxWidth().heightIn(min = AppTheme.sizes.listRow))
            }
        }
    }
}

@Composable
private fun ReadFailed(onAction: (GoalsAction) -> Unit) {
    EmptyState(icon = R.drawable.ic_error, title = stringResource(R.string.state_error_read)) {
        PrimaryActionButton(
            text = stringResource(R.string.action_retry),
            onClick = { onAction(GoalsAction.Retry) },
            variant = ActionVariant.TONAL,
        )
    }
}

/** Sin zona del negocio no se sabe qué mes es: no se supone otra, se lleva a elegirla. */
@Composable
private fun UnknownZone(onAction: (GoalsAction) -> Unit) {
    EmptyState(
        icon = R.drawable.ic_error,
        title = stringResource(R.string.stats_zone_unknown_title),
    ) {
        PrimaryActionButton(
            text = stringResource(R.string.stats_zone_unknown_action),
            onClick = { onAction(GoalsAction.ChooseZone) },
            variant = ActionVariant.TONAL,
        )
    }
}

/**
 * `Metas del mes`: el selector de mes y el avance de cada meta que el mes tiene. Sin metas, un
 * estado vacío lleva a definirlas.
 */
@Composable
internal fun GoalsSection(state: GoalsUiState.Ready, onAction: (GoalsAction) -> Unit) {
    val spacing = AppTheme.spacing
    Column {
        SectionHeader(stringResource(R.string.goal_section))
        PeriodSelector(
            period = ReportPeriod.Month(state.month),
            onPrevious = { onAction(GoalsAction.PreviousMonth) },
            onNext = { onAction(GoalsAction.NextMonth) },
            showNext = state.canGoNext,
            today = state.today,
            modifier = Modifier.padding(horizontal = spacing.s8),
        )
        Column(Modifier.padding(horizontal = spacing.s16)) {
            val goals = state.goals
            if (goals.goals == null) {
                EmptyStateInline(
                    title = stringResource(R.string.goal_empty_title),
                    body = stringResource(R.string.goal_empty_body),
                    action = {
                        PrimaryActionButton(
                            text = stringResource(R.string.goal_define_action),
                            onClick = { onAction(GoalsAction.StartGoals) },
                            variant = ActionVariant.PRIMARY,
                            modifier = Modifier.padding(top = spacing.s8),
                        )
                    },
                )
            } else {
                goals.sales?.let { GoalProgress(stringResource(R.string.goal_sales), it) }
                goals.profit?.let { GoalProgress(stringResource(R.string.goal_profit), it) }
                InlineTextButton(
                    text = stringResource(R.string.goal_edit_action),
                    onClick = { onAction(GoalsAction.StartGoals) },
                )
            }
        }
    }
}

/**
 * La hoja de metas del mes: `Meta de ventas` y `Meta de ganancia final`, las dos opcionales.
 * Dejarlas vacías quita las metas del mes. Una falla se dice en la hoja y lo escrito se conserva.
 * Mientras se escribe, los montos se ven aunque el modo discreto esté encendido.
 */
@Composable
private fun GoalSheetHost(sheet: GoalSheet, onAction: (GoalsAction) -> Unit) {
    AppBottomSheet(
        onDismissRequest = { onAction(GoalsAction.DismissSheet) },
        title = stringResource(
            if (sheet.editing) R.string.goal_edit_action else R.string.goal_define_action,
        ),
        action = {
            PrimaryActionButton(
                text = stringResource(R.string.goal_save_action),
                onClick = { onAction(GoalsAction.SaveGoals) },
                variant = ActionVariant.PRIMARY,
                state = if (sheet.saving) ActionState.WORKING else ActionState.IDLE,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        SheetMonth(sheet.month)
        MoneyField(
            value = sheet.salesText,
            onValueChange = { onAction(GoalsAction.SalesText(it)) },
            label = stringResource(R.string.goal_sales_field),
            error = sheet.salesProblem?.let { goalProblemText(it) },
        )
        MoneyField(
            value = sheet.profitText,
            onValueChange = { onAction(GoalsAction.ProfitText(it)) },
            label = stringResource(R.string.goal_profit_field),
            error = sheet.profitProblem?.let { goalProblemText(it) },
            imeAction = ImeAction.Done,
        )
        sheet.saveFailed?.let {
            NoticeCard(stringResource(saveErrorText(it.outOfSpace)), NoticeVariant.ERROR)
        }
    }
}

/** El mes de la hoja, que no se cambia en ella: se elige con el selector de la pantalla. */
@Composable
private fun SheetMonth(month: YearMonth) {
    Text(
        LocalAppFormats.current.date.monthYear(month),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Lo que dice un campo de meta con [problem]. */
@Composable
private fun goalProblemText(problem: GoalFieldProblem): String = stringResource(
    when (problem) {
        GoalFieldProblem.NOT_POSITIVE -> R.string.goal_error_value
        GoalFieldProblem.NOT_A_NUMBER -> R.string.field_error_number
        GoalFieldProblem.TOO_HIGH -> R.string.error_value_high
    },
)

/** Muestra [message] con los mensajes de la navegación, que sobreviven a la pantalla. */
@Composable
private fun ShowMessage(message: GoalMessage?, onShown: () -> Unit) {
    val messages = LocalAppMessages.current
    val text = message?.let {
        stringResource(
            when (it) {
                GoalMessage.Saved -> R.string.goal_saved
                GoalMessage.Removed -> R.string.goal_removed
                GoalMessage.LimitSaved -> R.string.limit_saved
                GoalMessage.LimitRemoved -> R.string.limit_removed
            },
        )
    }
    LaunchedEffect(message) {
        if (text != null) {
            messages.show(AppSnackbarVisuals(text, SnackbarVariant.SUCCESS))
            onShown()
        }
    }
}

private const val SKELETON_BARS = 2

/** Desde este ancho, metas y límite van en dos columnas: la ventana mediana. */
private val MEDIUM_WIDTH = 600.dp

private val PREVIEW_MONTH = YearMonth(2026, 11)
private val PREVIEW_TODAY = BusinessDate(LocalDate(2026, 11, 14))

private fun previewState(goals: MonthGoals?): GoalsUiState.Ready = GoalsUiState.Ready(
    month = PREVIEW_MONTH,
    today = PREVIEW_TODAY,
    canGoNext = true,
    goals = MonthGoalView(
        month = PREVIEW_MONTH,
        goals = goals,
        sales = goals?.sales?.let {
            GoalAdvance(Money(850_000), it, BasisPoints(8_500))
        },
        profit = goals?.profit?.let {
            GoalAdvance(Money(186_000), it, BasisPoints(6_200))
        },
    ),
)

@ThemePreviews
@Composable
private fun GoalsScreenPreview() {
    PreviewTheme {
        GoalsScreen(
            previewState(MonthGoals(PREVIEW_MONTH, Money(1_000_000), Money(300_000))),
        ) {}
    }
}

@ThemePreviews
@Composable
private fun GoalsScreenEmptyPreview() {
    PreviewTheme {
        GoalsScreen(previewState(null)) {}
    }
}

@ThemePreviews
@Composable
private fun GoalsScreenDiscreetPreview() {
    PreviewTheme(discreet = true) {
        GoalsScreen(
            previewState(MonthGoals(PREVIEW_MONTH, Money(1_000_000), null)),
        ) {}
    }
}

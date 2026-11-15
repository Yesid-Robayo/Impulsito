package io.github.yesidrobayo.shopledger.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ProfitTotals
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.expense.Recurrence
import io.github.yesidrobayo.shopledger.domain.model.expense.RecurringExpense
import io.github.yesidrobayo.shopledger.domain.model.expense.RecurringExpenseDetails
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.report.BreakEven
import io.github.yesidrobayo.shopledger.domain.model.report.CashFlowHorizon
import io.github.yesidrobayo.shopledger.domain.model.report.CashFlowProjection
import io.github.yesidrobayo.shopledger.domain.model.report.CustomerSales
import io.github.yesidrobayo.shopledger.domain.model.report.GoalAdvance
import io.github.yesidrobayo.shopledger.domain.model.report.IdleProduct
import io.github.yesidrobayo.shopledger.domain.model.report.IdleProducts
import io.github.yesidrobayo.shopledger.domain.model.report.LimitLevel
import io.github.yesidrobayo.shopledger.domain.model.report.ManagementSummary
import io.github.yesidrobayo.shopledger.domain.model.report.MonthGoals
import io.github.yesidrobayo.shopledger.domain.model.report.PaymentMethodTotal
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodAnalysis
import io.github.yesidrobayo.shopledger.domain.model.report.PriceIndexEntry
import io.github.yesidrobayo.shopledger.domain.model.report.ProfitConcentration
import io.github.yesidrobayo.shopledger.domain.model.report.ProfitLeader
import io.github.yesidrobayo.shopledger.domain.model.report.RealComparison
import io.github.yesidrobayo.shopledger.domain.model.report.RealValue
import io.github.yesidrobayo.shopledger.domain.model.report.RevenueLimitView
import io.github.yesidrobayo.shopledger.domain.model.report.SummaryContent
import io.github.yesidrobayo.shopledger.domain.model.settings.RevenueLimit
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxEstimate
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.rule.MarginCalculator
import io.github.yesidrobayo.shopledger.domain.usecase.report.MonthGoalView
import io.github.yesidrobayo.shopledger.ui.expense.RecurringContent
import io.github.yesidrobayo.shopledger.ui.expense.RecurringExpensesScreen
import io.github.yesidrobayo.shopledger.ui.expense.RecurringExpensesUiState
import io.github.yesidrobayo.shopledger.ui.expense.RecurringForm
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.goal.GoalFieldProblem
import io.github.yesidrobayo.shopledger.ui.goal.GoalSheet
import io.github.yesidrobayo.shopledger.ui.goal.GoalsScreen
import io.github.yesidrobayo.shopledger.ui.goal.GoalsUiState
import io.github.yesidrobayo.shopledger.ui.goal.LimitSheet
import io.github.yesidrobayo.shopledger.ui.home.HomeGoalCard
import io.github.yesidrobayo.shopledger.ui.priceindex.ChangeProblem
import io.github.yesidrobayo.shopledger.ui.priceindex.IndexSheet
import io.github.yesidrobayo.shopledger.ui.priceindex.PriceIndexScreen
import io.github.yesidrobayo.shopledger.ui.priceindex.PriceIndexUiState
import io.github.yesidrobayo.shopledger.ui.report.ManagementSummaryScreen
import io.github.yesidrobayo.shopledger.ui.report.ManagementSummaryUiState
import io.github.yesidrobayo.shopledger.ui.report.SummaryGeneration
import io.github.yesidrobayo.shopledger.ui.report.SummaryScreenContent
import io.github.yesidrobayo.shopledger.ui.settings.DailySummaryRow
import io.github.yesidrobayo.shopledger.ui.settings.DailySummaryUiState
import io.github.yesidrobayo.shopledger.ui.stats.AnalysisSection
import io.github.yesidrobayo.shopledger.ui.stats.AnalysisUiState
import io.github.yesidrobayo.shopledger.ui.stats.BreakEvenTool
import io.github.yesidrobayo.shopledger.ui.stats.CashFlowTool
import io.github.yesidrobayo.shopledger.ui.stats.CashFlowUiState
import io.github.yesidrobayo.shopledger.ui.stats.CompareMode
import io.github.yesidrobayo.shopledger.ui.stats.ComparisonSection
import io.github.yesidrobayo.shopledger.ui.stats.ComparisonUiState
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import kotlin.time.Instant
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.YearMonth
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las pantallas de reportes, metas, gastos recurrentes e índice de precios, y los bloques nuevos
 * de `Números`, medidos en el teléfono con la letra que tenga: se corre una vez con 1.0 y otra
 * con 2.0 en `font_scale`, como `AccessibilityFlowsTest`, para que Android aplique su escala no
 * lineal.
 *
 * Cada prueba recorre las escenas principales de una pantalla (con datos, vacía, con error y, si
 * aplica, en modo discreto) y junta las fallas de [A11yAudit]: todo lo tocable mide 48 dp y tiene
 * nombre, y ningún texto se sale de su caja. Falla con la lista entera. Datos inventados.
 */
@RunWith(AndroidJUnit4::class)
class ReportScreensAccessibilityTest {
    @get:Rule
    val compose = createComposeRule()

    private val audit = A11yAudit(compose)
    private var scene by mutableStateOf<(@Composable () -> Unit)?>(null)

    /** La escala de la letra del teléfono, en por ciento. */
    private val percent: Int
        get() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            return (context.resources.configuration.fontScale * PERCENT).toInt()
        }

    /** Dibuja una sola vez; cada escena reemplaza lo que se ve. */
    private fun start() {
        compose.setContent { PreviewTheme { key(scene) { scene?.invoke() } } }
    }

    /** Mide [content], con el modo discreto si [discreet], y junta sus fallas. */
    private fun measure(
        problems: MutableList<String>,
        name: String,
        discreet: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        scene = {
            CompositionLocalProvider(LocalDiscreetMode provides discreet) { content() }
        }
        problems += audit.problems().map { "$name al $percent %: $it" }
    }

    /** Un bloque de `Números` suelto, en una columna que se desplaza como la pantalla. */
    @Composable
    private fun Block(content: @Composable () -> Unit) {
        Column(Modifier.verticalScroll(rememberScrollState())) { content() }
    }

    private fun assertClean(problems: List<String>) {
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun theManagementSummaryPasses() {
        start()
        val problems = mutableListOf<String>()
        val full = summaryState(summary(tax = TAX))
        measure(problems, "resumen") { ManagementSummaryScreen(full, {}) }
        measure(problems, "resumen discreto", discreet = true) {
            ManagementSummaryScreen(full, {})
        }
        measure(problems, "resumen vacío") {
            ManagementSummaryScreen(summaryState(summary(null, hasMovements = false)), {})
        }
        measure(problems, "resumen en curso") {
            val current = ReportPeriod.Month(YearMonth(2026, 11))
            ManagementSummaryScreen(
                summaryState(summary(null, period = current)).copy(period = current),
                {},
            )
        }
        measure(problems, "resumen sin poder crearse") {
            ManagementSummaryScreen(full.copy(generation = SummaryGeneration.Failed), {})
        }
        measure(problems, "resumen sin espacio") {
            ManagementSummaryScreen(full.copy(generation = SummaryGeneration.NoSpace), {})
        }
        measure(problems, "resumen listo") {
            ManagementSummaryScreen(
                full.copy(
                    generation = SummaryGeneration.Ready("resumen-de-gestion-2026-10.zip", 4_096),
                ),
                {},
            )
        }
        measure(problems, "resumen sin leer") {
            ManagementSummaryScreen(full.copy(content = SummaryScreenContent.ReadError), {})
        }
        measure(problems, "resumen sin zona") {
            ManagementSummaryScreen(full.copy(content = SummaryScreenContent.UnknownZone), {})
        }
        assertClean(problems)
    }

    @Test
    fun goalsAndTheLimitPass() {
        start()
        val problems = mutableListOf<String>()
        val sales = GoalAdvance(Money(850_000), Money(1_000_000), BasisPoints(8_500))
        val profit = GoalAdvance(Money(-50_000), Money(300_000), BasisPoints(-1_667))
        val withGoals = goals(sales, profit)
            .copy(limit = limitView(8_200, LimitLevel.FIRST_NOTICE))
        measure(problems, "metas") { GoalsScreen(withGoals) {} }
        measure(problems, "metas discretas", discreet = true) { GoalsScreen(withGoals) {} }
        measure(problems, "metas vacías") { GoalsScreen(goals(null, null)) {} }
        measure(problems, "límite superado") {
            GoalsScreen(
                goals(GoalAdvance(Money(1_200_000), Money(1_000_000), BasisPoints(12_000)), null)
                    .copy(
                        limit = limitView(10_400, LimitLevel.EXCEEDED)
                            .copy(since = BusinessDate(LocalDate(2026, 3, 9))),
                    ),
            ) {}
        }
        measure(problems, "metas sin leer") { GoalsScreen(GoalsUiState.ReadFailed) {} }
        measure(problems, "metas sin zona") { GoalsScreen(GoalsUiState.UnknownZone) {} }
        measure(problems, "hoja de metas") {
            GoalsScreen(
                goals(null, null).copy(
                    sheet = GoalSheet(
                        month = NOVEMBER,
                        editing = false,
                        salesText = "0",
                        salesProblem = GoalFieldProblem.NOT_POSITIVE,
                    ),
                ),
            ) {}
        }
        measure(problems, "hoja del límite") {
            GoalsScreen(
                goals(null, null).copy(
                    limitSheet = LimitSheet(
                        editing = true,
                        monthsText = "61",
                        monthsInvalid = true,
                    ),
                ),
            ) {}
        }
        measure(problems, "meta en Inicio") { Block { HomeGoalCard(sales) {} } }
        measure(problems, "meta en Inicio discreta", discreet = true) {
            Block { HomeGoalCard(sales) {} }
        }
        assertClean(problems)
    }

    @Test
    fun recurringExpensesPass() {
        start()
        val problems = mutableListOf<String>()
        val listed = RecurringExpensesUiState(
            content = RecurringContent.Active(
                due = listOf(recurring(1, "Alquiler inventado", LocalDate(2026, 11, 1))),
                scheduled = listOf(recurring(2, LONG_NAME, LocalDate(2026, 11, 5))),
            ),
            today = TODAY,
            weekStartDay = DayOfWeek.MONDAY,
        )
        measure(problems, "recurrentes") { RecurringExpensesScreen(listed, {}) }
        measure(problems, "recurrentes vacíos") {
            RecurringExpensesScreen(
                RecurringExpensesUiState(content = RecurringContent.Empty, today = TODAY),
                {},
            )
        }
        measure(problems, "recurrentes sin leer") {
            RecurringExpensesScreen(RecurringExpensesUiState(RecurringContent.ReadError), {})
        }
        measure(problems, "recurrentes sin zona") {
            RecurringExpensesScreen(RecurringExpensesUiState(RecurringContent.UnknownZone), {})
        }
        measure(problems, "recurrentes archivados") {
            RecurringExpensesScreen(
                RecurringExpensesUiState(
                    content = RecurringContent.Archived(
                        listOf(recurring(3, "Seguro inventado", LocalDate(2026, 12, 1))),
                    ),
                    showArchived = true,
                    today = TODAY,
                ),
                {},
            )
        }
        measure(problems, "hoja de recurrente") {
            RecurringExpensesScreen(
                listed.copy(
                    form = RecurringForm(
                        day = 31,
                        nextDate = BusinessDate(LocalDate(2026, 11, 30)),
                    ),
                ),
                {},
            )
        }
        measure(problems, "omitir recurrente") {
            RecurringExpensesScreen(
                listed.copy(skipping = recurring(1, "Alquiler inventado", LocalDate(2026, 11, 1))),
                {},
            )
        }
        assertClean(problems)
    }

    @Test
    fun thePriceIndexPasses() {
        start()
        val problems = mutableListOf<String>()
        val listed = PriceIndexUiState.Ready(
            entries = listOf(
                PriceIndexEntry(OCTOBER, BasisPoints(190), "Lista propia inventada"),
                PriceIndexEntry(YearMonth(2026, 7), BasisPoints(-25), null),
                PriceIndexEntry(YearMonth(2026, 6), BasisPoints(0), LONG_NAME),
            ),
            missing = listOf(YearMonth(2026, 8), YearMonth(2026, 9)),
            currentMonth = NOVEMBER,
        )
        measure(problems, "índice") { PriceIndexScreen(listed) {} }
        measure(problems, "índice discreto", discreet = true) { PriceIndexScreen(listed) {} }
        measure(problems, "índice vacío") {
            PriceIndexScreen(PriceIndexUiState.Ready(emptyList(), emptyList(), NOVEMBER)) {}
        }
        measure(problems, "índice sin leer") { PriceIndexScreen(PriceIndexUiState.ReadFailed) {} }
        measure(problems, "hoja de mes nuevo") {
            PriceIndexScreen(
                listed.copy(
                    sheet = IndexSheet(
                        month = NOVEMBER,
                        editing = false,
                        changeText = "1,234",
                        problem = ChangeProblem.TOO_MANY_DECIMALS,
                        monthTaken = true,
                    ),
                ),
            ) {}
        }
        measure(problems, "hoja de mes guardado") {
            PriceIndexScreen(
                listed.copy(
                    sheet = IndexSheet(
                        month = OCTOBER,
                        editing = true,
                        changeText = "1,9",
                        source = "Lista propia inventada",
                    ),
                ),
            ) {}
        }
        measure(problems, "eliminar mes") {
            PriceIndexScreen(listed.copy(confirmingDelete = OCTOBER)) {}
        }
        assertClean(problems)
    }

    @Test
    fun theCashFlowPasses() {
        start()
        val problems = mutableListOf<String>()
        val projection = CashFlowProjection(
            TODAY,
            Money(85_000),
            listOf(horizon(7, 65_000), horizon(30, -5_000)),
        )
        val ready = CashFlowUiState.Ready(projection)
        measure(problems, "flujo") { Block { CashFlowTool(ready, onRetry = {}) } }
        measure(problems, "flujo discreto", discreet = true) {
            Block { CashFlowTool(ready, onRetry = {}) }
        }
        measure(problems, "flujo sin leer") {
            Block { CashFlowTool(CashFlowUiState.Failed, onRetry = {}) }
        }
        assertClean(problems)
    }

    @Test
    fun theBreakEvenPasses() {
        start()
        val problems = mutableListOf<String>()
        val missing = BreakEven.Calculated(
            fixedExpenses = Money(640_000),
            margin = BasisPoints(3_600),
            breakEvenSales = Money(1_777_778),
            netSales = Money(1_000_000),
            remaining = Money(777_778),
        )
        val covered = missing.copy(netSales = Money(2_000_000), remaining = Money.ZERO)
        measure(problems, "equilibrio") { Block { BreakEvenTool(missing, {}) } }
        measure(problems, "equilibrio cubierto") { Block { BreakEvenTool(covered, {}) } }
        measure(problems, "equilibrio discreto", discreet = true) {
            Block { BreakEvenTool(missing, {}) }
        }
        measure(problems, "equilibrio sin margen") {
            Block { BreakEvenTool(BreakEven.NoMargin(Money(640_000)), {}) }
        }
        measure(problems, "equilibrio sin gastos fijos") {
            Block { BreakEvenTool(BreakEven.NoFixedExpenses, {}) }
        }
        assertClean(problems)
    }

    @Test
    fun theAnalysisPasses() {
        start()
        val problems = mutableListOf<String>()
        val ready = AnalysisUiState.Ready(ANALYSIS)
        measure(problems, "análisis") { Block { AnalysisSection(ready, emptyMap(), {}) } }
        measure(problems, "análisis discreto", discreet = true) {
            Block { AnalysisSection(ready, emptyMap(), {}) }
        }
        measure(problems, "análisis sin ganancia") {
            Block {
                AnalysisSection(
                    AnalysisUiState.Ready(
                        ANALYSIS.copy(concentration = ProfitConcentration.NoProfit),
                    ),
                    emptyMap(),
                    {},
                )
            }
        }
        measure(problems, "análisis sin leer") {
            Block { AnalysisSection(AnalysisUiState.ReadError, emptyMap(), {}) }
        }
        assertClean(problems)
    }

    @Test
    fun theComparisonPasses() {
        start()
        val problems = mutableListOf<String>()
        val estimated = comparison(RealValue.Estimated(Money(4_100_000), BasisPoints(680)))
        measure(problems, "comparar") {
            Block {
                ComparisonSection(
                    ComparisonUiState.Ready(estimated, CompareMode.NOMINAL),
                    TODAY,
                    {},
                    {},
                    {},
                )
            }
        }
        measure(problems, "comparar real") {
            Block {
                ComparisonSection(
                    ComparisonUiState.Ready(estimated, CompareMode.REAL),
                    TODAY,
                    {},
                    {},
                    {},
                )
            }
        }
        measure(problems, "comparar real discreto", discreet = true) {
            Block {
                ComparisonSection(
                    ComparisonUiState.Ready(estimated, CompareMode.REAL),
                    TODAY,
                    {},
                    {},
                    {},
                )
            }
        }
        measure(problems, "comparar con meses faltantes") {
            Block {
                ComparisonSection(
                    ComparisonUiState.Ready(
                        comparison(RealValue.MissingMonths(listOf(YearMonth(2026, 9), OCTOBER))),
                        CompareMode.REAL,
                    ),
                    TODAY,
                    {},
                    {},
                    {},
                )
            }
        }
        measure(problems, "comparar fuera de rango") {
            Block {
                ComparisonSection(
                    ComparisonUiState.Ready(comparison(RealValue.OutOfRange), CompareMode.REAL),
                    TODAY,
                    {},
                    {},
                    {},
                )
            }
        }
        measure(problems, "comparar sin leer") {
            Block { ComparisonSection(ComparisonUiState.ReadError, TODAY, {}, {}, {}) }
        }
        assertClean(problems)
    }

    @Test
    fun theDailySummaryPasses() {
        start()
        val problems = mutableListOf<String>()
        val on = DailySummaryUiState.Ready(time = LocalTime(20, 30))
        measure(problems, "resumen diario apagado") {
            Block { DailySummaryRow(DailySummaryUiState.Ready(time = null), false, {}, {}) }
        }
        measure(problems, "resumen diario sin avisos") {
            Block { DailySummaryRow(on, blocked = true, onAction = {}, onOpenSettings = {}) }
        }
        measure(problems, "hora del resumen diario") {
            Block {
                DailySummaryRow(
                    on.copy(choosing = true, saveFailed = false),
                    blocked = false,
                    onAction = {},
                    onOpenSettings = {},
                )
            }
        }
        measure(problems, "resumen diario sin leer") {
            Block { DailySummaryRow(DailySummaryUiState.ReadError, false, {}, {}) }
        }
        assertClean(problems)
    }

    private fun summaryState(summary: ManagementSummary) = ManagementSummaryUiState(
        period = summary.period,
        today = TODAY,
        weekStartDay = DayOfWeek.MONDAY,
        canGoNext = true,
        content = SummaryScreenContent.Ready(summary),
        chosen = setOf(SummaryContent.SALES_AND_PAYMENTS, SummaryContent.PURCHASES_AND_EXPENSES),
    )

    private fun summary(
        tax: TaxEstimate?,
        hasMovements: Boolean = true,
        period: ReportPeriod = ReportPeriod.Month(OCTOBER),
    ) = ManagementSummary(
        period = period,
        profit = requireNotNull(MarginCalculator.profit(TOTALS).valueOrNull()),
        tax = tax,
        methods = listOf(
            PaymentMethodTotal("Efectivo inventado", PaymentMethodKind.CASH, 30, LONG, Money.ZERO),
            PaymentMethodTotal(LONG_NAME, PaymentMethodKind.CASH, 2, Money(9_000), Money.ZERO),
        ),
        salesWithoutCost = 3,
        hasMovements = hasMovements,
        contents = SummaryContent.entries,
    )

    private fun goals(sales: GoalAdvance?, profit: GoalAdvance?) = GoalsUiState.Ready(
        month = NOVEMBER,
        today = TODAY,
        canGoNext = true,
        goals = MonthGoalView(
            month = NOVEMBER,
            goals = if (sales == null && profit == null) {
                null
            } else {
                MonthGoals(NOVEMBER, sales?.goal, profit?.goal)
            },
            sales = sales,
            profit = profit,
        ),
    )

    private fun limitView(used: Long, level: LimitLevel) = RevenueLimitView(
        limit = RevenueLimit(Money(10_000_000), 12),
        window = ReportPeriod.Range(
            BusinessDate(LocalDate(2025, 12, 1)),
            BusinessDate(LocalDate(2026, 11, 30)),
        ),
        accumulated = Money(used * 1_000),
        used = BasisPoints(used),
        level = level,
        since = null,
    )

    private fun recurring(id: Long, description: String, next: LocalDate) = RecurringExpense(
        id = id,
        details = RecurringExpenseDetails(
            expenseCategoryId = 1,
            amount = LONG,
            description = description,
            recurrence = Recurrence.MONTHLY,
            dayOfPeriod = next.day,
            nextDueDate = BusinessDate(next),
            moneyAccountId = null,
            reminderEnabled = false,
        ),
        archivedAt = null,
        createdAt = AT,
        updatedAt = AT,
    )

    private fun horizon(days: Int, projected: Long) = CashFlowHorizon(
        days = days,
        until = TODAY.plusDays(days),
        settlements = Money(15_000),
        receivables = Money(20_000),
        payables = Money(30_000),
        recurring = Money(25_000),
        projected = Money(projected),
    )

    private fun comparison(real: RealValue) = RealComparison(
        period = ReportPeriod.Month(OCTOBER),
        comparedTo = ReportPeriod.Month(YearMonth(2026, 9)),
        base = Money(3_900_000),
        nominal = LONG,
        nominalVariation = BasisPoints(-320),
        real = real,
    )

    private companion object {
        const val PERCENT = 100
        val TODAY = BusinessDate(LocalDate(2026, 11, 3))
        val OCTOBER = YearMonth(2026, 10)
        val NOVEMBER = YearMonth(2026, 11)
        val AT: Instant = Instant.parse("2026-11-01T12:00:00Z")

        /** Un monto de 13 cifras, el más largo que se ve en una fila. */
        val LONG = Money(1_234_567_890_123L)

        /** Un nombre de 80 caracteres, como piden las vistas previas. */
        val LONG_NAME =
            "Servicio de mantenimiento inventado con un nombre muy largo para probar filas"
                .padEnd(80, '.')

        val TAX = TaxEstimate(collected = Money(510_000), paid = Money(120_000))
        val TOTALS = ProfitTotals(
            salesNetTotal = Money(4_250_000),
            returnsNetRefund = Money.ZERO,
            salesTotalCost = Money(2_720_000),
            returnsCostReturned = Money.ZERO,
            recordedExpenses = Money(640_000),
            netShrinkage = Money(50_000),
            paymentFees = Money(35_000),
            cashRoundingTotal = Money.ZERO,
        )
        val ANALYSIS = PeriodAnalysis(
            concentration = ProfitConcentration.Leaders(
                listOf(ProfitLeader(1, LONG_NAME, Money(8_000), BasisPoints(8_000))),
                soldProducts = 3,
                reachedShare = BasisPoints(8_000),
            ),
            idle = IdleProducts(
                listOf(IdleProduct(2, "Yerba inventada", 1, QuantityMilli(2_000), LONG)),
                total = 3,
            ),
            customers = listOf(CustomerSales(5, null, 2, Money(12_000))),
        )
    }
}

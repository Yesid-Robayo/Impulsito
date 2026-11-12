package io.github.yesidrobayo.shopledger.ui.stats

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodKind
import io.github.yesidrobayo.shopledger.format.NO_VALUE_TEXT
import io.github.yesidrobayo.shopledger.ui.components.DISCREET_TEXT
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `Números` en el teléfono: las pestañas y las flechas avisan su acción, la gráfica tiene resumen
 * para TalkBack y su lista, el período sin movimientos lo dice con margen `—`, el modo discreto
 * oculta el dinero y deja el número de ventas, y sin datos lleva a vender. Datos inventados.
 */
@RunWith(AndroidJUnit4::class)
class StatisticsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val actions = mutableListOf<StatisticsAction>()

    private fun text(id: Int) = resources.getString(id)

    private fun show(state: StatisticsUiState, discreet: Boolean = false) {
        compose.setContent {
            PreviewTheme(discreet = discreet) { StatisticsScreen(state, { actions += it }) }
        }
    }

    @Test
    fun tabsArrowsChipsAndListReportTheirAction() {
        show(previewStatisticsContent())

        compose.onNodeWithText(text(R.string.period_week)).performClick()
        compose.onNodeWithContentDescription(text(R.string.a11y_period_previous)).performClick()
        compose.onNodeWithText(text(R.string.stats_metric_profit)).performClick()
        compose.onNodeWithText(text(R.string.stats_view_list)).performClick()

        assertEquals(
            listOf(
                StatisticsAction.SelectKind(PeriodKind.WEEK),
                StatisticsAction.Previous,
                StatisticsAction.SelectMetric(ChartMetric.PROFIT),
                StatisticsAction.ToggleList,
            ),
            actions,
        )
    }

    @Test
    fun theMonthShowsItsSalesCountChartSummaryAndCascade() {
        show(previewStatisticsContent())

        compose.onNodeWithText(resources.getQuantityString(R.plurals.stats_sales_count, 312, 312))
            .assertIsDisplayed()
        // TalkBack no ve el lienzo de la gráfica: el contenedor dice la serie, el rango y el total.
        val summaryStart = text(R.string.stats_chart_summary_sales).substringBefore("%1")
        compose.onNodeWithContentDescription(summaryStart, substring = true).assertIsDisplayed()
        scrollTo(text(R.string.term_final_profit))
        compose.onNodeWithText(text(R.string.term_margin)).assertExists()
        compose.onNodeWithText(text(R.string.stats_row_fees)).assertExists()
        compose.onNodeWithText(text(R.string.stats_tool_estimated_tax)).assertExists()
        compose.onNodeWithText(text(R.string.tax_estimate_notice)).assertExists()
    }

    @Test
    fun tappingABarReportsItsDayAndTappingAgainReleasesIt() {
        // La guía de Vico avisa la x tocada: así el valor se escribe bajo la gráfica.
        val content = previewStatisticsContent()
        val points = requireNotNull(content.series)
        val tapped = mutableListOf<BusinessDate?>()
        compose.setContent {
            PreviewTheme {
                DailyBarChart(
                    points = points,
                    metric = ChartMetric.SALES,
                    selectedDay = null,
                    onSelectDay = { tapped += it },
                    summary = SUMMARY,
                    height = AppTheme.sizes.chartArea,
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithContentDescription(SUMMARY).performTouchInput { click(center) }
        compose.waitForIdle()
        val day = tapped.firstOrNull()
        assertTrue("Sin día tocado: $tapped", day != null && day in content.period)

        compose.onNodeWithContentDescription(SUMMARY).performTouchInput { click(center) }
        compose.waitForIdle()
        assertEquals(null, tapped.last())
    }

    @Test
    fun theListShowsEveryDayOfTheChart() {
        show(previewStatisticsContent(showList = true))

        compose.onNodeWithText(text(R.string.stats_view_chart)).assertExists()
        // Treinta días de noviembre, uno por fila, aunque no se vean todos a la vez.
        scrollTo(text(R.string.stats_how_calculated))
    }

    @Test
    fun anEmptyPeriodSaysItAndHasNoMargin() {
        show(previewEmptyPeriod())

        compose.onNodeWithText(text(R.string.empty_stats_period_body)).assertIsDisplayed()
        scrollTo(text(R.string.term_final_profit))
        assertTrue(compose.onAllNodesWithText(NO_VALUE_TEXT).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun aDayIsAFigureWithoutChart() {
        show(previewLongDay())

        compose.onNodeWithText(text(R.string.stats_view_list)).assertDoesNotExist()
    }

    @Test
    fun discreetModeHidesMoneyButNotTheSalesCount() {
        show(previewStatisticsContent(), discreet = true)

        compose.onNodeWithText(resources.getQuantityString(R.plurals.stats_sales_count, 312, 312))
            .assertIsDisplayed()
        assertTrue(
            compose.onAllNodesWithText(
                DISCREET_TEXT,
                substring = true,
            ).fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    fun withoutDataTheNumbersLeadToTheFirstSale() {
        show(StatisticsUiState.NoData)

        compose.onNodeWithText(text(R.string.empty_stats_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.empty_stats_action)).performClick()

        assertEquals(listOf<StatisticsAction>(StatisticsAction.NewSale), actions)
    }

    @Test
    fun anUnknownZoneLeadsToChooseIt() {
        show(StatisticsUiState.UnknownZone)

        compose.onNodeWithText(text(R.string.stats_zone_unknown_action)).performClick()

        assertEquals(listOf<StatisticsAction>(StatisticsAction.ChooseZone), actions)
    }

    private companion object {
        const val SUMMARY = "Resumen inventado"
    }

    private fun scrollTo(label: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(label))
    }
}

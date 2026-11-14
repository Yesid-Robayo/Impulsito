package io.github.yesidrobayo.shopledger.ui.stats

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.report.PeriodKind
import io.github.yesidrobayo.shopledger.format.NO_VALUE_TEXT
import io.github.yesidrobayo.shopledger.ui.components.DISCREET_TEXT
import io.github.yesidrobayo.shopledger.ui.components.PreviewSamples
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
        // TalkBack oye la etiqueta y el valor unidos con el recurso del idioma.
        compose.onNodeWithContentDescription(
            resources.getString(R.string.a11y_label_value, text(R.string.term_margin), ""),
            substring = true,
        ).assertExists()
        assertTrue(
            compose.onAllNodesWithText(
                text(R.string.stats_row_fees),
            ).fetchSemanticsNodes().isNotEmpty(),
        )
        compose.onNodeWithText(text(R.string.stats_tool_estimated_tax)).assertExists()
        compose.onNodeWithText(text(R.string.tax_estimate_notice)).assertExists()
    }

    @Test
    fun theComparisonNoticesRankingsAndBreakdownsAreShown() {
        show(previewStatisticsContent())

        // +8,0 % frente a los primeros días del mes anterior.
        compose.onNodeWithText(
            resources.getQuantityString(R.plurals.notice_sales_no_cost, 3, 3),
        ).assertExists()
        compose.onNodeWithText(
            resources.getQuantityString(R.plurals.notice_period_voided, 1, 1),
        ).assertExists()
        scrollTo(text(R.string.stats_by_expense_category))
        compose.onNodeWithText(text(R.string.stats_top_sold)).assertExists()
        compose.onNodeWithText(text(R.string.stats_by_payment)).assertExists()
        // La cascada y el desglose de gastos los nombran a los dos.
        assertEquals(
            2,
            compose.onAllNodesWithText(text(R.string.term_damage_loss)).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun theRankingChipsAndTheDatesMenuReportTheirAction() {
        show(previewStatisticsContent())

        compose.onNodeWithContentDescription(text(R.string.a11y_more_options)).performClick()
        compose.onNodeWithText(text(R.string.stats_choose_dates)).performClick()
        scrollTo(text(R.string.stats_by_quantity))
        compose.onNodeWithText(text(R.string.stats_by_quantity)).performClick()

        assertEquals(
            listOf(
                StatisticsAction.ChooseRange,
                StatisticsAction.SelectRanking(RankingKind.QUANTITY),
            ),
            actions,
        )
    }

    @Test
    fun aProductWithVariantsUnfoldsToShowEachOne() {
        show(previewStatisticsContent())

        val family = PreviewSamples.SECOND_NAME
        scrollTo(family)
        compose.onNode(
            hasText(family) and hasStateDescription(text(R.string.a11y_ranking_collapsed)),
        ).performClick()
        compose.onNodeWithText("M · Rojo").assertDoesNotExist()

        assertEquals(listOf<StatisticsAction>(StatisticsAction.ToggleVariants(2L)), actions)
    }

    @Test
    fun anUnfoldedProductListsItsVariantsUnderIt() {
        show(previewVariants())

        scrollTo("M · Rojo")
        compose.onNode(
            hasText(PreviewSamples.SECOND_NAME) and
                hasStateDescription(text(R.string.a11y_ranking_expanded)),
        ).assertExists()
        compose.onNodeWithText("M · Rojo").assertIsDisplayed()
    }

    @Test
    fun aRangeHasNoArrowsAndThePickerShowsTheTwoDates() {
        show(previewRange().copy(choosingRange = true))

        compose.onNodeWithContentDescription(
            text(R.string.a11y_period_previous),
        ).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.stats_range_from)).assertExists()
        compose.onNodeWithText(text(R.string.stats_range_to)).assertExists()
        compose.onNodeWithText(text(R.string.stats_range_show)).performClick()

        assertTrue(actions.single() is StatisticsAction.ShowRange)
    }

    @Test
    fun theListShowsEveryDayOfTheChart() {
        show(previewStatisticsContent(showList = true))

        compose.onNodeWithText(text(R.string.stats_view_chart)).assertExists()
        // Treinta días de noviembre, uno por fila, aunque no se vean todos a la vez.
        scrollTo(text(R.string.stats_how_calculated))
    }

    @Test
    fun otherIncomeIsShownApartAtTheEndAndHiddenInDiscreetMode() {
        show(previewStatisticsContent().copy(otherIncome = Money(12_000)), discreet = true)

        scrollTo(text(R.string.money_other_income_help))
        compose.onNodeWithText(text(R.string.money_other_income_help)).assertIsDisplayed()
        // El encabezado y la fila llevan el mismo nombre.
        val labels = compose.onAllNodesWithText(text(R.string.stats_other_income))
        assertEquals(2, labels.fetchSemanticsNodes().size)
    }

    @Test
    fun withoutOtherIncomeTheSectionDoesNotExist() {
        show(previewStatisticsContent())

        compose.onNodeWithText(text(R.string.money_other_income_help)).assertDoesNotExist()
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

    private fun scrollTo(label: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(label))
    }
}

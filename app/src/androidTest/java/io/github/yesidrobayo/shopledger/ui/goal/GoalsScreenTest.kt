package io.github.yesidrobayo.shopledger.ui.goal

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.GoalAdvance
import io.github.yesidrobayo.shopledger.domain.model.report.LimitLevel
import io.github.yesidrobayo.shopledger.domain.model.report.MonthGoals
import io.github.yesidrobayo.shopledger.domain.model.report.RevenueLimitView
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.domain.model.settings.RevenueLimit
import io.github.yesidrobayo.shopledger.domain.usecase.report.MonthGoalView
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `Metas y límites` en el teléfono: sin metas lleva a definirlas, con metas cada una es un foco
 * con su avance, la meta alcanzada lo dice sin celebrar, y el modo discreto oculta montos y
 * porcentaje. Montos inventados.
 */
@RunWith(AndroidJUnit4::class)
class GoalsScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val actions = mutableListOf<GoalsAction>()
    private val november = YearMonth(2026, 11)

    private fun text(id: Int) = resources.getString(id)

    private fun ready(sales: GoalAdvance?, profit: GoalAdvance? = null) = GoalsUiState.Ready(
        month = november,
        today = BusinessDate(LocalDate(2026, 11, 14)),
        canGoNext = true,
        goals = MonthGoalView(
            month = november,
            goals = if (sales == null && profit == null) {
                null
            } else {
                MonthGoals(november, sales?.goal, profit?.goal)
            },
            sales = sales,
            profit = profit,
        ),
    )

    @Test
    fun withoutGoalsItLeadsToDefineThem() {
        show(ready(null))

        compose.onNodeWithText(text(R.string.goal_empty_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.goal_define_action)).performClick()
        assertEquals(listOf<GoalsAction>(GoalsAction.StartGoals), actions)
    }

    @Test
    fun eachGoalIsOneFocusWithItsLabelAndEditOpensTheSheet() {
        show(
            ready(
                sales = GoalAdvance(Money(850_000), Money(1_000_000), BasisPoints(8_500)),
                profit = GoalAdvance(Money(186_000), Money(300_000), BasisPoints(6_200)),
            ),
        )

        compose.onNodeWithContentDescription(text(R.string.goal_sales), substring = true)
            .assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.goal_profit), substring = true)
            .assertIsDisplayed()
        compose.onNodeWithText(text(R.string.goal_edit_action)).performClick()
        assertEquals(listOf<GoalsAction>(GoalsAction.StartGoals), actions)
    }

    @Test
    fun aReachedGoalSaysSoWithoutCelebrating() {
        show(ready(GoalAdvance(Money(1_200_000), Money(1_000_000), BasisPoints(12_000))))

        val reached = compose.onAllNodes(hasContentDescription(text(R.string.goal_sales), true))
            .fetchSemanticsNodes()
            .single()
        assertEquals(
            text(R.string.goal_reached),
            reached.config[SemanticsProperties.StateDescription],
        )
    }

    @Test
    fun discreetModeHidesAmountsAndPercent() {
        show(
            ready(GoalAdvance(Money(850_000), Money(1_000_000), BasisPoints(8_500))),
            discreet = true,
        )

        val hidden = resources.getString(
            R.string.a11y_goal_progress_hidden,
            text(R.string.goal_sales),
            text(R.string.a11y_discreet_hidden),
        )
        compose.onNodeWithContentDescription(hidden).assertIsDisplayed()
    }

    @Test
    fun theSheetSaysAZeroGoalIsNotValid() {
        show(
            ready(null).copy(
                sheet = GoalSheet(
                    month = november,
                    editing = false,
                    salesText = "0",
                    salesProblem = GoalFieldProblem.NOT_POSITIVE,
                ),
            ),
        )

        compose.onNodeWithText(text(R.string.goal_error_value)).assertIsDisplayed()
    }

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

    @Test
    fun withoutALimitItLeadsToDefineOneAndSaysItIsAReference() {
        show(ready(null))

        compose.onNodeWithText(text(R.string.limit_empty_title)).performScrollTo()
            .assertIsDisplayed()
        val disclaimer =
            resources.getString(R.string.limit_disclaimer, text(R.string.app_name))
        compose.onNodeWithText(disclaimer).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.limit_define_action)).performScrollTo()
            .performClick()
        assertEquals(listOf<GoalsAction>(GoalsAction.StartLimit), actions)
    }

    @Test
    fun theFirstNoticeAndTheExceededOneAreWords() {
        show(ready(null).copy(limit = limitView(8_200, LimitLevel.FIRST_NOTICE)))
        val reached = text(R.string.limit_notice_reached).substringAfter("%1\$s")
        compose.onNodeWithText(reached, substring = true).performScrollTo().assertIsDisplayed()

        show(ready(null).copy(limit = limitView(10_400, LimitLevel.EXCEEDED)))
        compose.onNodeWithText(text(R.string.limit_notice_over)).performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun theLimitSheetSaysTheMonthsRange() {
        show(
            ready(null).copy(
                limitSheet = LimitSheet(editing = true, monthsText = "61", monthsInvalid = true),
            ),
        )

        compose.onNodeWithText(text(R.string.limit_remove_action)).assertIsDisplayed()
        val range = text(R.string.limit_error_months).substringBefore("%1")
        compose.onNodeWithText(range, substring = true).assertIsDisplayed()
    }

    private fun show(state: GoalsUiState, discreet: Boolean = false) {
        actions.clear()
        compose.runOnUiThread {
            compose.activity.setContent {
                AppTheme(palette = ColorPalette.INDIGO_MUSTARD, darkTheme = false) {
                    CompositionLocalProvider(
                        LocalAppFormats provides previewFormats(),
                        LocalDiscreetMode provides discreet,
                    ) {
                        GoalsScreen(state) { actions += it }
                    }
                }
            }
        }
        compose.waitForIdle()
    }
}

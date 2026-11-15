package io.github.yesidrobayo.shopledger.ui.report

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ProfitTotals
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.report.ManagementSummary
import io.github.yesidrobayo.shopledger.domain.model.report.PaymentMethodTotal
import io.github.yesidrobayo.shopledger.domain.model.report.SummaryContent
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxEstimate
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.rule.MarginCalculator
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El resumen de gestión en el dispositivo: sus cifras, el impuesto solo si el negocio lo cobra,
 * los interruptores de lo que incluye, el aviso de datos sin cifrar y la nota de que no es un
 * libro contable ni una declaración, el vacío sin poder generar, el error con `Reintentar`, el
 * período en curso y la hoja `Resumen listo`. Datos inventados.
 */
@RunWith(AndroidJUnit4::class)
class ManagementSummaryScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val actions = mutableListOf<SummaryAction>()

    private fun text(id: Int) = resources.getString(id)

    @Test
    fun theFiguresTheIncludedFilesAndTheNotesAreShown() {
        show { ManagementSummaryScreen(state(summary(tax = TAX)), { actions += it }) }

        compose.onNodeWithText(text(R.string.report_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.report_sales)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.report_tax_estimated)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.report_final_profit)).assertIsDisplayed()
        compose.onNodeWithText("Efectivo inventado").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.report_include_pdf)).performScrollTo()
        compose.onNodeWithText(text(R.string.report_include_pdf_value)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.report_include_inventory))
            .performScrollTo()
            .performClick()
        compose.onNodeWithText(text(R.string.report_sensitive_notice)).performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText(text(R.string.report_support_note)).performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText(text(R.string.report_generate_action)).performClick()

        assertTrue(SummaryAction.Include(SummaryContent.CLOSING_INVENTORY, true) in actions)
        assertTrue(SummaryAction.Generate in actions)
    }

    @Test
    fun withoutTaxThereIsNoEstimatedTaxRow() {
        show { ManagementSummaryScreen(state(summary(tax = null)), { actions += it }) }

        compose.onAllNodesWithText(text(R.string.report_tax_estimated)).assertCountEquals(0)
        compose.onNodeWithText(text(R.string.report_sales)).assertIsDisplayed()
    }

    @Test
    fun aPeriodWithoutMovementsCannotBeGeneratedAndSaysWhy() {
        show {
            ManagementSummaryScreen(
                state(summary(tax = null, hasMovements = false)),
                { actions += it },
            )
        }

        compose.onNodeWithText(text(R.string.report_empty_title)).assertIsDisplayed()
        compose.onNode(hasText(text(R.string.report_generate_action))).assertIsNotEnabled()
        compose.onAllNodesWithText(text(R.string.report_empty_reason)).assertCountEquals(2)
    }

    @Test
    fun aFailedGenerationOffersToRetry() {
        val failed = state(summary(tax = null)).copy(generation = SummaryGeneration.Failed)
        show { ManagementSummaryScreen(failed, { actions += it }) }

        compose.onNodeWithText(text(R.string.report_error_generate)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.action_retry)).performClick()
        assertTrue(SummaryAction.Generate in actions)
    }

    @Test
    fun theMonthInProgressSaysItsFiguresMayChange() {
        val current = ReportPeriod.Month(YearMonth(2026, 11))
        val inProgress = state(summary(tax = null, period = current)).copy(period = current)
        show { ManagementSummaryScreen(inProgress, { actions += it }) }

        compose.onNodeWithText(text(R.string.report_in_progress_notice)).assertIsDisplayed()
    }

    @Test
    fun theReadySheetSharesOrSaves() {
        val ready = state(summary(tax = null))
            .copy(generation = SummaryGeneration.Ready("resumen-de-gestion-2026-10.zip", 4_096))
        show { ManagementSummaryScreen(ready, { actions += it }) }

        compose.onNodeWithText(text(R.string.report_ready_title)).assertIsDisplayed()
        compose.onNodeWithText("resumen-de-gestion-2026-10.zip", substring = true)
            .assertIsDisplayed()
        compose.onNodeWithText(text(R.string.report_save_folder_action)).performClick()
        compose.onNodeWithText(text(R.string.action_share)).performClick()
        assertTrue(SummaryAction.SaveToFolder in actions)
        assertTrue(SummaryAction.Share in actions)
    }

    private fun show(content: @Composable () -> Unit) {
        compose.runOnUiThread {
            compose.activity.setContent {
                AppTheme(palette = ColorPalette.INDIGO_MUSTARD, darkTheme = false) {
                    CompositionLocalProvider(
                        LocalAppFormats provides previewFormats(),
                        LocalDiscreetMode provides false,
                    ) { content() }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun state(summary: ManagementSummary) = ManagementSummaryUiState(
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
        period: ReportPeriod = ReportPeriod.Month(YearMonth(2026, 10)),
    ) = ManagementSummary(
        period = period,
        profit = requireNotNull(MarginCalculator.profit(TOTALS).valueOrNull()),
        tax = tax,
        methods = listOf(
            PaymentMethodTotal(
                "Efectivo inventado",
                PaymentMethodKind.CASH,
                30,
                Money(4_250_000),
                Money.ZERO,
            ),
        ),
        salesWithoutCost = 0,
        hasMovements = hasMovements,
        contents = SummaryContent.entries,
    )

    private companion object {
        val TODAY = BusinessDate(LocalDate(2026, 11, 3))
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
    }
}

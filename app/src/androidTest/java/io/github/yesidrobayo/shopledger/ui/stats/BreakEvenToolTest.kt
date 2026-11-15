package io.github.yesidrobayo.shopledger.ui.stats

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.report.BreakEven
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.format.NO_VALUE_TEXT
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `Punto de equilibrio` en el teléfono: con margen muestra lo que falta o que ya se cubrió, sin
 * margen dice `—` con su aviso y nunca cero, y sin gastos fijos lleva a marcar categorías. Montos
 * inventados.
 */
@RunWith(AndroidJUnit4::class)
class BreakEvenToolTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private var marked = 0

    private fun text(id: Int) = resources.getString(id)

    private fun count(label: String) =
        compose.onAllNodes(hasText(label, substring = true)).fetchSemanticsNodes().size

    @Test
    fun withAMarginItSaysWhatIsMissing() {
        show(
            BreakEven.Calculated(
                fixedExpenses = Money(640_000),
                margin = BasisPoints(3_600),
                breakEvenSales = Money(1_777_778),
                netSales = Money(1_000_000),
                remaining = Money(777_778),
            ),
        )

        compose.onNodeWithText(text(R.string.stats_break_even_sales)).assertIsDisplayed()
        val missing = text(R.string.stats_break_even_missing).substringAfter("%1\$s")
        assertEquals(1, count(missing))
        assertEquals(0, count(text(R.string.stats_break_even_covered)))
    }

    @Test
    fun salesAboveThePointSayTheyAreCovered() {
        show(
            BreakEven.Calculated(
                fixedExpenses = Money(640_000),
                margin = BasisPoints(3_600),
                breakEvenSales = Money(1_777_778),
                netSales = Money(2_000_000),
                remaining = Money.ZERO,
            ),
        )

        compose.onNodeWithText(text(R.string.stats_break_even_covered)).assertIsDisplayed()
    }

    @Test
    fun withoutAMarginItShowsADashAndANotice() {
        show(BreakEven.NoMargin(Money(640_000)))

        compose.onNodeWithText(text(R.string.stats_break_even_no_margin)).assertIsDisplayed()
        assertTrue(count(NO_VALUE_TEXT) >= 1)
    }

    @Test
    fun withoutFixedExpensesItLeadsToMarkCategories() {
        show(BreakEven.NoFixedExpenses)

        compose.onNodeWithText(text(R.string.stats_break_even_no_fixed)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.stats_break_even_no_fixed_action)).performClick()
        assertEquals(1, marked)
    }

    private fun show(breakEven: BreakEven) {
        marked = 0
        compose.runOnUiThread {
            compose.activity.setContent {
                AppTheme(palette = ColorPalette.INDIGO_MUSTARD, darkTheme = false) {
                    CompositionLocalProvider(
                        LocalAppFormats provides previewFormats(),
                        LocalDiscreetMode provides false,
                    ) {
                        Column { BreakEvenTool(breakEven, onMarkCategories = { marked += 1 }) }
                    }
                }
            }
        }
        compose.waitForIdle()
    }
}

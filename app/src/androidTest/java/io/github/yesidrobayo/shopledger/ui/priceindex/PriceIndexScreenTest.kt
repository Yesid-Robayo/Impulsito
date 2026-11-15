package io.github.yesidrobayo.shopledger.ui.priceindex

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.report.PriceIndexEntry
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import kotlinx.datetime.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `Índice de precios` en el teléfono: sin datos explica para qué sirve, con datos avisa los meses
 * que faltan y lleva a cada mes, y la hoja dice sus errores. Valores inventados.
 */
@RunWith(AndroidJUnit4::class)
class PriceIndexScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val actions = mutableListOf<PriceIndexAction>()
    private val october = YearMonth(2026, 10)
    private val november = YearMonth(2026, 11)

    private fun text(id: Int) = resources.getString(id)

    private fun show(state: PriceIndexUiState) {
        compose.setContent { PreviewTheme { PriceIndexScreen(state) { actions += it } } }
    }

    @Test
    fun withoutDataItSaysWhatItIsForAndOffersToAdd() {
        show(PriceIndexUiState.Ready(emptyList(), emptyList(), november))

        compose.onNodeWithText(text(R.string.index_empty_title)).assertExists()
        compose.onNodeWithText(text(R.string.index_empty_body)).assertExists()
        compose.onNodeWithText(text(R.string.index_add_action)).performClick()

        assertEquals(listOf<PriceIndexAction>(PriceIndexAction.Add), actions)
    }

    @Test
    fun theMissingMonthsAreNamedAndLeadToAddThem() {
        show(
            PriceIndexUiState.Ready(
                entries =
                    listOf(
                        PriceIndexEntry(october, BasisPoints(190), "Lista propia"),
                        PriceIndexEntry(YearMonth(2026, 7), BasisPoints(-25), null),
                    ),
                missing = listOf(YearMonth(2026, 8), YearMonth(2026, 9)),
                currentMonth = november,
            ),
        )

        compose.onNodeWithText("Lista propia").assertExists()
        compose.onNodeWithText(text(R.string.index_compare_action)).performClick()

        assertTrue(PriceIndexAction.Compare in actions)
    }

    @Test
    fun theSheetSaysItsErrorsUnderEachField() {
        show(
            PriceIndexUiState.Ready(
                entries = listOf(PriceIndexEntry(october, BasisPoints(190), null)),
                missing = emptyList(),
                currentMonth = november,
                sheet =
                    IndexSheet(
                        month = november,
                        editing = false,
                        changeText = "1,234",
                        problem = ChangeProblem.TOO_MANY_DECIMALS,
                        monthTaken = true,
                    ),
            ),
        )

        compose.onNodeWithText(text(R.string.index_error_decimals)).assertExists()
        compose.onNodeWithText(text(R.string.index_error_month_exists)).assertExists()
        compose.onNodeWithText(text(R.string.index_save_action)).performClick()

        assertTrue(PriceIndexAction.Save in actions)
    }
}

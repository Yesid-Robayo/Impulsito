package io.github.yesidrobayo.shopledger.ui.region

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.settings.CsvSeparator
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import kotlinx.datetime.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `Corregir la moneda` en el teléfono: solo aparece con la moneda bloqueada, la hoja muestra las
 * monedas que le pasa la pantalla, ya filtradas por decimales, y el diálogo dice que los montos no
 * se convierten y no confirma sin marcar que se entiende. Las monedas son códigos de prueba.
 */
@RunWith(AndroidJUnit4::class)
class CurrencyCorrectionScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val actions = mutableListOf<RegionalSettingsAction>()
    private val xtu = RegionalOption("XTU", "Third test money", "XTU", "third test money xtu")
    private val xtv = RegionalOption("XTV", "Fourth test money", "XTV", "fourth test money xtv")

    private fun state(
        locked: Boolean = true,
        options: List<RegionalOption>? = listOf(xtu, xtv),
        symbol: String = "",
    ) = RegionalSettingsUiState.Ready(
        firstRun = false,
        draft =
            RegionalDraft(
                currency = requireNotNull(CurrencyCode.of("XTS")),
                decimals = 2,
                symbol = symbol,
                cashRounding = "",
                formatTag = "es",
                zoneId = "Etc/GMT+5",
                weekStart = DayOfWeek.MONDAY,
                csvSeparator = CsvSeparator.SEMICOLON,
            ),
        locked = locked,
        currencyName = null,
        formatName = "es",
        zoneName = null,
        isoDecimals = 2,
        cashRoundingStep = Money.ZERO,
        correctionOptions = options.takeIf { locked },
    )

    private fun show(state: RegionalSettingsUiState.Ready) {
        compose.setContent {
            PreviewTheme {
                RegionalSettingsScreen(state, onAction = { actions += it }, onBack = {})
            }
        }
    }

    private fun text(id: Int): String = resources.getString(id)

    @Test
    fun theActionAppearsOnlyWithTheCurrencyLocked() {
        show(state(locked = false))

        compose.onAllNodes(hasText(text(R.string.region_correct_currency_action)))
            .assertCountEquals(0)
    }

    @Test
    fun withTheCurrencyLockedTheActionOpensTheCorrectionSheet() {
        show(state())

        compose.onNodeWithText(text(R.string.region_correct_currency_help)).assertExists()
        compose.onNodeWithText(text(R.string.region_correct_currency_action)).performClick()

        assertEquals(listOf(RegionalSettingsAction.OpenSheet(RegionalSheet.CORRECTION)), actions)
    }

    @Test
    fun withoutAnotherCurrencyOfTheSameDecimalsItSaysSo() {
        show(state(options = emptyList()))

        compose.onNodeWithText(text(R.string.region_correct_currency_none)).assertExists()
        compose.onAllNodes(hasText(text(R.string.region_correct_currency_action)))
            .assertCountEquals(0)
    }

    @Test
    fun theSheetListsOnlyTheOfferedCurrencies() {
        show(
            state().copy(
                sheet = RegionalSheet.CORRECTION,
                sheetOptions = listOf(xtu, xtv),
            ),
        )

        compose.onNodeWithText(text(R.string.region_correct_currency_title)).assertExists()
        compose.onAllNodes(hasText("Other test money")).assertCountEquals(0)
        compose.onNodeWithText(xtu.title).performClick()

        assertEquals(listOf(RegionalSettingsAction.Pick("XTU")), actions)
    }

    @Test
    fun theDialogSaysAmountsStayAndConfirmsOnlyAfterTheCheck() {
        show(state().copy(correctionTarget = xtu))
        val currency = resources.getString(R.string.region_option_summary, xtu.title, xtu.key)

        compose.onNodeWithText(
            resources.getString(R.string.region_correct_currency_confirm_title, currency),
        ).assertExists()
        compose.onNodeWithText(
            resources.getString(R.string.region_correct_currency_confirm_body, currency),
        ).assertExists()

        // Sin marcar que se entiende, el botón no confirma.
        val confirm = text(R.string.region_correct_currency_confirm_action)
        compose.onNodeWithText(confirm).performClick()
        assertEquals(emptyList<RegionalSettingsAction>(), actions)

        compose.onNodeWithText(text(R.string.region_correct_currency_check)).performClick()
        compose.onNodeWithText(confirm).performClick()
        assertEquals(listOf(RegionalSettingsAction.ConfirmCorrection), actions)
    }

    @Test
    fun aCustomSymbolIsSaidToBeKept() {
        show(state(symbol = "¤").copy(correctionTarget = xtu))
        val currency = resources.getString(R.string.region_option_summary, xtu.title, xtu.key)

        val body = R.string.region_correct_currency_confirm_body_symbol
        compose.onNodeWithText(resources.getString(body, currency, "¤")).assertExists()
    }

    @Test
    fun whileCorrectingTheScreenSaysSo() {
        show(state().copy(correcting = true))
        compose.onNodeWithText(text(R.string.region_correcting_currency)).assertExists()
        compose.onAllNodes(hasText(text(R.string.region_correct_currency_action)))
            .assertCountEquals(0)
    }

    @Test
    fun aFailureShowsItsReason() {
        show(state().copy(correctionError = CurrencyCorrectionError.UNSYNCED_COPIES))

        compose.onNodeWithText(text(R.string.region_correct_error_unsynced)).assertExists()
        compose.onNodeWithText(text(R.string.region_correct_currency_action)).assertExists()
    }
}

package io.github.yesidrobayo.shopledger.ui.region

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
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
 * La pantalla regional en el teléfono: la vista previa cambia en vivo con el formato, una moneda sin
 * confirmar se marca con texto y no solo con color, y con la moneda bloqueada no hay forma de
 * cambiarla. La moneda es la de prueba de ISO 4217.
 */
@RunWith(AndroidJUnit4::class)
class RegionalSettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val xts = requireNotNull(CurrencyCode.of("XTS"))

    private fun state(formatTag: String, currency: CurrencyCode = xts, locked: Boolean = false) =
        RegionalSettingsUiState.Ready(
            firstRun = false,
            draft =
                RegionalDraft(
                    currency = currency,
                    decimals = 2,
                    symbol = "",
                    cashRounding = "",
                    formatTag = formatTag,
                    zoneId = "Etc/GMT+5",
                    weekStart = DayOfWeek.MONDAY,
                    csvSeparator = CsvSeparator.SEMICOLON,
                ),
            locked = locked,
            currencyName = null,
            formatName = formatTag,
            zoneName = null,
            isoDecimals = 2,
            cashRoundingStep = Money.ZERO,
        )

    private fun shows(text: String) = compose.onAllNodes(
        hasText(text, substring = true) or hasContentDescription(text, substring = true),
    )

    @Test
    fun thePreviewChangesLiveWithTheFormat() {
        val current = mutableStateOf<RegionalSettingsUiState>(state("es"))
        compose.setContent {
            PreviewTheme { RegionalSettingsScreen(current.value, onAction = {}, onBack = {}) }
        }
        // Las cifras de muestra, 12345,67, con coma decimal y punto de miles.
        assertEquals(true, shows("12.345,67").fetchSemanticsNodes().isNotEmpty())

        current.value = state("en")

        compose.waitForIdle()
        assertEquals(true, shows("12,345.67").fetchSemanticsNodes().isNotEmpty())
        shows("12.345,67").assertCountEquals(0)
    }

    @Test
    fun anUnconfirmedCurrencySaysSoInWords() {
        val actions = mutableListOf<RegionalSettingsAction>()
        compose.setContent {
            PreviewTheme {
                RegionalSettingsScreen(
                    state("es", currency = CurrencyCode.UNCONFIRMED),
                    onAction = { actions += it },
                    onBack = {},
                )
            }
        }

        compose.onNodeWithText(
            resources.getString(R.string.region_currency_unconfirmed),
        ).assertExists()
        compose.onNodeWithText(resources.getString(R.string.region_currency)).performClick()
        assertEquals(listOf(RegionalSettingsAction.OpenSheet(RegionalSheet.CURRENCY)), actions)
    }

    @Test
    fun aLockedCurrencyCannotBeOpenedAndSaysWhy() {
        val actions = mutableListOf<RegionalSettingsAction>()
        compose.setContent {
            PreviewTheme {
                RegionalSettingsScreen(state("es", locked = true), onAction = {
                    actions += it
                }, onBack = {})
            }
        }

        compose.onNodeWithText(resources.getString(R.string.region_locked_help)).assertExists()
        compose.onNodeWithText(resources.getString(R.string.region_currency)).performClick()
        assertEquals(emptyList<RegionalSettingsAction>(), actions)
        // Ya no se anuncia el bloqueo: ya pasó.
        shows(resources.getString(R.string.region_lock_warning)).assertCountEquals(0)
    }
}

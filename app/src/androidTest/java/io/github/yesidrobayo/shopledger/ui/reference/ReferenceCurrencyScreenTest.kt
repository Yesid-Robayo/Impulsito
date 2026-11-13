package io.github.yesidrobayo.shopledger.ui.reference

import android.graphics.Bitmap
import android.graphics.Color.TRANSPARENT
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.pricing.FxRate
import io.github.yesidrobayo.shopledger.domain.model.pricing.NewFxRate
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.model.settings.ReferenceCurrency
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import java.io.File
import kotlin.time.Instant
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La moneda de referencia en el teléfono: sin moneda lleva a elegirla, sin cotización lo dice sin
 * suponer un valor, la vigente y el historial se leen con su fecha y su variación, la hoja avisa un
 * salto sin bloquear y marca una cotización en cero, y el botón fijo registra. Con el argumento
 * `captureDir` deja las capturas de la puerta de calidad en claro, oscuro y modo discreto. Códigos y
 * cotizaciones inventados.
 */
@RunWith(AndroidJUnit4::class)
class ReferenceCurrencyScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val resources = instrumentation.targetContext.resources
    private val captureDir: File? =
        InstrumentationRegistry.getArguments().getString("captureDir")?.let { name ->
            File(instrumentation.targetContext.cacheDir, name).apply { mkdirs() }
        }
    private val actions = mutableListOf<ReferenceCurrencyAction>()
    private val reference = ReferenceCurrency(requireNotNull(CurrencyCode.of("XXX")), 2)
    private val today = BusinessDate(LocalDate(2026, 11, 13))

    private fun text(id: Int) = resources.getString(id)

    private fun count(label: String) =
        compose.onAllNodes(hasText(label, substring = true)).fetchSemanticsNodes().size

    private fun rate(id: Long, micro: Long, day: Int, note: String? = null) = FxRate(
        id,
        NewFxRate(
            reference.code,
            micro,
            note,
            Instant.fromEpochMilliseconds(id),
            BusinessDate(LocalDate(2026, 11, day)),
            Instant.fromEpochMilliseconds(id),
        ),
    )

    private val withRates: ReferenceCurrencyUiState.Ready
        get() {
            val newer =
                RateRow(rate(3, 4_150_500_000, 13, "Casa de cambio del centro"), BasisPoints(376))
            val middle = RateRow(rate(2, 4_000_000_000, 6), BasisPoints(-123))
            val older = RateRow(rate(1, 4_049_800_000, 1), null)
            return ReferenceCurrencyUiState.Ready(
                reference = reference,
                latest = newer,
                history = listOf(newer, middle, older),
                calendar = RateCalendar(today, DayOfWeek.MONDAY),
            )
        }

    @Test
    fun withoutAReferenceItLeadsToChooseOne() {
        show(ReferenceCurrencyUiState.Ready(null, null, emptyList()))

        compose.onNodeWithText(text(R.string.reference_empty_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.reference_empty_action)).performClick()
        assertEquals(
            listOf<ReferenceCurrencyAction>(ReferenceCurrencyAction.ChooseCurrency),
            actions,
        )
        assertEquals(0, count(text(R.string.reference_register_action)))
    }

    @Test
    fun withoutARateItSaysSoAndNeverAssumesOne() {
        show(ReferenceCurrencyUiState.Ready(reference, null, emptyList()))

        compose.onNodeWithText(text(R.string.reference_no_rate_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.reference_register_action)).performClick()
        assertEquals(listOf<ReferenceCurrencyAction>(ReferenceCurrencyAction.StartRate), actions)
    }

    @Test
    fun theCurrentRateAndTheHistoryShowDateNoteAndChange() {
        show(withRates)

        compose.onNodeWithText(text(R.string.reference_history_title)).assertIsDisplayed()
        compose.onNodeWithText("Casa de cambio del centro").assertIsDisplayed()
        // La vigente arriba y otra vez en el historial.
        assertEquals(2, count("XXX = 4.150,5"))
    }

    @Test
    fun theSheetWarnsAJumpAndMarksAZeroRate() {
        show(
            withRates.copy(
                sheet = RateSheet(
                    rateText = "0",
                    date = today,
                    problem = RateProblem.NOT_POSITIVE,
                    jumpWarning = true,
                    variation = BasisPoints(9_000),
                ),
            ),
        )

        compose.onNodeWithText(text(R.string.reference_error_zero)).assertIsDisplayed()
        assertEquals(1, count(text(R.string.reference_warning_jump).substringBefore("%1")))
    }

    @Test
    fun capturesEveryStateInBothModesAndDiscreet() {
        val dir = captureDir
        assumeTrue("Sin captureDir no hay capturas", dir != null)
        val states = listOf(
            "empty" to ReferenceCurrencyUiState.Ready(null, null, emptyList()),
            "no-rate" to ReferenceCurrencyUiState.Ready(reference, null, emptyList()),
            "rates" to withRates,
            "sheet" to withRates.copy(
                sheet = RateSheet(
                    rateText = "41505",
                    date = today,
                    jumpWarning = true,
                    variation = BasisPoints(90_002),
                ),
            ),
            "loading" to ReferenceCurrencyUiState.Loading,
            "read-failed" to ReferenceCurrencyUiState.ReadFailed,
        )
        for (dark in listOf(false, true)) {
            for ((name, state) in states) {
                show(state, dark = dark)
                shoot(requireNotNull(dir), "$name-${if (dark) "dark" else "light"}")
            }
        }
        show(withRates, discreet = true)
        shoot(requireNotNull(dir), "rates-discreet")
    }

    private fun show(
        state: ReferenceCurrencyUiState,
        dark: Boolean = false,
        discreet: Boolean = false,
    ) {
        actions.clear()
        compose.runOnUiThread {
            val bars = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { dark }
            compose.activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            compose.activity.setContent {
                AppTheme(palette = ColorPalette.INDIGO_MUSTARD, darkTheme = dark) {
                    CompositionLocalProvider(
                        LocalAppFormats provides previewFormats(),
                        LocalDiscreetMode provides discreet,
                    ) {
                        ReferenceCurrencyScreen(state, emptyList()) { actions += it }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun shoot(dir: File, name: String) {
        compose.waitForIdle()
        SystemClock.sleep(SETTLE_MILLIS)
        val shot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(dir, "$name.png").outputStream().use {
            shot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        shot.recycle()
    }

    private companion object {
        const val SETTLE_MILLIS = 700L
    }
}

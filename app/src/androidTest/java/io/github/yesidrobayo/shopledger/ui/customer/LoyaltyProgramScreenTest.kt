package io.github.yesidrobayo.shopledger.ui.customer

import android.graphics.Bitmap
import android.graphics.Color.TRANSPARENT
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.customer.PointsSummary
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.ui.format.AppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El programa de puntos en pantalla: apagado solo invita a activarlo, activo muestra los montos, el
 * ejemplo y lo que hay, y cada acción llega a quien la maneja. Con el argumento `captureDir` también
 * deja las capturas de la puerta de calidad, en claro y oscuro.
 */
@RunWith(AndroidJUnit4::class)
class LoyaltyProgramScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val captureDir: File? =
        InstrumentationRegistry.getArguments().getString("captureDir")?.let { name ->
            File(context.cacheDir, name).apply { mkdirs() }
        }
    private val actions = mutableListOf<LoyaltyProgramAction>()

    @Test
    fun anOffProgramOnlyInvitesToTurnItOn() {
        show(OFF)

        compose.onNodeWithText(text(R.string.loyalty_enabled)).assertIsOff()
        compose.onNodeWithText(text(R.string.loyalty_not_money)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.loyalty_amount_per_point)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.loyalty_save_action)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.loyalty_empty_action)).performClick()

        assertEquals(listOf(LoyaltyProgramAction.SetEnabled(true)), actions)
    }

    @Test
    fun anActiveProgramShowsItsAmountsTheExampleAndThePoints() {
        show(ACTIVE)

        compose.onNodeWithText(text(R.string.loyalty_enabled)).assertIsOn()
        compose.onNodeWithText(text(R.string.loyalty_amount_per_point)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.loyalty_total_points, "1.250")).assertExists()
        compose.onNodeWithText(text(R.string.loyalty_customers_with_points, "14")).assertExists()
        compose.onNodeWithText(text(R.string.loyalty_save_action)).performClick()
        compose.onNodeWithText(text(R.string.loyalty_enabled)).performClick()

        assertEquals(
            listOf(LoyaltyProgramAction.Save, LoyaltyProgramAction.SetEnabled(false)),
            actions,
        )
    }

    @Test
    fun theCountsAreGroupedWithTheRegionalFormatNotTheLanguage() {
        val many = ACTIVE.copy(
            example = LoyaltyExample(Money(100_000_000), 100_000, 250_000, Money(1_250_000)),
            points = PointsSummary(1_234_567, 12_345),
        )
        show(many, formats = previewFormats(formatTag = "en-IN"))

        compose.onNodeWithText(text(R.string.loyalty_total_points, "12,34,567")).assertExists()
        compose.onNodeWithText(text(R.string.loyalty_customers_with_points, "12,345"))
            .assertExists()
        compose.onNodeWithText("1,00,000", substring = true).assertExists()
        compose.onNodeWithText("2,50,000", substring = true).assertExists()
    }

    @Test
    fun withoutCustomersItSaysSoAndOffersToOpenThem() {
        show(ACTIVE.copy(hasCustomers = false))

        compose.onNodeWithText(text(R.string.loyalty_no_customers)).assertExists()
        compose.onNodeWithText(text(R.string.customer_picker_view_all)).performClick()

        assertEquals(listOf(LoyaltyProgramAction.OpenCustomers), actions)
    }

    @Test
    fun turningOffAsksFirst() {
        show(ACTIVE.copy(confirmTurnOff = true))

        compose.onNodeWithText(text(R.string.loyalty_off_confirm_body)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.loyalty_off_confirm_action)).performClick()

        assertEquals(listOf(LoyaltyProgramAction.ConfirmTurnOff), actions)
    }

    @Test
    fun capturesEveryStateInBothModes() {
        val dir = captureDir
        assumeTrue("Sin captureDir no hay capturas", dir != null)
        val states =
            listOf(
                "off" to OFF,
                "active" to ACTIVE,
                "changed" to ACTIVE.copy(valuesChanged = true),
                "errors" to ERRORS,
                "no-customers" to ACTIVE.copy(hasCustomers = false, points = PointsSummary.NONE),
                "turn-off" to ACTIVE.copy(confirmTurnOff = true),
                "loading" to LoyaltyProgramUiState.Loading,
                "read-failed" to LoyaltyProgramUiState.ReadFailed,
            )
        for (dark in listOf(false, true)) {
            for ((name, state) in states) {
                show(state, dark)
                shoot(requireNotNull(dir), "$name-${if (dark) "dark" else "light"}")
            }
        }
    }

    private fun show(
        state: LoyaltyProgramUiState,
        dark: Boolean = false,
        formats: AppFormats = previewFormats(),
    ) {
        compose.runOnUiThread {
            val bars = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { dark }
            compose.activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            compose.activity.setContent {
                AppTheme(palette = ColorPalette.INDIGO_MUSTARD, darkTheme = dark) {
                    CompositionLocalProvider(LocalAppFormats provides formats) {
                        LoyaltyProgramScreen(state) { actions += it }
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

    private fun text(id: Int, vararg args: Any): String = context.getString(id, *args)

    private companion object {
        const val SETTLE_MILLIS = 700L

        val ACTIVE = LoyaltyProgramUiState.Ready(
            form = LoyaltyForm(enabled = true, amountPerPoint = "1000", pointValue = "5"),
            savedEnabled = true,
            valuesChanged = false,
            example = LoyaltyExample(Money(100_000), 10, 100, Money(500)),
            points = PointsSummary(1_250, 14),
            hasCustomers = true,
        )

        val OFF = ACTIVE.copy(
            form = LoyaltyForm(enabled = false, amountPerPoint = "", pointValue = ""),
            savedEnabled = false,
            example = null,
            points = PointsSummary.NONE,
        )

        val ERRORS = ACTIVE.copy(
            form = LoyaltyForm(
                enabled = true,
                amountPerPoint = "",
                pointValue = "99999999999999",
                amountPerPointError = LoyaltyAmountError.REQUIRED,
                pointValueError = LoyaltyAmountError.TOO_HIGH,
            ),
            savedEnabled = false,
            example = null,
            saveFailed = true,
        )
    }
}

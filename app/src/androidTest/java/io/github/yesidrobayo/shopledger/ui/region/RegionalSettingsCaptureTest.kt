package io.github.yesidrobayo.shopledger.ui.region

import android.graphics.Bitmap
import android.graphics.Color.TRANSPARENT
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.domain.model.settings.CsvSeparator
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import java.io.File
import kotlinx.datetime.DayOfWeek
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las capturas de la configuración regional para la puerta de calidad visual: cada estado con la
 * paleta predeterminada y otra, en claro y en oscuro, de arriba abajo. La escala de la letra y el
 * ancho de la ventana son los del teléfono: se corre en `Compact` y en `Expanded`, con la letra al
 * 100 % y al 200 %.
 *
 * Solo corre con el argumento `captureDir`. Los valores son de muestra: la moneda de prueba de ISO
 * 4217 y formatos sin país.
 */
@RunWith(AndroidJUnit4::class)
class RegionalSettingsCaptureTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val captureDir: File? =
        InstrumentationRegistry.getArguments().getString("captureDir")?.let { name ->
            File(instrumentation.targetContext.cacheDir, name).apply { mkdirs() }
        }

    private val xts = requireNotNull(CurrencyCode.of("XTS"))

    private fun state(
        firstRun: Boolean = false,
        currency: CurrencyCode = xts,
        locked: Boolean = false,
        formatTag: String = "es-419",
        errors: Set<RegionalFieldError> = emptySet(),
        saveFailed: Boolean = false,
        confirmingZone: Boolean = false,
    ) = RegionalSettingsUiState.Ready(
        firstRun = firstRun,
        draft =
            RegionalDraft(
                currency = currency,
                decimals = 2,
                symbol = "",
                cashRounding = if (locked) "0.05" else "",
                formatTag = formatTag,
                zoneId = "Etc/GMT+5",
                weekStart = DayOfWeek.MONDAY,
                csvSeparator = CsvSeparator.SEMICOLON,
            ),
        locked = locked,
        currencyName = if (currency.isConfirmed) "Testing Currency Code" else null,
        formatName = formatTag,
        zoneName = null,
        isoDecimals = if (currency.isConfirmed) 2 else null,
        cashRoundingStep = if (locked) Money(5) else Money.ZERO,
        errors = errors,
        saveFailed = saveFailed,
        confirmingZone = confirmingZone,
    )

    private val states =
        linkedMapOf(
            "first-run-unconfirmed" to
                state(
                    firstRun = true,
                    currency = CurrencyCode.UNCONFIRMED,
                    errors = setOf(RegionalFieldError.CURRENCY_MISSING),
                ),
            "editing" to state(formatTag = "pt"),
            "locked" to state(locked = true, formatTag = "en-150"),
            "errors" to
                state(
                    errors = setOf(
                        RegionalFieldError.SYMBOL_LENGTH,
                        RegionalFieldError.ZONE_UNKNOWN,
                    ),
                    saveFailed = true,
                ),
            "zone-dialog" to state(confirmingZone = true),
            "unavailable" to RegionalSettingsUiState.Unavailable,
        )

    @Test
    fun capturesEveryStateInBothPalettesAndModes() {
        val dir = captureDir
        assumeTrue("Sin captureDir no hay capturas", dir != null)
        for (palette in listOf(ColorPalette.INDIGO_MUSTARD, ColorPalette.GARNET_HONEY)) {
            for (dark in listOf(false, true)) {
                states.forEach { (name, uiState) ->
                    compose.runOnUiThread {
                        val bars = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { dark }
                        compose.activity.enableEdgeToEdge(
                            statusBarStyle = bars,
                            navigationBarStyle = bars,
                        )
                        compose.activity.setContent {
                            AppTheme(palette = palette, darkTheme = dark) {
                                RegionalSettingsScreen(uiState, onAction = {}, onBack = {})
                            }
                        }
                    }
                    val base = "${palette.name.lowercase()}-${if (dark) "dark" else "light"}-$name"
                    shoot(File(requireNotNull(dir), "$base-1.png"))
                    // La parte de abajo del formulario, si la hay.
                    val bottom = compose.onAllNodesWithTextOrNull(CSV_LABEL)
                    if (bottom) {
                        compose.onNodeWithText(CSV_LABEL, substring = true).performScrollTo()
                        shoot(File(dir, "$base-2.png"))
                    }
                }
            }
        }
    }

    private fun shoot(file: File) {
        compose.waitForIdle()
        SystemClock.sleep(SETTLE_MILLIS)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Sin captura" }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private companion object {
        const val SETTLE_MILLIS = 700L

        // Basta un trozo del texto en cualquiera de los tres idiomas: «CSV».
        const val CSV_LABEL = "CSV"
    }
}

/** Si hay algún nodo con [text] en su texto. */
private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTextOrNull(
    text: String,
): Boolean = onAllNodes(androidx.compose.ui.test.hasText(text, substring = true))
    .fetchSemanticsNodes()
    .isNotEmpty()

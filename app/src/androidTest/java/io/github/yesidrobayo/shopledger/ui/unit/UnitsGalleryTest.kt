package io.github.yesidrobayo.shopledger.ui.unit

import android.graphics.Bitmap
import android.graphics.Color.TRANSPARENT
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionEntry
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductConversion
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import java.io.File
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Capturas de la pantalla de unidades para la puerta de calidad visual: cada estado con la paleta
 * predeterminada y otra, en claro y en oscuro. La escala de la letra y el tamaño de la ventana son los
 * del sistema. Solo corre con el argumento `captureDir`. Sin regla de Compose: su actividad taparía
 * la de las capturas. Los datos son inventados.
 */
@RunWith(AndroidJUnit4::class)
class UnitsGalleryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun drawsEveryStateInBothPalettesAndModes() {
        val dir =
            InstrumentationRegistry.getArguments().getString("captureDir")?.let { name ->
                File(instrumentation.targetContext.cacheDir, name).apply { mkdirs() }
            }
        assumeTrue("Sin captureDir no hay capturas", dir != null)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            for (palette in listOf(ColorPalette.INDIGO_MUSTARD, ColorPalette.GARNET_HONEY)) {
                for (dark in listOf(false, true)) {
                    for (shot in shots()) {
                        scenario.onActivity { activity ->
                            val bars = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { dark }
                            activity.enableEdgeToEdge(
                                statusBarStyle = bars,
                                navigationBarStyle = bars,
                            )
                            activity.setContent {
                                AppTheme(palette = palette, darkTheme = dark) {
                                    CompositionLocalProvider(
                                        LocalAppFormats provides previewFormats(),
                                        LocalDiscreetMode provides shot.discreet,
                                    ) {
                                        key(shot.name) {
                                            UnitsScreen(
                                                uiState = shot.state,
                                                onAction = {},
                                                unitEditor = shot.unitEditor,
                                                conversionEditor = shot.conversionEditor,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        instrumentation.waitForIdleSync()
                        SystemClock.sleep(SETTLE_MILLIS)
                        val mode = if (dark) "dark" else "light"
                        save(
                            File(
                                requireNotNull(dir),
                                "${palette.name.lowercase()}-$mode-${shot.name}.png",
                            ),
                        )
                    }
                }
            }
        }
    }

    private class Shot(
        val name: String,
        val state: UnitsUiState,
        val unitEditor: UnitEditor? = null,
        val conversionEditor: ConversionEditor? = null,
        val discreet: Boolean = false,
    )

    private fun shots(): List<Shot> = listOf(
        Shot("units", UnitsScreenDeviceTest.READY),
        Shot("conversions", UnitsScreenDeviceTest.READY.copy(tab = UnitsTab.CONVERSIONS)),
        Shot(
            "empty",
            UnitsScreenDeviceTest.READY.copy(tab = UnitsTab.CONVERSIONS, conversions = emptyList()),
        ),
        Shot("loading", UnitsUiState()),
        Shot(
            "unit-sheet",
            UnitsScreenDeviceTest.READY,
            unitEditor =
                UnitEditor(
                    editingId = null,
                    isInitial = false,
                    name = "Bulto",
                    symbol = "",
                    kind = UnitKind.MASS,
                    allowsFraction = true,
                    nameProblem = UnitNameProblem.DUPLICATE,
                ),
        ),
        Shot(
            "conversion-sheet",
            UnitsScreenDeviceTest.READY.copy(tab = UnitsTab.CONVERSIONS),
            conversionEditor = UnitsScreenDeviceTest.EDITING,
        ),
        Shot(
            "conversion-discreet",
            UnitsScreenDeviceTest.READY.copy(tab = UnitsTab.CONVERSIONS),
            conversionEditor = UnitsScreenDeviceTest.EDITING.copy(
                saveFailed = SaveFailure(outOfSpace = false),
            ),
            discreet = true,
        ),
        Shot(
            "factor-confirm",
            UnitsScreenDeviceTest.READY.copy(tab = UnitsTab.CONVERSIONS),
            conversionEditor = UnitsScreenDeviceTest.EDITING.copy(
                factor = "12",
                confirmingFactorChange = true,
            ),
        ),
        Shot(
            "archive-dialog",
            UnitsScreenDeviceTest.READY.copy(
                dialog = UnitsDialog.Archive(UnitsScreenDeviceTest.KILO.id, "Kilogramo"),
            ),
        ),
    )

    private fun save(file: File) {
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Sin captura" }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private companion object {
        const val SETTLE_MILLIS = 900L
    }
}

package io.github.yesidrobayo.shopledger.ui.home

import android.graphics.Bitmap
import android.graphics.Color.TRANSPARENT
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.sale.SalesSummary
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Capturas de Inicio para la puerta de calidad visual: un día con ventas, con pérdida y un monto de
 * trece cifras, sin ventas todavía, el primer uso, el modo discreto, sin valor y con error. Con la
 * paleta predeterminada y otra, en claro y en oscuro. Solo corre con `captureDir`; la letra grande
 * y la ventana ancha se fijan en el emulador antes de correrla.
 */
@RunWith(AndroidJUnit4::class)
class HomeGalleryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val captureDir: File? =
        InstrumentationRegistry.getArguments().getString("captureDir")?.let { name ->
            File(instrumentation.targetContext.cacheDir, name).apply { mkdirs() }
        }

    @Test
    fun drawsEveryStateInBothPalettesAndModes() {
        val dir = captureDir
        assumeTrue("Sin captureDir no hay capturas", dir != null)
        val base = previewHomeContent()
        val scenes = listOf(
            Triple("sales", base as HomeUiState, false),
            Triple(
                "loss",
                base.copy(
                    hero = TodayHero.Sales(
                        SalesSummary(
                            Money(1_234_567_890_123L),
                            1_204,
                            Money(-35_200L),
                            Margin.Defined(BasisPoints(-1L)),
                        ),
                    ),
                ),
                false,
            ),
            Triple("noSales", base.copy(hero = TodayHero.NoSalesYet), false),
            Triple(
                "firstUse",
                base.copy(hero = TodayHero.NoSalesYet, firstUse = true, recentSales = emptyList()),
                false,
            ),
            Triple("discreet", base, true),
            Triple(
                "noValue",
                base.copy(today = null, zone = null, hero = TodayHero.Unavailable),
                false,
            ),
            Triple("error", HomeUiState.ReadError, false),
            Triple("lowStock", base, false),
        )
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            for (palette in listOf(ColorPalette.INDIGO_MUSTARD, ColorPalette.GARNET_HONEY)) {
                for (dark in listOf(false, true)) {
                    for ((name, state, discreet) in scenes) {
                        scenario.onActivity { activity ->
                            val bars = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { dark }
                            activity.enableEdgeToEdge(bars, bars)
                            activity.setContent {
                                AppTheme(palette = palette, darkTheme = dark) {
                                    CompositionLocalProvider(
                                        LocalAppFormats provides previewFormats(),
                                        LocalDiscreetMode provides discreet,
                                    ) {
                                        HomeScreen(
                                            uiState = state,
                                            onAction = {},
                                            profit = previewHomeProfit(),
                                            lowStock =
                                                if (name == "lowStock") {
                                                    previewHomeLowStock()
                                                } else {
                                                    null
                                                },
                                        )
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
                                "${palette.name.lowercase()}-$mode-$name.png",
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun save(file: File) {
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Sin captura" }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private companion object {
        const val SETTLE_MILLIS = 900L
    }
}

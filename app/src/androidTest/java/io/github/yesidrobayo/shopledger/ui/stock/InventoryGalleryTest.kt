package io.github.yesidrobayo.shopledger.ui.stock

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
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Capturas del inventario para la puerta de calidad visual: la lista por agotarse con stock
 * negativo, agotado y bajo, nombre de 80 caracteres y valor de trece cifras; nada por agotarse;
 * los avisos apagados; el modo discreto; abierto desde Inicio con flecha; `Revisar` con la cola
 * `Costo nuevo sin revisar`, también en modo discreto; el catálogo vacío y el error. Con la paleta
 * predeterminada y otra, en claro y en oscuro. Solo corre con `captureDir`; la letra grande se fija
 * en el emulador antes de correrla. Productos y montos inventados.
 */
@RunWith(AndroidJUnit4::class)
class InventoryGalleryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val captureDir: File? =
        InstrumentationRegistry.getArguments().getString("captureDir")?.let { name ->
            File(instrumentation.targetContext.cacheDir, name).apply { mkdirs() }
        }

    private data class Scene(
        val name: String,
        val state: InventoryUiState,
        val discreet: Boolean = false,
        val navigation: TopBarNavigation = TopBarNavigation.NONE,
        val notificationsBlocked: Boolean = false,
    )

    @Test
    fun drawsEveryStateInBothPalettesAndModes() {
        val dir = captureDir
        assumeTrue("Sin captureDir no hay capturas", dir != null)
        val scenes = listOf(
            Scene("lowStock", InventoryUiState(content = previewInventory())),
            Scene("nothingLow", InventoryUiState(content = previewInventory(items = emptyList()))),
            Scene(
                "notificationsOff",
                InventoryUiState(content = previewInventory()),
                notificationsBlocked = true,
            ),
            Scene("discreet", InventoryUiState(content = previewInventory()), discreet = true),
            Scene(
                "negativeFromHome",
                InventoryUiState(
                    chip = InventoryChip.NEGATIVE_STOCK,
                    content = previewInventory(items = previewInventoryItems().take(1)),
                ),
                navigation = TopBarNavigation.BACK,
            ),
            Scene(
                "review",
                InventoryUiState(
                    chip = InventoryChip.REVIEW,
                    content = previewInventory(reviewItems = previewReviewItems()),
                ),
            ),
            Scene(
                "reviewDiscreet",
                InventoryUiState(
                    chip = InventoryChip.REVIEW,
                    content = previewInventory(reviewItems = previewReviewItems()),
                ),
                discreet = true,
            ),
            Scene("emptyCatalog", InventoryUiState(content = InventoryContent.EmptyCatalog)),
            Scene("error", InventoryUiState(content = InventoryContent.ReadError)),
        )
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            for (palette in listOf(ColorPalette.INDIGO_MUSTARD, ColorPalette.GARNET_HONEY)) {
                for (dark in listOf(false, true)) {
                    for (scene in scenes) {
                        scenario.onActivity { activity ->
                            val bars = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { dark }
                            activity.enableEdgeToEdge(bars, bars)
                            activity.setContent {
                                AppTheme(palette = palette, darkTheme = dark) {
                                    CompositionLocalProvider(
                                        LocalAppFormats provides previewFormats(),
                                        LocalDiscreetMode provides scene.discreet,
                                    ) {
                                        InventoryScreen(
                                            uiState = scene.state,
                                            onAction = {},
                                            navigation = scene.navigation,
                                            notificationsBlocked = scene.notificationsBlocked,
                                        )
                                    }
                                }
                            }
                        }
                        instrumentation.waitForIdleSync()
                        SystemClock.sleep(SETTLE_MILLIS)
                        val mode = if (dark) "dark" else "light"
                        val file = "${palette.name.lowercase()}-$mode-${scene.name}.png"
                        save(File(requireNotNull(dir), file))
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

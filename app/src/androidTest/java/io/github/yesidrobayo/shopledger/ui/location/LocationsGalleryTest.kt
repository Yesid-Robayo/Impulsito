package io.github.yesidrobayo.shopledger.ui.location

import android.graphics.Bitmap
import android.graphics.Color.TRANSPARENT
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
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
 * Capturas de ubicaciones y transferencias para la puerta de calidad visual: la lista, una sola
 * ubicación, el stock de una con el aviso de archivar, las transferencias, su hoja con el aviso de
 * negativo y el formulario, en claro y en oscuro. La letra es la del sistema. Solo corre con
 * `captureDir`. Datos inventados.
 */
@RunWith(AndroidJUnit4::class)
class LocationsGalleryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val captureDir: File? =
        InstrumentationRegistry.getArguments().getString("captureDir")?.let { name ->
            File(instrumentation.targetContext.cacheDir, name).apply { mkdirs() }
        }

    @Test
    fun drawsLocationsAndTransfersInBothModes() {
        val dir = captureDir
        assumeTrue("Sin captureDir no hay capturas", dir != null)
        val detail = LocationSamples.ready().copy(
            selectedId = LocationSamples.STALL.id,
            detail = LocationDetailContent.Ready(
                LocationSamples.STALL,
                LocationSamples.ITEMS,
                hasStock = true,
            ),
            archiveBlocked = true,
        )
        val form = NewTransferUiState(
            loading = false,
            locations = listOf(LocationSamples.MAIN, LocationSamples.STALL),
            fromId = LocationSamples.MAIN.id,
            toId = LocationSamples.STALL.id,
            lines = listOf(
                TransferLineUi(1L, 7L, "Arroz 1 kg", LocationSamples.UNIT, "4"),
                TransferLineUi(
                    2L,
                    8L,
                    LocationSamples.CHEESE.details.name,
                    LocationSamples.KILOGRAM,
                    "4,5",
                    leftAtOrigin = QuantityMilli(-2_350L),
                ),
            ),
        )
        val scenes: List<Pair<String, @Composable () -> Unit>> = listOf(
            "list" to { LocationsScreen(LocationSamples.ready(), onAction = {}) },
            "single" to {
                LocationsScreen(
                    LocationsUiState(content = LocationsContent.Ready(LocationSamples.SINGLE)),
                    onAction = {},
                )
            },
            "detail" to { LocationsScreen(detail, onAction = {}) },
            "transfers" to {
                LocationsScreen(LocationSamples.ready(LocationsTab.TRANSFERS), onAction = {})
            },
            "transfer-sheet" to {
                LocationsScreen(
                    LocationSamples.ready(LocationsTab.TRANSFERS).copy(
                        transfer = TransferSheet.Ready(LocationSamples.DETAIL),
                    ),
                    onAction = {},
                )
            },
            "transfers-empty" to {
                LocationsScreen(
                    LocationSamples.ready(LocationsTab.TRANSFERS).copy(
                        transfers = TransfersContent.Ready(emptyList(), false, false),
                    ),
                    onAction = {},
                )
            },
            "form" to { NewTransferScreen(form, onAction = {}) },
            "form-missing" to {
                NewTransferScreen(
                    form.copy(
                        lines = emptyList(),
                        toId = LocationSamples.MAIN.id,
                        endError = TransferEnd.TO,
                        showNoLines = true,
                    ),
                    onAction = {},
                )
            },
        )
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            for (dark in listOf(false, true)) {
                for ((name, content) in scenes) {
                    scenario.onActivity { activity ->
                        val bars = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { dark }
                        activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                        activity.setContent {
                            AppTheme(palette = ColorPalette.INDIGO_MUSTARD, darkTheme = dark) {
                                CompositionLocalProvider(
                                    LocalAppFormats provides previewFormats(),
                                    LocalDiscreetMode provides false,
                                ) {
                                    key(name, dark) { content() }
                                }
                            }
                        }
                    }
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(SETTLE_MILLIS)
                    val mode = if (dark) "dark" else "light"
                    save(File(requireNotNull(dir), "$mode-locations-$name.png"))
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

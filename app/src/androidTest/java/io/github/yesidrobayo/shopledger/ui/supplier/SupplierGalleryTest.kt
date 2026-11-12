package io.github.yesidrobayo.shopledger.ui.supplier

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
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierFormIssue
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.product.ProductsTab
import io.github.yesidrobayo.shopledger.ui.product.ProductsTabs
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Capturas de la lista y el detalle de proveedores para la puerta de calidad visual: cada estado con
 * la paleta predeterminada y otra, en claro y en oscuro. La escala de la letra y el tamaño de la
 * ventana son los del sistema: se corre una vez por combinación.
 *
 * Solo corre con el argumento `captureDir`, que nombra la carpeta dentro de la caché de la app. Los
 * proveedores son inventados.
 */
@RunWith(AndroidJUnit4::class)
class SupplierGalleryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val captureDir: File? =
        InstrumentationRegistry.getArguments().getString("captureDir")?.let { name ->
            File(instrumentation.targetContext.cacheDir, name).apply { mkdirs() }
        }

    @Test
    fun drawsEveryStateInBothPalettesAndModes() {
        val dir = captureDir
        assumeTrue("Sin captureDir no hay capturas", dir != null)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            for (palette in listOf(ColorPalette.INDIGO_MUSTARD, ColorPalette.GARNET_HONEY)) {
                for (dark in listOf(false, true)) {
                    for ((name, screen) in states()) {
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
                                        LocalDiscreetMode provides (name == DISCREET),
                                    ) { key(name) { screen() } }
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

    private fun states(): List<Pair<String, @Composable () -> Unit>> {
        val tabs: @Composable () -> Unit = { ProductsTabs(ProductsTab.SUPPLIERS, {}) }
        val listed = SupplierListContent.Suppliers(SupplierSamples.active, SupplierSamples.archived)
        return listOf(
            "list" to {
                SupplierListScreen(
                    SupplierListUiState(content = listed, showArchived = true),
                    {},
                    tabs = tabs,
                )
            },
            "empty" to {
                SupplierListScreen(SupplierListUiState(content = SupplierListContent.Empty), {
                }, tabs = tabs)
            },
            "noresults" to {
                SupplierListScreen(
                    SupplierListUiState("sol", SupplierListContent.NoResults("sol")),
                    {},
                    tabs = tabs,
                )
            },
            "detail" to {
                SupplierDetailScreen(
                    SupplierDetailUiState(SupplierDetailContent.Ready(SupplierSamples.overview())),
                    {},
                )
            },
            DISCREET to {
                SupplierDetailScreen(
                    SupplierDetailUiState(SupplierDetailContent.Ready(SupplierSamples.overview())),
                    {},
                )
            },
            "archived" to {
                SupplierDetailScreen(
                    SupplierDetailUiState(
                        SupplierDetailContent.Ready(
                            SupplierSamples.overview(archived = true, purchases = false),
                        ),
                    ),
                    {},
                )
            },
            "delete" to {
                SupplierDetailScreen(
                    SupplierDetailUiState(
                        SupplierDetailContent.Ready(SupplierSamples.overview(purchases = false)),
                        dialog = SupplierDialog.Delete(1L, SupplierSamples.LONG_NAME),
                    ),
                    {},
                )
            },
            "sheet" to {
                SupplierEditorSheet(
                    SupplierEditor(
                        editingId = null,
                        name = "Granja El Alto",
                        phone = "555-CALL",
                        email = "compras",
                        issues =
                            setOf(SupplierFormIssue.PHONE_FORMAT, SupplierFormIssue.EMAIL_FORMAT),
                    ),
                    {},
                    asPanel = false,
                )
            },
        )
    }

    private fun save(file: File) {
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Sin captura" }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private companion object {
        const val SETTLE_MILLIS = 900L
        const val DISCREET = "discreet"
    }
}

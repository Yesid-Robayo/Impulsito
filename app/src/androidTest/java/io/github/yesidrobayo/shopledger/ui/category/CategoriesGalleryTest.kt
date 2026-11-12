package io.github.yesidrobayo.shopledger.ui.category

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
 * Capturas de las categorías para la puerta de calidad visual: productos, gastos con una archivada,
 * productos vacío, la hoja de renombrar una inicial y la confirmación de archivar, en claro y en
 * oscuro. La letra es la del
 * sistema. Solo corre con `captureDir`. Datos inventados.
 */
@RunWith(AndroidJUnit4::class)
class CategoriesGalleryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val captureDir: File? =
        InstrumentationRegistry.getArguments().getString("captureDir")?.let { name ->
            File(instrumentation.targetContext.cacheDir, name).apply { mkdirs() }
        }

    @Test
    fun drawsTheCategoriesInBothModes() {
        val dir = captureDir
        assumeTrue("Sin captureDir no hay capturas", dir != null)
        val ready = CategoriesContent.Ready(previewCategories())
        val scenes: List<Pair<String, @Composable () -> Unit>> = listOf(
            "products" to { CategoriesScreen(CategoriesUiState(content = ready), onAction = {}) },
            "expenses" to {
                CategoriesScreen(
                    CategoriesUiState(tab = CategoryTab.EXPENSES, content = ready),
                    onAction = {},
                )
            },
            "empty" to {
                CategoriesScreen(
                    CategoriesUiState(
                        content = CategoriesContent.Ready(
                            previewCategories().copy(products = emptyList()),
                        ),
                    ),
                    onAction = {},
                )
            },
            "sheet" to {
                CategoriesScreen(
                    CategoriesUiState(
                        tab = CategoryTab.EXPENSES,
                        content = ready,
                        sheet = CategorySheet(
                            CategoryTab.EXPENSES,
                            editingId = 11,
                            name = "",
                            isFixed = true,
                            initial = true,
                            error = CategoryNameError.DUPLICATE,
                        ),
                    ),
                    onAction = {},
                )
            },
            "archive" to {
                CategoriesScreen(
                    CategoriesUiState(
                        tab = CategoryTab.EXPENSES,
                        content = ready,
                        confirm = CategoryConfirm.ArchiveExpense(11, "Alquiler"),
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
                    save(File(requireNotNull(dir), "$mode-categories-$name.png"))
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

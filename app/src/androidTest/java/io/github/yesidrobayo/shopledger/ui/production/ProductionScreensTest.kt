package io.github.yesidrobayo.shopledger.ui.production

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La lista, el detalle y el formulario de producción en el dispositivo: cada estado se ve, las
 * acciones llegan y el botón principal queda a la vista. Con `captureDir`, las capturas de la
 * puerta de calidad en claro y en oscuro. Datos y cifras inventados.
 */
@RunWith(AndroidJUnit4::class)
class ProductionScreensTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val resources = instrumentation.targetContext.resources
    private val formActions = mutableListOf<ProductionFormAction>()
    private val listActions = mutableListOf<ProductionsAction>()

    private fun text(id: Int) = resources.getString(id)

    @Test
    fun theFormShowsTheInputsTheShortageAndTheResult() {
        show { ProductionFormScreen(previewProductionForm(), { formActions += it }) }

        compose.onNodeWithText(text(R.string.production_inputs_title)).assertIsDisplayed()
        scrollFormTo(text(R.string.production_warning_no_cost))
        scrollFormTo(text(R.string.production_total_cost_label))
        // El botón de registrar queda fijo abajo, con cualquier cantidad de insumos.
        compose.onNodeWithText(text(R.string.production_register_action)).performClick()
        assertTrue(ProductionFormAction.Register in formActions)
    }

    @Test
    fun withoutRecipesTheFormOffersToCreateOne() {
        show {
            ProductionFormScreen(
                ProductionFormUiState(loading = false, noRecipes = true),
                { formActions += it },
            )
        }

        compose.onNodeWithText(text(R.string.production_empty_no_recipe_action)).performClick()
        assertTrue(ProductionFormAction.CreateRecipe in formActions)
    }

    @Test
    fun theListOpensAProductionAndOffersANewOne() {
        show { ProductionsScreen(previewProductions(), { listActions += it }) }

        compose.onNodeWithText(text(R.string.status_voided)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.production_new_action)).performClick()
        assertTrue(ProductionsAction.NewProduction in listActions)
    }

    @Test
    fun theDetailWarnsBeforeVoiding() {
        show { ProductionsScreen(previewProductionDetail(), { listActions += it }) }

        compose.onNodeWithText(text(R.string.production_inputs_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.production_void_action)).performClick()
        assertTrue(ProductionsAction.AskVoid in listActions)
    }

    @Test
    fun anEmptyListWithoutRecipesOffersToCreateOne() {
        show { ProductionsScreen(ProductionsUiState(loading = false), { listActions += it }) }

        compose.onNodeWithText(text(R.string.production_empty_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.production_empty_no_recipe_action)).performClick()
        assertTrue(ProductionsAction.CreateRecipe in listActions)
    }

    @Test
    fun drawsProductionInBothModes() {
        val name = InstrumentationRegistry.getArguments().getString("captureDir")
        assumeTrue("Sin captureDir no hay capturas", name != null)
        val dir = File(instrumentation.targetContext.cacheDir, requireNotNull(name)).apply {
            mkdirs()
        }
        for (dark in listOf(false, true)) {
            val mode = if (dark) "dark" else "light"
            show(dark) { ProductionsScreen(previewProductions(), {}) }
            SystemClock.sleep(SETTLE_MILLIS)
            save(File(dir, "$mode-production-list.png"))
            show(dark) { ProductionsScreen(previewProductionDetail(), {}) }
            SystemClock.sleep(SETTLE_MILLIS)
            save(File(dir, "$mode-production-detail.png"))
            show(dark) { ProductionFormScreen(previewProductionForm(), {}) }
            SystemClock.sleep(SETTLE_MILLIS)
            save(File(dir, "$mode-production-form.png"))
            show(dark) { ProductionsScreen(ProductionsUiState(loading = false), {}) }
            SystemClock.sleep(SETTLE_MILLIS)
            save(File(dir, "$mode-production-empty.png"))
        }
    }

    private fun scrollFormTo(value: String) {
        compose.onNodeWithTag(PRODUCTION_FORM_TAG).performScrollToNode(hasText(value))
        compose.onNodeWithText(value).assertIsDisplayed()
    }

    private fun show(dark: Boolean = false, content: @Composable () -> Unit) {
        compose.runOnUiThread {
            compose.activity.setContent {
                AppTheme(palette = ColorPalette.INDIGO_MUSTARD, darkTheme = dark) {
                    CompositionLocalProvider(
                        LocalAppFormats provides previewFormats(),
                        LocalDiscreetMode provides false,
                    ) { content() }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun save(file: File) {
        val bitmap =
            requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Sin captura" }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private companion object {
        const val SETTLE_MILLIS = 900L
    }
}

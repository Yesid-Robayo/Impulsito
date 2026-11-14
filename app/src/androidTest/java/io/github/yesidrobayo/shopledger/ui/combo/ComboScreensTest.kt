package io.github.yesidrobayo.shopledger.ui.combo

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La lista de combos y la receta en el dispositivo: cada estado se ve, las acciones llegan, el
 * guardar queda a la vista y los errores de la fila se leen. Con `captureDir`, las capturas de la
 * puerta de calidad en claro y en oscuro. Datos y cifras inventados.
 */
@RunWith(AndroidJUnit4::class)
class ComboScreensTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val resources = instrumentation.targetContext.resources
    private val recipeActions = mutableListOf<RecipeAction>()
    private val listActions = mutableListOf<CombosAction>()

    private fun text(id: Int) = resources.getString(id)

    @Test
    fun theRecipeShowsItsRowsFiguresAndAvailability() {
        showRecipe(previewRecipe())

        compose.onNodeWithText(text(R.string.combo_components_title)).assertIsDisplayed()
        scrollToText(text(R.string.combo_no_stock_component))
        scrollToText(text(R.string.combo_cost_label))
        scrollToText(text(R.string.combo_warning_no_cost))
        scrollToText(resources.getString(R.string.combo_limited_by, "Arroz 1 kg"))
        // El botón de guardar queda fijo abajo, a la vista con cualquier receta.
        compose.onNodeWithText(text(R.string.combo_save_action)).assertIsDisplayed()
    }

    @Test
    fun aCycleIsExplainedOnItsRow() {
        showRecipe(previewRecipe())

        val cycle = resources.getString(
            R.string.combo_error_cycle,
            previewRecipe().comboName,
        )
        scrollToText(cycle)
    }

    @Test
    fun anEmptyRecipeOffersTheFirstComponentAndSaving() {
        showRecipe(RecipeUiState(loading = false, comboName = "Kit de té"))

        compose.onNodeWithText(text(R.string.combo_detail_empty_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.combo_add_component_action)).performClick()
        compose.onNodeWithText(text(R.string.combo_save_action)).performClick()
        compose.onNodeWithContentDescription(text(R.string.combo_edit_product_action))
            .performClick()

        assertEquals(
            listOf(RecipeAction.OpenPicker, RecipeAction.Save, RecipeAction.EditProduct),
            recipeActions,
        )
    }

    @Test
    fun aSaveFailureKeepsTheRecipeAndOffersToRetry() {
        showRecipe(previewRecipe().copy(notice = RecipeNotice.SaveFailed(outOfSpace = false)))

        compose.onNodeWithText(text(R.string.state_error_save)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.action_retry)).performClick()

        assertTrue(RecipeAction.Retry in recipeActions)
    }

    @Test
    fun leavingWithChangesAsksFirst() {
        showRecipe(previewRecipe().copy(discardAsked = true))

        compose.onNodeWithText(text(R.string.dialog_discard_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.dialog_discard_action)).performClick()

        assertEquals(listOf<RecipeAction>(RecipeAction.ConfirmLeave), recipeActions)
    }

    @Test
    fun theListOpensACombosAndCreatesOne() {
        showList(previewCombos())

        compose.onNodeWithText("Arroz 1 kg").performClick()
        compose.onNodeWithText(text(R.string.combo_new_action)).performClick()

        assertEquals(listOf(CombosAction.Open(1L), CombosAction.NewCombo), listActions)
    }

    @Test
    fun noCombosExplainsWhatTheyAreFor() {
        showList(CombosUiState(loading = false))

        compose.onNodeWithText(text(R.string.combo_empty_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.combo_empty_body)).assertIsDisplayed()
        val actions = compose.onAllNodesWithText(text(R.string.combo_new_action))
        assertEquals(1, actions.fetchSemanticsNodes().size)
    }

    @Test
    fun aSearchWithoutResultsSaysSo() {
        showList(previewCombos().copy(query = "zzz", items = emptyList()))

        val none = resources.getString(R.string.combo_search_no_results, "zzz")
        compose.onNodeWithText(none).assertIsDisplayed()
    }

    @Test
    fun drawsCombosAndRecipeInBothModes() {
        val name = InstrumentationRegistry.getArguments().getString("captureDir")
        assumeTrue("Sin captureDir no hay capturas", name != null)
        val dir = File(instrumentation.targetContext.cacheDir, requireNotNull(name)).apply {
            mkdirs()
        }
        for (dark in listOf(false, true)) {
            val mode = if (dark) "dark" else "light"
            showList(previewCombos(), dark)
            SystemClock.sleep(SETTLE_MILLIS)
            save(File(dir, "$mode-combos-list.png"))
            showList(CombosUiState(loading = false), dark)
            SystemClock.sleep(SETTLE_MILLIS)
            save(File(dir, "$mode-combos-empty.png"))
            showRecipe(previewRecipe(), dark)
            SystemClock.sleep(SETTLE_MILLIS)
            save(File(dir, "$mode-recipe.png"))
            showRecipe(RecipeUiState(loading = false, comboName = "Kit de té"), dark)
            SystemClock.sleep(SETTLE_MILLIS)
            save(File(dir, "$mode-recipe-empty.png"))
        }
    }

    /** Desplaza la receta hasta el texto [value], que la lista compone solo al acercarse. */
    private fun scrollToText(value: String) {
        compose.onNodeWithTag(RECIPE_LIST_TAG).performScrollToNode(hasText(value))
        compose.onNodeWithText(value).assertIsDisplayed()
    }

    private fun showRecipe(state: RecipeUiState, dark: Boolean = false) {
        compose.runOnUiThread {
            compose.activity.setContent {
                AppTheme(palette = ColorPalette.INDIGO_MUSTARD, darkTheme = dark) {
                    CompositionLocalProvider(
                        LocalAppFormats provides previewFormats(),
                        LocalDiscreetMode provides false,
                    ) { RecipeScreen(state, { recipeActions += it }) }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun showList(state: CombosUiState, dark: Boolean = false) {
        compose.runOnUiThread {
            compose.activity.setContent {
                AppTheme(palette = ColorPalette.INDIGO_MUSTARD, darkTheme = dark) {
                    CompositionLocalProvider(
                        LocalAppFormats provides previewFormats(),
                        LocalDiscreetMode provides false,
                    ) { CombosScreen(state, { listActions += it }) }
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

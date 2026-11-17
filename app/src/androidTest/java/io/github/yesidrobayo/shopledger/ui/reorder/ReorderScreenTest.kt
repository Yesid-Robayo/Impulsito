package io.github.yesidrobayo.shopledger.ui.reorder

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.ui.components.PreviewSamples
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
 * La reposición sugerida en el dispositivo: los grupos por proveedor, las filas con su estado, el
 * selector que cambia la cantidad, `Producir` en lo que se hace con receta, `Compartir lista` y
 * `Crear compra`, y los dos vacíos. Con `captureDir`, las capturas de la puerta de calidad en claro
 * y en oscuro. Datos inventados.
 */
@RunWith(AndroidJUnit4::class)
class ReorderScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val resources = instrumentation.targetContext.resources
    private val actions = mutableListOf<ReorderAction>()

    private fun text(id: Int) = resources.getString(id)

    @Test
    fun theListGroupsBySupplierAndTheStepperChangesTheQuantity() {
        show { ReorderScreen(previewReorder(), { actions += it }) }

        compose.onNodeWithText(text(R.string.term_out_of_stock)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.reorder_cover_label)).assertIsDisplayed()
        val more = resources.getString(R.string.a11y_stepper_add_one, PreviewSamples.SHORT_NAME)
        compose.onAllNodesWithContentDescription(more).onFirst().performClick()
        assertTrue(ReorderAction.Increment(1L) in actions)
    }

    @Test
    fun eachGroupWithSomethingToBuySharesAndCreatesItsPurchase() {
        show { ReorderScreen(previewReorder(), { actions += it }) }

        scrollTo(text(R.string.reorder_create_purchase_action))
        compose.onNodeWithText(text(R.string.reorder_create_purchase_action)).performClick()
        compose.onNodeWithText(text(R.string.reorder_share_action)).performClick()
        assertTrue(ReorderAction.CreatePurchase(10L) in actions)
        assertTrue(ReorderAction.Share(10L) in actions)
        // El grupo sin proveedor va todo en cero: no ofrece ni compra ni lista.
        scrollTo(text(R.string.reorder_no_recent_sales))
        compose.onAllNodesWithText(text(R.string.reorder_create_purchase_action))
            .assertCountEquals(1)
    }

    @Test
    fun aProductWithARecipeOffersToProduceInsteadOfTheStepper() {
        val preview = previewReorder()
        val first = preview.groups.first()
        val made = first.lines[1]
        val groups = listOf(
            first.copy(
                lines = first.lines.map { it.copy(producible = it.productId == made.productId) },
            ),
        )
        show { ReorderScreen(preview.copy(groups = groups), { actions += it }) }

        compose.onNodeWithText(text(R.string.combo_produce_action)).performClick()
        assertTrue(ReorderAction.Produce(made.productId) in actions)
        // Lo que se produce no lleva selector de cantidad: no se le compra al proveedor.
        val moreMade = resources.getString(R.string.a11y_stepper_add_one, made.name)
        compose.onAllNodesWithContentDescription(moreMade).assertCountEquals(0)
        val moreBought = resources.getString(R.string.a11y_stepper_add_one, first.lines[0].name)
        compose.onAllNodesWithContentDescription(moreBought).onFirst().assertIsDisplayed()
    }

    @Test
    fun withoutSalesTheEmptyStateAsksForThem() {
        show {
            ReorderScreen(
                ReorderUiState(
                    loading = false,
                    coverText = "30",
                    empty = ReorderEmpty.NEEDS_SALES,
                ),
                { actions += it },
            )
        }

        compose.onNodeWithText(text(R.string.reorder_empty_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.reorder_empty_body)).assertIsDisplayed()
    }

    @Test
    fun aReadFailureOffersToRetry() {
        show {
            ReorderScreen(ReorderUiState(loading = false, loadFailed = true), { actions += it })
        }

        compose.onNodeWithText(text(R.string.action_retry)).performClick()
        assertTrue(ReorderAction.Retry in actions)
    }

    @Test
    fun drawsReorderInBothModes() {
        val name = InstrumentationRegistry.getArguments().getString("captureDir")
        assumeTrue("Sin captureDir no hay capturas", name != null)
        val dir = File(instrumentation.targetContext.cacheDir, requireNotNull(name)).apply {
            mkdirs()
        }
        for (dark in listOf(false, true)) {
            val mode = if (dark) "dark" else "light"
            show(dark) { ReorderScreen(previewReorder(), {}) }
            SystemClock.sleep(SETTLE_MILLIS)
            save(File(dir, "$mode-reorder-list.png"))
            show(dark) {
                ReorderScreen(
                    ReorderUiState(
                        loading = false,
                        coverText = "30",
                        empty = ReorderEmpty.NEEDS_SALES,
                    ),
                    {},
                )
            }
            SystemClock.sleep(SETTLE_MILLIS)
            save(File(dir, "$mode-reorder-empty.png"))
        }
    }

    private fun scrollTo(value: String) {
        compose.onNodeWithTag(REORDER_LIST_TAG).performScrollToNode(hasText(value))
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

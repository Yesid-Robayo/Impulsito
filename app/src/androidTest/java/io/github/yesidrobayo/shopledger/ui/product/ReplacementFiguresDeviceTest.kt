package io.github.yesidrobayo.shopledger.ui.product

import android.graphics.Bitmap
import android.graphics.Color.TRANSPARENT
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.report.ReplacementProfit
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.stats.StatisticsScreen
import io.github.yesidrobayo.shopledger.ui.stats.StatisticsUiState
import io.github.yesidrobayo.shopledger.ui.stats.previewStatisticsContent
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La reposición en el detalle de un producto y en `Números`, dibujada en el teléfono: rotulada
 * aparte de la ganancia en ventas, con su explicación, oculta en modo discreto, y sin ninguna cifra
 * cuando no hay costo de reposición o no hubo ventas. Con el argumento `captureDir` deja las capturas
 * de la puerta de calidad en claro, oscuro y modo discreto. Montos inventados.
 */
@RunWith(AndroidJUnit4::class)
class ReplacementFiguresDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val resources = instrumentation.targetContext.resources
    private val captureDir: File? =
        InstrumentationRegistry.getArguments().getString("captureDir")?.let { name ->
            File(instrumentation.targetContext.cacheDir, name).apply { mkdirs() }
        }

    private fun text(id: Int) = resources.getString(id)

    /** Las cifras ocultas: cada una se anuncia con el aviso de cifra oculta, no con sus puntos. */
    private fun discreetCount() = compose
        .onAllNodes(
            hasContentDescription(text(R.string.a11y_discreet_hidden)),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().size

    private fun count(label: String) =
        compose.onAllNodes(hasText(label, substring = true)).fetchSemanticsNodes().size

    private fun detail(replacement: Long): ProductDetailUiState {
        val product = ProductDetailSamples.product().copy(replacementCost = Money(replacement))
        val overview = ProductDetailSamples.overview(product)
        return ProductDetailUiState(
            content = ProductDetailContent.Ready(overview, ProductDetailSamples.pricing(overview)),
        )
    }

    private val replaced = previewStatisticsContent().copy(
        replacement = ReplacementProfit(
            Money(4_250_000_00),
            Money(3_100_000_00),
            Money(1_150_000_00),
            linesWithoutReplacement = 3,
        ),
    )

    @Test
    fun theDetailShowsReplacementApartWithItsHelp() {
        showDetail(detail(4_100_00))

        scrollTo(text(R.string.product_replacement_help))
        compose.onNodeWithText(text(R.string.product_replacement_cost)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.product_profit_if_replaced)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.product_replacement_help)).assertIsDisplayed()
    }

    @Test
    fun withoutReplacementCostTheDetailInventsNothing() {
        showDetail(detail(0))

        assertEquals(0, count(text(R.string.product_replacement_cost)))
        assertEquals(0, count(text(R.string.product_profit_if_replaced)))
    }

    @Test
    fun discreetModeHidesBothReplacementFigures() {
        showDetail(detail(0), discreet = true)
        scrollTo(text(R.string.term_average_cost))
        val without = discreetCount()
        showDetail(detail(4_100_00), discreet = true)
        scrollTo(text(R.string.product_replacement_help))

        // La reposición y la ganancia si se repone se ocultan, igual que el costo y el margen.
        assertEquals(without + 2, discreetCount())
    }

    @Test
    fun theStatisticsToolShowsApartWithItsHelpAndTheShortfall() {
        showStats(replaced)

        scrollTo(text(R.string.stats_replacement_help))
        compose.onNodeWithText(text(R.string.stats_replacement_help)).assertIsDisplayed()
        val missing = resources.getQuantityString(R.plurals.stats_replacement_missing, 3, 3)
        scrollTo(missing)
        compose.onNodeWithText(missing).assertIsDisplayed()
        // El encabezado y la fila llevan el mismo nombre.
        assertEquals(2, count(text(R.string.stats_tool_replacement)))
    }

    @Test
    fun withoutReplacementTheStatisticsToolDoesNotExist() {
        showStats(previewStatisticsContent())

        compose.onNodeWithText(text(R.string.stats_replacement_help)).assertDoesNotExist()
    }

    @Test
    fun capturesBothScreensInBothModesAndDiscreet() {
        val dir = captureDir
        assumeTrue("Sin captureDir no hay capturas", dir != null)
        for (dark in listOf(false, true)) {
            val mode = if (dark) "dark" else "light"
            showDetail(detail(4_100_00), dark = dark)
            scrollTo(text(R.string.product_replacement_help))
            shoot(requireNotNull(dir), "detail-$mode")
            showStats(replaced, dark = dark)
            scrollTo(resources.getQuantityString(R.plurals.stats_replacement_missing, 3, 3))
            shoot(requireNotNull(dir), "stats-$mode")
        }
        showDetail(detail(4_100_00), discreet = true)
        scrollTo(text(R.string.product_replacement_help))
        shoot(requireNotNull(dir), "detail-discreet")
        showStats(replaced, discreet = true)
        scrollTo(resources.getQuantityString(R.plurals.stats_replacement_missing, 3, 3))
        shoot(requireNotNull(dir), "stats-discreet")
    }

    private fun scrollTo(label: String) {
        // Con letra grande, una cifra larga se desplaza sola: la lista es el primer desplazable.
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(label))
        compose.waitForIdle()
    }

    private fun showDetail(
        state: ProductDetailUiState,
        dark: Boolean = false,
        discreet: Boolean = false,
    ) = show(dark, discreet) { ProductDetailScreen(state, onAction = {}) }

    private fun showStats(
        state: StatisticsUiState,
        dark: Boolean = false,
        discreet: Boolean = false,
    ) = show(dark, discreet) { StatisticsScreen(state, onAction = {}) }

    private fun show(dark: Boolean, discreet: Boolean, content: @Composable () -> Unit) {
        compose.runOnUiThread {
            val bars = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { dark }
            compose.activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            compose.activity.setContent {
                AppTheme(palette = ColorPalette.INDIGO_MUSTARD, darkTheme = dark) {
                    CompositionLocalProvider(
                        LocalAppFormats provides previewFormats(),
                        LocalDiscreetMode provides discreet,
                    ) { content() }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun shoot(dir: File, name: String) {
        compose.waitForIdle()
        SystemClock.sleep(SETTLE_MILLIS)
        val shot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(dir, "$name.png").outputStream().use {
            shot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        shot.recycle()
    }

    private companion object {
        const val SETTLE_MILLIS = 700L
    }
}

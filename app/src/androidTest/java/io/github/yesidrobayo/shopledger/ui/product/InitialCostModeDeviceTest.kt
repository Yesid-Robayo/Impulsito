package io.github.yesidrobayo.shopledger.ui.product

import android.graphics.Bitmap
import android.graphics.Color.TRANSPARENT
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.ui.format.LocalAppFormats
import io.github.yesidrobayo.shopledger.ui.format.LocalDiscreetMode
import io.github.yesidrobayo.shopledger.ui.theme.AppTheme
import io.github.yesidrobayo.shopledger.ui.theme.previewFormats
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cómo se escribe el costo del stock inicial de un producto nuevo, dibujado en el teléfono: sin
 * stock inicial no hay nada que elegir; con él aparecen `Costo unitario` y el total, con la cifra
 * que sale de lo escrito debajo; elegir el total llega como acción, y con el total el campo del
 * costo cambia de nombre. Con el argumento `captureDir` deja las capturas de la puerta de calidad
 * en claro, oscuro y modo discreto. Montos inventados.
 */
@RunWith(AndroidJUnit4::class)
class InitialCostModeDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val resources = instrumentation.targetContext.resources
    private val unitCostLabel = resources.getString(R.string.form_unit_cost)
    private val totalLabel = resources.getString(R.string.product_initial_stock_total)
    private val costLabel = resources.getString(R.string.term_cost)
    private val captureDir: File? =
        InstrumentationRegistry.getArguments().getString("captureDir")?.let { name ->
            File(instrumentation.targetContext.cacheDir, name).apply { mkdirs() }
        }
    private val actions = mutableListOf<ProductEditAction>()

    private fun count(text: String) =
        compose.onAllNodes(hasText(text)).fetchSemanticsNodes().size

    @Test
    fun withoutOpeningStockThereIsNoCostModeToChoose() {
        show(state(stock = "", shown = false))

        assertEquals(0, count(unitCostLabel))
        assertEquals(0, count(totalLabel))
        compose.onNodeWithText(costLabel).performScrollTo()
    }

    @Test
    fun withOpeningStockThePerUnitCostShowsTheTotalItAddsUpTo() {
        show(state(stock = "1000", shown = true, derived = Money(100_000_000L)))

        // El chip del total y la fila con el total que sale de lo escrito.
        assertEquals(2, count(totalLabel))
        assertEquals(1, count(unitCostLabel))
        compose.onNodeWithText(costLabel).performScrollTo()
    }

    @Test
    fun choosingTheTotalArrivesAsAnAction() {
        show(state(stock = "1000", shown = true, derived = Money(100_000_000L)))

        compose.onNode(hasText(totalLabel).and(hasClickAction())).performScrollTo().performClick()

        assertEquals(ProductEditAction.CostModeChange(byTotal = true), actions.last())
    }

    @Test
    fun byTotalTheCostFieldIsNamedTheTotalAndTheUnitCostShowsBelow() {
        show(state(stock = "1000", shown = true, byTotal = true, derived = Money(1L)))

        // El chip y el nombre del campo dicen el total; el chip y la fila, el costo unitario.
        assertEquals(2, count(totalLabel))
        assertEquals(2, count(unitCostLabel))
        assertEquals(0, count(costLabel))
    }

    @Test
    fun capturesTheCostModeInBothThemesAndDiscreet() {
        val dir = captureDir
        assumeTrue("Sin captureDir no hay capturas", dir != null)
        val states = listOf(
            "unit" to state(stock = "1000", shown = true, derived = Money(100_000_000L)),
            "total" to state(stock = "1000", shown = true, byTotal = true, derived = Money(1L)),
        )
        for (dark in listOf(false, true)) {
            for ((name, state) in states) {
                show(state, dark = dark)
                compose.onNode(hasText(unitCostLabel).and(hasClickAction())).performScrollTo()
                shoot(requireNotNull(dir), "initial-cost-$name-${if (dark) "dark" else "light"}")
            }
        }
        show(states.last().second, discreet = true)
        compose.onNode(hasText(unitCostLabel).and(hasClickAction())).performScrollTo()
        shoot(requireNotNull(dir), "initial-cost-total-discreet")
    }

    private fun state(
        stock: String,
        shown: Boolean,
        byTotal: Boolean = false,
        derived: Money? = null,
    ) = ProductEditUiState(
        isNew = true,
        form = ProductEditForm(
            name = "Harina de trigo",
            unitId = COUNT.id,
            salePrice = "5",
            cost = "1000",
            initialStock = stock,
            costByTotal = byTotal,
        ),
        units = listOf(COUNT),
        costModeShown = shown,
        derivedCost = derived,
    )

    private fun show(state: ProductEditUiState, dark: Boolean = false, discreet: Boolean = false) {
        actions.clear()
        compose.runOnUiThread {
            val bars = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { dark }
            compose.activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            compose.activity.setContent {
                AppTheme(palette = ColorPalette.INDIGO_MUSTARD, darkTheme = dark) {
                    CompositionLocalProvider(
                        LocalAppFormats provides previewFormats(),
                        LocalDiscreetMode provides discreet,
                    ) {
                        ProductEditScreen(uiState = state, onAction = { actions += it })
                    }
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
        val COUNT = MeasureUnit(1L, "UNIT", null, null, UnitKind.COUNT, false, null)
    }
}

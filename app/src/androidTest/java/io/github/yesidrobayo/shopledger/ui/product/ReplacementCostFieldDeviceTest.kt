package io.github.yesidrobayo.shopledger.ui.product

import android.graphics.Bitmap
import android.graphics.Color.TRANSPARENT
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
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
 * El costo de reposición en `Más detalles` del formulario de un producto, dibujado en el teléfono:
 * vacío se queda vacío, con su ayuda; un error reemplaza la ayuda; un servicio no lo muestra; y lo
 * escrito llega como acción. Con el argumento `captureDir` deja las capturas de la puerta de calidad
 * en claro, oscuro y modo discreto. Montos inventados.
 */
@RunWith(AndroidJUnit4::class)
class ReplacementCostFieldDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val resources = instrumentation.targetContext.resources
    private val label = resources.getString(R.string.product_replacement_cost)
    private val help = resources.getString(R.string.product_replacement_cost_help)
    private val captureDir: File? =
        InstrumentationRegistry.getArguments().getString("captureDir")?.let { name ->
            File(instrumentation.targetContext.cacheDir, name).apply { mkdirs() }
        }
    private val actions = mutableListOf<ProductEditAction>()

    private fun count(text: String) =
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().size

    @Test
    fun aWrittenReplacementCostOpensTheDetailsWithItsHelp() {
        show(state("3100"))

        compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(help).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun anErrorTakesThePlaceOfTheHelp() {
        show(
            state("1,234").copy(
                errors = mapOf(ProductEditField.REPLACEMENT_COST to FieldProblem.TooHigh),
            ),
        )

        compose.onNodeWithText(label).performScrollTo()
        assertEquals(0, count(help))
        assertEquals(1, count(resources.getString(R.string.error_value_high)))
    }

    @Test
    fun whatIsWrittenArrivesAsAnAction() {
        show(state("3100"))

        compose.onNodeWithText("3100").performScrollTo().performTextReplacement("3200")

        assertEquals(ProductEditAction.ReplacementCostChange("3200"), actions.last())
    }

    @Test
    fun aServiceDoesNotShowTheField() {
        show(state("").copy(form = state("").form.copy(stockMode = ProductStockMode.UNTRACKED)))

        assertEquals(0, count(label))
    }

    @Test
    fun capturesTheFieldInBothModesAndDiscreet() {
        val dir = captureDir
        assumeTrue("Sin captureDir no hay capturas", dir != null)
        val states = listOf(
            "filled" to state("3100"),
            "error" to state("1,234").copy(
                errors = mapOf(ProductEditField.REPLACEMENT_COST to FieldProblem.TooHigh),
            ),
        )
        for (dark in listOf(false, true)) {
            for ((name, state) in states) {
                show(state, dark = dark)
                compose.onNodeWithText(label).performScrollTo()
                shoot(requireNotNull(dir), "replacement-$name-${if (dark) "dark" else "light"}")
            }
        }
        show(state("3100"), discreet = true)
        compose.onNodeWithText(label).performScrollTo()
        shoot(requireNotNull(dir), "replacement-filled-discreet")
    }

    private fun state(replacement: String) = ProductEditUiState(
        isNew = false,
        form = ProductEditForm(
            name = "Arroz 1 kg",
            unitId = COUNT.id,
            salePrice = "5000",
            cost = "3800",
            minStock = "5",
            replacementCost = replacement,
        ),
        units = listOf(COUNT),
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

package io.github.yesidrobayo.shopledger.ui.variant

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La pantalla de variantes en el teléfono: la matriz con dos ejes, las combinaciones ya creadas,
 * el botón con el número de variantes y los avisos. Los datos son inventados.
 */
@RunWith(AndroidJUnit4::class)
class VariantsScreenDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val resources = instrumentation.targetContext.resources

    private fun count(text: String) = compose
        .onAllNodes(hasText(text, substring = true) or hasContentDescription(text))
        .fetchSemanticsNodes()
        .size

    @Test
    fun twoAxesDrawOneCellPerCombinationAndTheButtonCountsTheCheckedOnes() {
        val actions = mutableListOf<VariantsAction>()
        compose.setContent { PreviewTheme { VariantsScreen(TWO_AXES, { actions += it }) } }

        // Seis combinaciones y una desmarcada: el botón crea cinco.
        val create = resources.getQuantityString(R.plurals.variant_create_action, 5, 5)
        assertEquals(1, count(create))
        val cell = resources.getString(R.string.variant_cell_description, "M", "Azul")
        compose.onNodeWithContentDescription(cell).performClick()
        val toggle = VariantsAction.ToggleCombo(VariantCombo("M", "Azul"))
        assertEquals(listOf<VariantsAction>(toggle), actions)
    }

    @Test
    fun createdCombinationsAreMarkedAndTheAxesCannotChange() {
        compose.setContent { PreviewTheme { VariantsScreen(EDITING, {}) } }

        assertEquals(1, count(resources.getString(R.string.variant_save_action)))
        // La fila de la lista; la celda lo dice como estado, no como texto.
        assertEquals(1, count(resources.getString(R.string.variant_created_status)))
        assertEquals(0, count(resources.getString(R.string.variant_add_axis_action)))
    }

    @Test
    fun aParentWithItsOwnStockIsExplainedAboveTheButton() {
        compose.setContent {
            PreviewTheme {
                VariantsScreen(TWO_AXES.copy(notice = VariantsNotice.ParentHasStock), {})
            }
        }

        assertEquals(1, count(resources.getString(R.string.variant_error_parent_stock)))
    }

    internal companion object {
        val COUNT = MeasureUnit(1L, "UNIT", null, null, UnitKind.COUNT, false, null)
        val SMALL_RED = VariantCombo("S", "Rojo")

        val TWO_AXES =
            VariantsUiState(
                loading = false,
                parentName = "Camiseta de algodón",
                unit = COUNT,
                firstAxis = "Talla",
                secondAxis = "Color",
                firstValues = listOf("S", "M", "L"),
                secondValues = listOf("Rojo", "Azul"),
                unchecked = setOf(VariantCombo("L", "Azul").key),
                applyAll = VariantInput(price = "25000"),
                inputs = mapOf(SMALL_RED.key to VariantInput(price = "25000", initialStock = "4")),
                prices = mapOf(SMALL_RED.key to Money(25_000L)),
                expanded = SMALL_RED.key,
            )

        val EDITING =
            TWO_AXES.copy(
                firstValues = listOf("S"),
                secondValues = listOf("Rojo"),
                unchecked = emptySet(),
                inputs = emptyMap(),
                prices = emptyMap(),
                expanded = null,
                applyAll = VariantInput(),
                existing = listOf(
                    ExistingVariant(
                        id = 2L,
                        name = "Camiseta de algodón · S · Rojo",
                        combo = SMALL_RED,
                        salePrice = Money(25_000L),
                        onHand = QuantityMilli(4_000L),
                        archived = false,
                    ),
                ),
            )
    }
}

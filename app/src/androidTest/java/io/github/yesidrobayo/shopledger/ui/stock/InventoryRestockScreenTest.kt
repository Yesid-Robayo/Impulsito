package io.github.yesidrobayo.shopledger.ui.stock

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductUsage
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * En el teléfono: la acción de cada fila del inventario. Un producto con receta dice `Producir` y
 * pide la producción, también un insumo; uno sin receta dice `Reponer` y pide la compra. Productos
 * inventados.
 */
@RunWith(AndroidJUnit4::class)
class InventoryRestockScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val actions = mutableListOf<InventoryAction>()

    private fun show(items: List<InventoryItem>) {
        compose.setContent {
            PreviewTheme {
                InventoryScreen(
                    uiState = InventoryUiState(content = previewInventory(items)),
                    onAction = { actions += it },
                )
            }
        }
    }

    @Test
    fun aProducibleRowAsksForTheProductionAndTheOtherForAPurchase() {
        val (bought, made) = previewInventoryItems().take(2)
        show(listOf(bought, made.copy(producible = true)))

        compose.onNodeWithText(resources.getString(R.string.combo_produce_action)).performClick()
        assertEquals(InventoryAction.Produce(made.id), actions.last())

        compose.onNodeWithText(resources.getString(R.string.stock_restock)).performClick()
        assertEquals(InventoryAction.Restock(bought.id), actions.last())
    }

    @Test
    fun anInputWithARecipeIsAlsoProduced() {
        val input = previewInventoryItems().first { it.usage == ProductUsage.INPUT }
        show(listOf(input.copy(producible = true)))

        compose.onAllNodesWithText(resources.getString(R.string.stock_restock))
            .assertCountEquals(0)
        compose.onNodeWithText(resources.getString(R.string.combo_produce_action)).performClick()
        assertEquals(InventoryAction.Produce(input.id), actions.single())
    }
}

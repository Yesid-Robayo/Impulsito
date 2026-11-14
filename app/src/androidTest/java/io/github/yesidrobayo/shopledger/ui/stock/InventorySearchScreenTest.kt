package io.github.yesidrobayo.shopledger.ui.stock

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductUsage
import io.github.yesidrobayo.shopledger.ui.components.PreviewSamples
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * En el teléfono: el inventario tiene la búsqueda de la lista de productos, con el escáner si
 * algún producto tiene código; lo escrito llega como acción, una búsqueda sin resultados lo dice
 * con su propio estado vacío, y un insumo lleva su etiqueta en la fila. Productos inventados.
 */
@RunWith(AndroidJUnit4::class)
class InventorySearchScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val actions = mutableListOf<InventoryAction>()
    private var scans = 0

    private fun show(state: InventoryUiState, canScan: Boolean) {
        compose.setContent {
            PreviewTheme {
                InventoryScreen(
                    uiState = state,
                    onAction = { actions += it },
                    onScan = if (canScan) ({ scans++ }) else null,
                )
            }
        }
    }

    @Test
    fun theSearchFieldSendsWhatIsTypedAndOffersTheScanner() {
        show(InventoryUiState(content = previewInventory()), canScan = true)

        compose.onNode(hasSetTextAction()).performTextInput("arr")
        assertEquals(InventoryAction.QueryChange("arr"), actions.last())

        compose.onNodeWithContentDescription(resources.getString(R.string.a11y_scan_code))
            .performClick()
        assertEquals(1, scans)
    }

    @Test
    fun withoutCodesThereIsNoScanner() {
        show(InventoryUiState(content = previewInventory()), canScan = false)

        compose.onNode(hasSetTextAction()).assertIsDisplayed()
        compose.onNodeWithContentDescription(resources.getString(R.string.a11y_scan_code))
            .assertDoesNotExist()
    }

    @Test
    fun aSearchWithNothingFoundSaysSoWithItsOwnEmptyState() {
        val query = PreviewSamples.SEARCH_QUERY
        show(
            InventoryUiState(
                content = previewInventory(items = emptyList()).copy(query = query),
                query = query,
            ),
            canScan = false,
        )

        compose.onNodeWithText(resources.getString(R.string.empty_search_title, query))
            .assertIsDisplayed()
        compose.onNodeWithText(resources.getString(R.string.inventory_search_empty_body))
            .assertIsDisplayed()
        compose.onNodeWithText(resources.getString(R.string.empty_low_stock_title))
            .assertDoesNotExist()
    }

    @Test
    fun anInputRowCarriesItsTag() {
        show(InventoryUiState(content = previewInventory()), canScan = false)

        val input = previewInventoryItems().first { it.usage == ProductUsage.INPUT }
        val tag = resources.getString(R.string.product_usage_input)
        compose.onNode(hasText(input.name) and hasText(tag)).assertExists()
        assertTrue(previewInventoryItems().count { it.usage == ProductUsage.INPUT } == 1)
    }
}

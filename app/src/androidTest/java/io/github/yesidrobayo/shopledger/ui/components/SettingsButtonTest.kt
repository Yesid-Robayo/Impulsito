package io.github.yesidrobayo.shopledger.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.product.ProductListScreen
import io.github.yesidrobayo.shopledger.ui.product.ProductListUiState
import io.github.yesidrobayo.shopledger.ui.product.ProductsTab
import io.github.yesidrobayo.shopledger.ui.product.ProductsTabs
import io.github.yesidrobayo.shopledger.ui.sale.SaleHistoryScreen
import io.github.yesidrobayo.shopledger.ui.sale.SaleHistoryUiState
import io.github.yesidrobayo.shopledger.ui.stats.StatisticsScreen
import io.github.yesidrobayo.shopledger.ui.stats.StatisticsUiState
import io.github.yesidrobayo.shopledger.ui.stock.InventoryScreen
import io.github.yesidrobayo.shopledger.ui.stock.InventoryUiState
import io.github.yesidrobayo.shopledger.ui.supplier.SupplierListScreen
import io.github.yesidrobayo.shopledger.ui.supplier.SupplierListUiState
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * En el teléfono: cada pestaña tiene el engranaje de los ajustes en su barra de arriba, y tocarlo
 * los abre. Como destino propio, con atrás, una pantalla no lo lleva.
 */
@RunWith(AndroidJUnit4::class)
class SettingsButtonTest {
    @get:Rule
    val compose = createComposeRule()

    private val label =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(
            R.string.a11y_settings,
        )
    private var opened = 0

    private fun show(screen: @Composable () -> Unit) {
        compose.setContent {
            PreviewTheme {
                CompositionLocalProvider(LocalSettingsOpener provides { opened += 1 }) { screen() }
            }
        }
    }

    private fun tapSettings() {
        compose.onNodeWithContentDescription(label).performClick()
        compose.waitForIdle()
        assertEquals(1, opened)
    }

    private val tabs: @Composable () -> Unit = { ProductsTabs(ProductsTab.PRODUCTS, {}) }

    @Test
    fun recordsOpensSettings() {
        show { SaleHistoryScreen(SaleHistoryUiState(), {}) }
        tapSettings()
    }

    @Test
    fun productsOpensSettings() {
        show { ProductListScreen(ProductListUiState(), {}, tabs = tabs) }
        tapSettings()
    }

    @Test
    fun inventoryAsATabOpensSettings() {
        show { InventoryScreen(InventoryUiState(), {}, tabs = tabs) }
        tapSettings()
    }

    @Test
    fun suppliersAsATabOpenSettings() {
        show { SupplierListScreen(SupplierListUiState(), {}, tabs = tabs) }
        tapSettings()
    }

    @Test
    fun numbersOpensSettings() {
        show { StatisticsScreen(StatisticsUiState.Loading, {}) }
        tapSettings()
    }

    @Test
    fun aScreenWithBackHasNoSettings() {
        show {
            InventoryScreen(InventoryUiState(), {}, navigation = TopBarNavigation.BACK)
        }
        compose.onAllNodesWithContentDescription(label).assertCountEquals(0)
    }
}

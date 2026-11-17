package io.github.yesidrobayo.shopledger.ui.product

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * En el teléfono: el detalle de un producto con receta ofrece `Producir` junto a `Registrar
 * compra`, y pide la producción; sin receta, solo la compra. Producto inventado.
 */
@RunWith(AndroidJUnit4::class)
class ProductDetailProduceDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val actions = mutableListOf<ProductDetailAction>()

    private fun show(producible: Boolean) {
        val overview = ProductDetailSamples.overview()
        val state = ProductDetailUiState(
            content = ProductDetailContent.Ready(overview, ProductDetailSamples.pricing(overview)),
            producible = producible,
        )
        compose.setContent {
            PreviewTheme { ProductDetailScreen(state, onAction = { actions += it }) }
        }
    }

    @Test
    fun aProductWithARecipeOffersToProduceNextToThePurchase() {
        show(producible = true)

        compose.onNodeWithText(resources.getString(R.string.empty_movements_action))
            .assertIsDisplayed()
        compose.onNodeWithText(resources.getString(R.string.combo_produce_action)).performClick()
        assertEquals(ProductDetailAction.Produce, actions.last())
    }

    @Test
    fun withoutARecipeOnlyThePurchaseIsOffered() {
        show(producible = false)

        compose.onNodeWithText(resources.getString(R.string.combo_produce_action))
            .assertDoesNotExist()
        compose.onNodeWithText(resources.getString(R.string.empty_movements_action)).performClick()
        assertEquals(ProductDetailAction.RegisterPurchase, actions.last())
    }
}

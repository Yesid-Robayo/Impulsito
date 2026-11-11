package io.github.yesidrobayo.shopledger.ui.sale

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.ui.product.sampleProduct
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La venta en el teléfono, con su ViewModel y un catálogo inventado: la venta habitual son dos toques
 * después de `Vender`, vender sin stock solo avisa, atrás con productos pregunta, y la pantalla se
 * acomoda a la letra grande y a una ventana ancha. TalkBack lee el resumen con su total.
 */
@RunWith(AndroidJUnit4::class)
class NewSaleScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val requests = mutableListOf<SaleRequest>()

    private fun text(id: Int, vararg args: Any) = resources.getString(id, *args)

    private fun plural(id: Int, count: Int) = resources.getQuantityString(id, count, count)

    private fun show(model: NewSaleViewModel, fontScale: Float? = null, width: Int? = null) {
        compose.setContent {
            PreviewTheme {
                val state by model.uiState.collectAsState()
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, fontScale ?: density.fontScale),
                ) {
                    val frame =
                        width?.let { Modifier.requiredSize(it.dp, WIDE_HEIGHT.dp) } ?: Modifier
                    Box(frame) { NewSaleScreen(state, model::onAction) }
                }
            }
        }
    }

    @Test
    fun aUsualSaleIsAProductAndRegisterAfterSell() {
        show(saleViewModel(requests = requests))

        compose.onNodeWithText("Arroz 1 kg").performClick()
        compose.onNodeWithText(text(R.string.sale_register)).assertIsEnabled().performClick()
        compose.waitUntil(TIMEOUT_MS) { requests.isNotEmpty() }

        val request = requests.single()
        assertEquals(listOf(SaleLineRequest(2L, QuantityMilli(1_000L))), request.lines)
        // Sin tocar el método, se cobra con el primero que se ofrece.
        assertEquals(1L, request.paymentMethodId)
    }

    @Test
    fun sellingWithoutStockOnlyWarnsAndStillRegisters() {
        val empty = sampleProduct(9L, "Pan tajado", 5_000L, 3_000L, 0L)
        show(saleViewModel(saleCatalog(listOf(empty)), requests))

        compose.onNodeWithText("Pan tajado").performClick()

        // El aviso dice cuánto quedaría, con la unidad en el idioma del teléfono.
        val warning = text(R.string.notice_oversell, "").trim()
        compose.onNodeWithText(warning, substring = true).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.sale_register)).performClick()
        compose.waitUntil(TIMEOUT_MS) { requests.isNotEmpty() }
    }

    @Test
    fun talkBackReadsTheSummaryWithItsCount() {
        show(saleViewModel())

        compose.onNodeWithText("Aceite 900 ml").performClick()
        compose.onNodeWithText("Aceite 900 ml").performClick()

        compose.onNodeWithContentDescription(
            plural(R.plurals.sale_summary_products, 2),
            substring = true,
        ).assertIsDisplayed()
    }

    @Test
    fun backWithProductsAsksBeforeDiscarding() {
        show(saleViewModel())
        compose.onNodeWithText("Arroz 1 kg").performClick()

        compose.onNodeWithContentDescription(text(R.string.a11y_close)).performClick()

        compose.onNodeWithText(text(R.string.dialog_leave_sale_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.dialog_leave_sale_exit)).performClick()
        val dialogs = compose.onAllNodesWithText(text(R.string.dialog_leave_sale_title))
        assertEquals(0, dialogs.fetchSemanticsNodes().size)
    }

    @Test
    fun largeTextPutsThePaymentInARowAndKeepsTheButton() {
        show(saleViewModel(), fontScale = LARGE_FONT)

        compose.onNodeWithText("Arroz 1 kg").performClick()

        val cash = text(R.string.seed_payment_methods_cash)
        compose.onNodeWithText(text(R.string.sale_payment_row, cash)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.sale_register)).assertIsDisplayed()
    }

    @Test
    fun aWideWindowShowsTheSaleBesideTheCatalog() {
        show(saleViewModel(), width = WIDE_WIDTH)

        compose.onNodeWithText("Huevos x 30").performClick()

        // La tarjeta y la línea del panel derecho: la venta está a la vista sin abrir nada.
        assertEquals(2, compose.onAllNodesWithText("Huevos x 30").fetchSemanticsNodes().size)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val LARGE_FONT = 2f
        const val WIDE_WIDTH = 900
        const val WIDE_HEIGHT = 600
    }
}

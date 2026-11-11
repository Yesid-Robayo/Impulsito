package io.github.yesidrobayo.shopledger.ui.sale

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El detalle de una venta en el teléfono: muestra lo que dejó la venta y lo oculta en modo
 * discreto, anula desde su hoja con el motivo elegido, y una venta anulada ya no ofrece anular.
 */
@RunWith(AndroidJUnit4::class)
class SaleDetailScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val actions = mutableListOf<SaleDetailAction>()

    private fun text(id: Int) = resources.getString(id)

    private fun show(state: SaleDetailUiState, discreet: Boolean = false) {
        compose.setContent {
            PreviewTheme(discreet = discreet) { SaleDetailScreen(state, { actions += it }) }
        }
    }

    /** La lista es perezosa: lo de abajo no existe hasta desplazarse hasta ahí. */
    private fun scrollTo(label: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(label))
    }

    @Test
    fun theDetailShowsWhatTheSaleLeftAndOffersToVoid() {
        show(SaleDetailUiState.Ready(saleDetailView()))

        scrollTo(text(R.string.term_sales_profit))
        compose.onNodeWithText(text(R.string.term_sales_profit)).assertIsDisplayed()
        scrollTo(text(R.string.dialog_void_sale_action))
        compose.onNodeWithText(text(R.string.dialog_void_sale_action)).performClick()

        assertEquals(listOf<SaleDetailAction>(SaleDetailAction.OpenVoid), actions)
    }

    @Test
    fun discreetModeHidesTheResultsButNotTheTotal() {
        show(SaleDetailUiState.Ready(saleDetailView()), discreet = true)

        scrollTo(text(R.string.term_net_received))
        // Cada cifra oculta se lee en TalkBack como oculta, sin su valor.
        val hidden = compose.onAllNodesWithContentDescription(text(R.string.a11y_discreet_hidden))
            .fetchSemanticsNodes()
        assertTrue("Costo, ganancia, margen, comisión y neto ocultos", hidden.size >= 5)
    }

    @Test
    fun theVoidSheetSendsTheChosenReason() {
        show(SaleDetailUiState.Ready(saleDetailView(), sheet = VoidSheet()))

        compose.onNodeWithText(text(R.string.void_reason_customer_return)).performClick()
        val buttons = compose.onAllNodesWithText(text(R.string.dialog_void_sale_action))
        buttons[buttons.fetchSemanticsNodes().size - 1].performClick()

        assertEquals(SaleDetailAction.ReasonChange(VoidReason.CUSTOMER_RETURN), actions.first())
        // El botón de la hoja confirma; el de la pantalla queda detrás del velo.
        assertTrue(SaleDetailAction.ConfirmVoid in actions)
    }

    @Test
    fun aVoidedSaleIsMarkedAndNoLongerOffersToVoid() {
        show(SaleDetailUiState.Ready(saleDetailView(voided = true)))

        // La fecha y el motivo de la anulación van junto al total tachado, que lleva su etiqueta.
        compose.onNodeWithText(text(R.string.void_reason_customer_return), substring = true)
            .assertIsDisplayed()
        val offers = compose.onAllNodesWithText(text(R.string.dialog_void_sale_action))
        assertEquals(0, offers.fetchSemanticsNodes().size)
    }
}

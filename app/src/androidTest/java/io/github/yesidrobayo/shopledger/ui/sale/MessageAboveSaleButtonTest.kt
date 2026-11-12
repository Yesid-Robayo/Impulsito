package io.github.yesidrobayo.shopledger.ui.sale

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.navigation.NavigationMessages
import io.github.yesidrobayo.shopledger.ui.components.AppSnackbarVisuals
import io.github.yesidrobayo.shopledger.ui.components.SnackbarVariant
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El mensaje de la venta anterior, con `Deshacer`, sigue a la vista cuando se abre la venta
 * siguiente o su cobro: va encima del área fija de abajo, nunca sobre `Registrar venta`.
 *
 * Se dibuja la pantalla dentro de los mensajes de la navegación, como en la app. El toque va por
 * coordenadas al centro del botón, como un dedo: si el mensaje lo tapara, el toque caería en él y la
 * venta nueva no se registraría.
 */
@RunWith(AndroidJUnit4::class)
class MessageAboveSaleButtonTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val scope = CoroutineScope(Dispatchers.Main)
    private val host = SnackbarHostState()
    private var previousSaleUndone = false

    private fun text(id: Int, vararg args: Any) = resources.getString(id, *args)

    private val message = text(R.string.confirm_sale_registered, "45")
    private val undo = text(R.string.action_undo)
    private val register = text(R.string.sale_register)

    @After
    fun stopMessages() {
        scope.cancel()
    }

    private fun showInNavigation(screen: @Composable () -> Unit) {
        compose.setContent {
            PreviewTheme { NavigationMessages(host, showsBottomBar = false) { screen() } }
        }
    }

    /** El mensaje de la venta anterior. Queda hasta que se actúe, para no depender del reloj. */
    private fun showPreviousSaleMessage() {
        scope.launch {
            val visuals = AppSnackbarVisuals(message, SnackbarVariant.ERROR, actionLabel = undo)
            if (host.showSnackbar(visuals) == SnackbarResult.ActionPerformed) {
                previousSaleUndone = true
            }
        }
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    private fun assertAbove(upper: SemanticsNodeInteraction, lower: SemanticsNodeInteraction) {
        val top = upper.getBoundsInRoot()
        val bottom = lower.getBoundsInRoot()
        assertTrue("${top.bottom} debía quedar sobre ${bottom.top}", top.bottom <= bottom.top)
    }

    private fun assertMessageAboveRegister() {
        val button = compose.onNodeWithText(register)
        assertAbove(compose.onNodeWithText(message), button)
        assertAbove(compose.onNodeWithText(undo), button)
    }

    @Test
    fun inTheNextSaleTheMessageStaysAboveRegisterAndATapInTheMiddleRegisters() {
        val requests = mutableListOf<SaleRequest>()
        val model = saleViewModel(requests = requests)
        showInNavigation {
            val state by model.uiState.collectAsState()
            NewSaleScreen(state, model::onAction)
        }
        compose.onNodeWithText("Arroz 1 kg").performClick()

        showPreviousSaleMessage()

        assertMessageAboveRegister()
        compose.onNodeWithText(register).performTouchInput { click(center) }
        compose.waitUntil(TIMEOUT_MS) { requests.isNotEmpty() }
        assertEquals(1, requests.size)
        assertTrue("El mensaje anterior debía seguir a la vista", host.currentSnackbarData != null)
        assertTrue("No debía anular la venta anterior", !previousSaleUndone)
    }

    @Test
    fun inTheCheckoutTheMessageStaysAboveRegisterAndATapInTheMiddleRegisters() {
        val requests = mutableListOf<SaleRequest>()
        val model = CheckoutViewModel(
            catalogSource = { MutableStateFlow(checkoutCatalog()) },
            registerSale = {
                requests += it
                RegisterSaleResult.Registered(1_204L)
            },
            findImage = { null },
            savedState = SavedStateHandle(),
            numbers = SaleNumberFormat(SYMBOLS, 0, SYMBOLS, SYMBOLS),
            offered = { CheckoutHandoff.Offered(SaleDraft(CHECKOUT_LINES, 1L), false) },
            computation = Dispatchers.Default,
        )
        showInNavigation {
            val state by model.uiState.collectAsState()
            CheckoutScreen(state, model::onAction, onBack = {})
        }
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithText(text(R.string.checkout_complete), substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        showPreviousSaleMessage()

        assertMessageAboveRegister()
        compose.onNodeWithText(register).performTouchInput { click(center) }
        compose.waitUntil(TIMEOUT_MS) { requests.isNotEmpty() }
        assertEquals(1, requests.size)
        assertTrue("El mensaje anterior debía seguir a la vista", host.currentSnackbarData != null)
        assertTrue("No debía anular la venta anterior", !previousSaleUndone)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        val SYMBOLS = NumberSymbols(decimalSeparator = ',', minusSign = '-', grouping = null)
    }
}

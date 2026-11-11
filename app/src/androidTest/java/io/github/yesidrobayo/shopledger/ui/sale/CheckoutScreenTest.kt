package io.github.yesidrobayo.shopledger.ui.sale

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePaymentRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El cobro en el teléfono, con su ViewModel y un catálogo inventado: abre con un pago que ya cuadra,
 * los pagos que no suman se dicen y se completan con `Usar`, la imagen de cobro va a pantalla
 * completa, TalkBack lee el total, y la pantalla se acomoda a la letra grande y a una ventana ancha.
 * La venta común sigue confirmando directo, con `Más opciones de cobro` a la vista.
 */
@RunWith(AndroidJUnit4::class)
class CheckoutScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val requests = mutableListOf<SaleRequest>()

    private fun text(id: Int, vararg args: Any) = resources.getString(id, *args)

    private fun model(methodId: Long = 1L, qrOnly: Boolean = false) = CheckoutViewModel(
        catalogSource = { MutableStateFlow(checkoutCatalog()) },
        registerSale = {
            requests += it
            RegisterSaleResult.Registered(1_204L)
        },
        findImage = { null },
        savedState = SavedStateHandle(),
        numbers = SaleNumberFormat(SYMBOLS, 0, SYMBOLS, SYMBOLS),
        offered = { CheckoutHandoff.Offered(SaleDraft(CHECKOUT_LINES, methodId), qrOnly) },
        computation = Dispatchers.Default,
    )

    private fun show(model: CheckoutViewModel, fontScale: Float? = null, width: Int? = null) {
        compose.setContent {
            PreviewTheme {
                val state by model.uiState.collectAsState()
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, fontScale ?: density.fontScale),
                ) {
                    val frame =
                        width?.let { Modifier.requiredSize(it.dp, WIDE_HEIGHT.dp) } ?: Modifier
                    Box(frame) { CheckoutScreen(state, model::onAction, onBack = {}) }
                }
            }
        }
    }

    /** TalkBack lee el bloque del total como una sola frase, con la cifra. */
    private fun totalLabel() = text(R.string.a11y_checkout_total, "").trim()

    /** Espera a que aparezca [value]: el estado llega desde el hilo de las cuentas. */
    private fun awaitText(value: String) {
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithText(value, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun register() {
        compose.onNodeWithText(text(R.string.sale_register)).performClick()
    }

    @Test
    fun itOpensWithOnePaymentThatAlreadyAddsUpAndRegisters() {
        show(model())

        awaitText(text(R.string.checkout_complete))
        compose.onNodeWithText(
            text(R.string.checkout_complete),
        ).performScrollTo().assertIsDisplayed()
        register()
        compose.waitUntil(TIMEOUT_MS) { requests.isNotEmpty() }

        // Dos aceites a 12500 y un café de 14900 con 10 %: 25000 + 13410.
        assertEquals(listOf(SalePaymentRequest(1L, Money(38_410L))), requests.single().payments)
    }

    @Test
    fun paymentsThatDoNotAddUpAreSaidAndUseCompletesThem() {
        val model = model()
        show(model)
        awaitText(text(R.string.checkout_complete))

        // El monto se escribe en el ViewModel: lo que se prueba aquí es el aviso y Usar.
        compose.runOnIdle { model.onAction(CheckoutAction.AmountChange(0, "30000")) }
        awaitText(text(R.string.checkout_missing, "").trim())
        register()

        val missing = text(R.string.checkout_error_sum, "").trim()
        awaitText(missing)
        compose.onNodeWithText(missing, substring = true).performScrollTo().assertIsDisplayed()
        assertTrue(requests.isEmpty())

        val use = text(R.string.checkout_use_amount_action, "").trim()
        compose.onNodeWithText(use, substring = true).performScrollTo().performClick()
        register()
        compose.waitUntil(TIMEOUT_MS) { requests.isNotEmpty() }
    }

    @Test
    fun talkBackReadsTheTotalDue() {
        show(model())

        val label = text(R.string.a11y_checkout_total, "").trim()
        compose.onNodeWithContentDescription(label, substring = true).assertIsDisplayed()
    }

    @Test
    fun thePaymentImageGoesFullScreenWithTheTotal() {
        show(model(methodId = 3L, qrOnly = true))

        compose.onNodeWithText(text(R.string.checkout_qr_total)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.checkout_qr_check)).assertIsDisplayed()
        compose.onNodeWithText("Datos de cobro inventados").assertIsDisplayed()
        // La imagen de cobro nunca muestra comisión ni neto.
        assertEquals(
            0,
            compose.onAllNodesWithText(text(R.string.checkout_fee)).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun largeTextKeepsTheTotalAndTheButton() {
        show(model(), fontScale = LARGE_FONT)

        compose.onNodeWithContentDescription(totalLabel(), substring = true).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.sale_register)).assertIsDisplayed()
    }

    @Test
    fun aWideWindowShowsTheSummaryBesideThePayments() {
        show(model(), width = WIDE_WIDTH)

        // La ventana es más ancha que el teléfono: se mira dónde queda cada parte, no si se ve.
        val total = compose.onNodeWithContentDescription(totalLabel(), substring = true)
            .fetchSemanticsNode().boundsInRoot
        val payments = compose.onNodeWithText(text(R.string.checkout_section_payments))
            .fetchSemanticsNode().boundsInRoot
        assertTrue("El resumen va a la izquierda de los pagos", total.right <= payments.left)
        assertTrue("Las dos columnas empiezan arriba", payments.top < total.bottom)
    }

    @Test
    fun theUsualSaleStillRegistersDirectlyWithMoreOptionsInSight() {
        val sale = saleViewModel(requests = requests)
        compose.setContent {
            PreviewTheme {
                val state by sale.uiState.collectAsState()
                NewSaleScreen(state, sale::onAction)
            }
        }

        compose.onNodeWithText("Arroz 1 kg").performClick()
        compose.onNodeWithText(text(R.string.checkout_more_options_action)).assertIsDisplayed()
        register()
        compose.waitUntil(TIMEOUT_MS) { requests.isNotEmpty() }

        assertTrue(requests.single().payments.isEmpty())
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val LARGE_FONT = 2f
        const val WIDE_WIDTH = 900
        const val WIDE_HEIGHT = 700
        val SYMBOLS = NumberSymbols(decimalSeparator = ',', minusSign = '-', grouping = null)
    }
}

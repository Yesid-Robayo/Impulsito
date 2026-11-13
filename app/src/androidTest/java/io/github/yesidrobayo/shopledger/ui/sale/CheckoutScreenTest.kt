package io.github.yesidrobayo.shopledger.ui.sale

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.customer.Customer
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerContact
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.sale.ForeignTender
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePaymentRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.LoyaltySettings
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.ui.theme.PreviewTheme
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El cobro en el teléfono, con su ViewModel y un catálogo inventado: abre con un pago que ya cuadra,
 * los pagos que no suman se dicen y se completan con `Usar`, la imagen de cobro va a pantalla
 * completa, TalkBack lee el total, y la pantalla se acomoda a la letra grande y a una ventana ancha.
 * La venta común sigue confirmando directo, con `Más opciones de cobro` a la vista. Un cliente
 * reciente con saldo a favor lo usa desde el cobro, y el modo discreto oculta lo disponible. Con
 * moneda de referencia, un pago se cobra en otra moneda desde su menú.
 */
@RunWith(AndroidJUnit4::class)
class CheckoutScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val requests = mutableListOf<SaleRequest>()

    private fun text(id: Int, vararg args: Any) = resources.getString(id, *args)

    private fun model(
        methodId: Long = 1L,
        qrOnly: Boolean = false,
        customers: List<Customer> = emptyList(),
        loyalty: LoyaltySettings = LoyaltySettings.OFF,
        recent: List<Customer> = emptyList(),
        reference: CheckoutReference? = null,
    ) = CheckoutViewModel(
        catalogSource = {
            MutableStateFlow(
                checkoutCatalog().copy(
                    creditMethods = listOf(saleMethod(CREDIT, "ON_ACCOUNT")),
                    loyalty = loyalty,
                ),
            )
        },
        registerSale = {
            requests += it
            RegisterSaleResult.Registered(1_204L)
        },
        findImage = { null },
        savedState = SavedStateHandle(),
        numbers = SaleNumberFormat(SYMBOLS, 0, SYMBOLS, SYMBOLS),
        offered = { CheckoutHandoff.Offered(SaleDraft(CHECKOUT_LINES, methodId), qrOnly) },
        computation = Dispatchers.Default,
        customersSource = { MutableStateFlow(customers) },
        recentCustomersSource = { MutableStateFlow(recent) },
        referenceSource = { MutableStateFlow(reference) },
    )

    private fun show(
        model: CheckoutViewModel,
        fontScale: Float? = null,
        width: Int? = null,
        discreet: Boolean = false,
    ) {
        compose.setContent {
            PreviewTheme(discreet = discreet) {
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
    fun withoutAReferenceCurrencyAPaymentOffersNoOtherCurrency() {
        show(model())
        awaitText(text(R.string.checkout_complete))

        // Un solo pago en efectivo y sin imagen de cobro: su menú no tiene nada que ofrecer.
        assertTrue(
            compose.onAllNodesWithContentDescription(text(R.string.a11y_more_options))
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun anotherCurrencyIsChargedFromThePaymentMenuAlsoWithLargeText() {
        show(model(reference = CHECKOUT_REFERENCE), fontScale = LARGE_FONT)
        awaitText(text(R.string.checkout_complete))
        compose.onNodeWithContentDescription(text(R.string.a11y_more_options))
            .performScrollTo()
            .performClick()
        compose.onNodeWithText(text(R.string.checkout_other_currency_action)).performClick()

        val tendered = text(R.string.checkout_fx_tendered, "XTS")
        awaitText(tendered)
        // La cotización vigente ya viene escrita; solo falta lo entregado.
        compose.onNodeWithText(tendered).performScrollTo().performTextInput("20")
        awaitText(text(R.string.checkout_fx_equivalent, "").trim())
        val apply = text(R.string.checkout_fx_apply_action)
        compose.onNodeWithText(apply).performClick()
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithText(apply).fetchSemanticsNodes().isEmpty()
        }

        // Debajo del pago quedan lo entregado y la cotización, y el cobro sigue cuadrando.
        compose.onNodeWithText(text(R.string.reference_rate_prefix, "XTS"), substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        awaitText(text(R.string.checkout_complete))
        register()
        compose.waitUntil(TIMEOUT_MS) { requests.isNotEmpty() }

        val sent = requests.single().payments.single()
        val xts = CHECKOUT_REFERENCE.currency.code
        assertEquals(ForeignTender(xts, 2, 2_000L, 4_150_500_000L), sent.foreign)
        assertEquals(Money(83_010L), sent.tenderedAmount)
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

    @Test
    fun aCreditPaymentAsksForTheCustomerAndRegistersWithIt() {
        show(model(customers = listOf(CLIENT)))
        awaitText(text(R.string.checkout_complete))

        compose.onNodeWithText(
            text(R.string.customer_picker_none),
        ).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.checkout_customer)).performClick()
        awaitText(CLIENT_NAME)
        compose.onNodeWithText(CLIENT_NAME).performClick()
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithText(text(R.string.customer_picker_none))
                .fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithContentDescription(text(R.string.a11y_remove_customer)).assertExists()
        register()
        compose.waitUntil(TIMEOUT_MS) { requests.isNotEmpty() }

        assertEquals(CLIENT.id, requests.single().customerId)
    }

    @Test
    fun pointsAreRedeemedAsADiscountAndSaySoAlsoWithLargeText() {
        show(model(customers = listOf(MEMBER), loyalty = PROGRAM), fontScale = LARGE_FONT)
        awaitText(text(R.string.checkout_complete))
        // Sin cliente, el cobro no menciona puntos.
        assertTrue(
            compose.onAllNodesWithText(text(R.string.checkout_points_to_redeem))
                .fetchSemanticsNodes().isEmpty(),
        )
        compose.onNodeWithText(text(R.string.checkout_customer)).performScrollTo().performClick()
        awaitText(MEMBER_NAME)
        compose.onNodeWithText(MEMBER_NAME).performClick()
        awaitText(text(R.string.checkout_points_not_money))

        compose.onNodeWithText(text(R.string.checkout_points_available, "150"))
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText(text(R.string.checkout_points_to_redeem))
            .performScrollTo()
            .performTextInput("10")
        compose.onNodeWithText(text(R.string.checkout_redeem_action))
            .performScrollTo()
            .performClick()
        // La fila del canje aparece en el bloque del total, y el pago único sigue cuadrando.
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithText(text(R.string.checkout_points))
                .fetchSemanticsNodes().size > 1
        }
        awaitText(text(R.string.checkout_complete))
        register()
        compose.waitUntil(TIMEOUT_MS) { requests.isNotEmpty() }

        assertEquals(10L, requests.single().pointsRedeemed)
        assertEquals(MEMBER.id, requests.single().customerId)
    }

    @Test
    fun aRecentCustomerWithCreditBalanceUsesItAlsoWithLargeText() {
        show(
            model(customers = listOf(CLIENT, PREPAID), recent = listOf(PREPAID)),
            fontScale = LARGE_FONT,
        )
        awaitText(text(R.string.checkout_complete))
        compose.onNodeWithText(text(R.string.checkout_customer)).performScrollTo().performClick()

        // Sin búsqueda, los recientes van arriba y `Ver clientes` queda a la vista.
        awaitText(text(R.string.customer_picker_recent))
        compose.onNodeWithText(text(R.string.customer_picker_all)).assertExists()
        compose.onNodeWithText(text(R.string.customer_picker_view_all)).assertIsDisplayed()
        compose.onAllNodesWithText(PREPAID_NAME)[0].performClick()

        val available = text(R.string.checkout_credit_available, "").trim()
        awaitText(available)
        compose.onNodeWithText(text(R.string.checkout_credit_balance))
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText(text(R.string.checkout_use_credit_action))
            .performScrollTo()
            .performClick()
        // El saldo a favor paga todo: el botón se va, y el vencimiento no se pide.
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithText(text(R.string.checkout_use_credit_action))
                .fetchSemanticsNodes().isEmpty()
        }
        assertTrue(
            compose.onAllNodesWithText(text(R.string.checkout_due_date))
                .fetchSemanticsNodes().isEmpty(),
        )
        awaitText(text(R.string.checkout_complete))
        register()
        compose.waitUntil(TIMEOUT_MS) { requests.isNotEmpty() }

        val request = requests.single()
        assertEquals(PREPAID.id, request.customerId)
        assertEquals(listOf(SalePaymentRequest(CREDIT, Money(38_410L))), request.payments)
        assertNull(request.creditDueDate)
    }

    @Test
    fun inDiscreetModeTheAvailableCreditIsHiddenAndStillUsable() {
        val model = model(customers = listOf(PREPAID))
        show(model, discreet = true)
        awaitText(text(R.string.checkout_complete))
        compose.runOnIdle { model.onAction(CheckoutAction.PickCustomer(PREPAID.id)) }

        val available = text(R.string.checkout_credit_available, "").trim()
        awaitText(available)
        compose.onNode(
            hasText(available, substring = true) and
                hasContentDescription(text(R.string.a11y_discreet_hidden)),
            useUnmergedTree = false,
        ).assertExists()
        compose.onNodeWithText(text(R.string.checkout_use_credit_action))
            .performScrollTo()
            .assertIsDisplayed()
    }

    private companion object {
        const val CREDIT = 9L
        val CREATED: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000L)
        const val CLIENT_NAME = "Cliente inventado"
        val CLIENT = Customer(
            id = 40L,
            contact = CustomerContact(CLIENT_NAME, "cliente inventado", null, null, null),
            priceListId = null,
            balance = Money(4_000L),
            pointsBalance = 0L,
            anonymizedAt = null,
            archivedAt = null,
            createdAt = CREATED,
            updatedAt = CREATED,
        )
        const val MEMBER_NAME = "Cliente con puntos"
        val MEMBER = Customer(
            id = 41L,
            contact = CustomerContact(MEMBER_NAME, "cliente con puntos", null, null, null),
            priceListId = null,
            balance = Money.ZERO,
            pointsBalance = 150L,
            anonymizedAt = null,
            archivedAt = null,
            createdAt = CREATED,
            updatedAt = CREATED,
        )
        const val PREPAID_NAME = "Cliente con anticipo"
        val PREPAID = Customer(
            id = 42L,
            contact = CustomerContact(PREPAID_NAME, "cliente con anticipo", null, null, null),
            priceListId = null,
            balance = Money(-50_000L),
            pointsBalance = 0L,
            anonymizedAt = null,
            archivedAt = null,
            createdAt = CREATED,
            updatedAt = CREATED,
        )
        val PROGRAM = LoyaltySettings(true, amountPerPoint = Money(1_000L), pointValue = Money(5L))
        const val TIMEOUT_MS = 5_000L
        const val LARGE_FONT = 2f
        const val WIDE_WIDTH = 900
        const val WIDE_HEIGHT = 700
        val SYMBOLS = NumberSymbols(decimalSeparator = ',', minusSign = '-', grouping = null)
    }
}

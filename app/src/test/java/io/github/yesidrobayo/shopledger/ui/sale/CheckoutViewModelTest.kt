package io.github.yesidrobayo.shopledger.ui.sale

import androidx.lifecycle.SavedStateHandle
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodDetails
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodRecord
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleCatalog
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePaymentRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxSettings
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.rule.sale.CheckoutBalance
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.ui.product.COUNT_UNIT
import io.github.yesidrobayo.shopledger.ui.product.CREATED
import io.github.yesidrobayo.shopledger.ui.product.product
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * El cobro sin base: abre con un pago que cuadra, varios pagos con recargo y comisión, lo que falta
 * y `Usar`, el ajuste de la venta, un método archivado, la venta en cero, las fallas sin perder
 * nada y el cobro que sobrevive al cierre del proceso. Productos y métodos inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CheckoutViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val oil = product(2L, "Aceite", salePrice = 12_500L, onHand = 23_000L)
    private val free = product(5L, "Muestra")
    private val today = BusinessDate(LocalDate(2026, 11, 2))
    private val cash = method(10L, PaymentMethodKind.CASH)
    private val card =
        method(11L, PaymentMethodKind.CARD, adjustment = 500L, feeBp = 300L, days = 2)
    private val wallet = method(12L, PaymentMethodKind.WALLET, instructions = "Cuenta 0000")
    private val catalog = MutableStateFlow(catalogOf(listOf(cash, card, wallet)))
    private val requests = mutableListOf<SaleRequest>()
    private var result: RegisterSaleResult = RegisterSaleResult.Registered(99L)
    private val savedState = SavedStateHandle()
    private val symbols = NumberSymbols(decimalSeparator = ',', minusSign = '-', grouping = null)
    private val numbers = SaleNumberFormat(symbols, 0, symbols, symbols)
    private val twoOils =
        SaleDraft(lines = listOf(SaleLineRequest(2L, QuantityMilli(2_000L))), methodId = 10L)

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun catalogOf(methods: List<PaymentMethodRecord>) = SaleCatalog(
        products = listOf(oil, free),
        frequent = listOf(oil, free),
        archived = emptyList(),
        units = listOf(COUNT_UNIT).associateBy { it.id },
        methods = methods,
        taxes = TaxSettings(TaxMode.NONE, null, purchaseTaxRecoverable = false),
        taxRates = emptyMap(),
        today = today,
        weekStartDay = DayOfWeek.MONDAY,
    )

    private fun method(
        id: Long,
        kind: PaymentMethodKind,
        adjustment: Long = 0L,
        feeBp: Long = 0L,
        days: Int = 0,
        instructions: String? = null,
    ) = PaymentMethodRecord(
        id = id,
        systemKey = kind.name,
        details = PaymentMethodDetails(
            name = null,
            normalizedName = null,
            kind = kind,
            feeRate = BasisPoints(feeBp),
            feeFixed = Money.ZERO,
            settlementDays = days,
            priceAdjustment = BasisPoints(adjustment),
            moneyAccountId = null,
            instructionsText = instructions,
            instructionsMediaId = null,
        ),
        sortOrder = id,
        archivedAt = null,
        createdAt = CREATED,
        updatedAt = CREATED,
    )

    private fun TestScope.viewModel(
        draft: SaleDraft? = twoOils,
        qrOnly: Boolean = false,
        state: SavedStateHandle = savedState,
    ): CheckoutViewModel {
        val model = CheckoutViewModel(
            catalogSource = { catalog },
            registerSale = {
                requests += it
                result
            },
            findImage = { null },
            savedState = state,
            numbers = numbers,
            offered = { draft?.let { CheckoutHandoff.Offered(it, qrOnly) } },
            computation = dispatcher,
        )
        backgroundScope.launch { model.uiState.collect {} }
        advanceUntilIdle()
        return model
    }

    private fun CheckoutViewModel.act(scope: TestScope, action: CheckoutAction) {
        onAction(action)
        scope.advanceUntilIdle()
    }

    private val CheckoutViewModel.state get() = uiState.value

    @Test
    fun `it opens with one payment of the chosen method for everything, which already adds up`() =
        runTest {
            val model = viewModel()

            assertEquals(CheckoutContent.Ready, model.state.content)
            assertEquals(listOf(10L), model.state.payments.map { it.method.id })
            assertEquals("25000", model.state.payments.single().amountText)
            assertEquals(Money(25_000L), model.state.summary.amountDue)
            assertEquals(Money(25_000L), model.state.summary.subtotal)
            assertEquals(CheckoutBalance.Complete, model.state.balance)
            assertEquals(listOf(11L, 12L), model.state.addable.map { it.id })
        }

    @Test
    fun `without a sale to charge there is nothing to show`() = runTest {
        val model = viewModel(draft = null)

        assertEquals(CheckoutContent.Unavailable, model.state.content)
    }

    @Test
    fun `a second payment takes what is missing, with its surcharge, fee and settlement date`() =
        runTest {
            val model = viewModel()
            model.act(this, CheckoutAction.AmountChange(0, "15000"))

            assertEquals(CheckoutBalance.Missing(Money(10_000L)), model.state.balance)

            model.act(this, CheckoutAction.AddPayment(11L))

            val card = model.state.payments[1]
            assertEquals("10000", card.amountText)
            // +5 % sobre 10000 es 500: la tarjeta cubre 10500, con 3 % de comisión, 315.
            assertEquals(Money(10_500L), card.covers)
            assertTrue(card.showsFee)
            assertEquals("315", card.feeText)
            assertEquals(Money(10_185L), card.net)
            assertEquals(BusinessDate(LocalDate(2026, 11, 4)), card.settlesOn)
            assertEquals(Money(25_500L), model.state.summary.amountDue)
            assertEquals(Money(500L), model.state.summary.methodAdjustments.single().amount)
            assertEquals(CheckoutBalance.Complete, model.state.balance)
            assertEquals(listOf(12L), model.state.addable.map { it.id })
        }

    @Test
    fun `payments that do not add up are not registered, and use due completes the active one`() =
        runTest {
            val model = viewModel()
            model.act(this, CheckoutAction.AmountChange(0, "20000"))
            model.act(this, CheckoutAction.Register)

            assertTrue(model.state.balanceError)
            assertTrue(requests.isEmpty())

            model.act(this, CheckoutAction.UseDue)

            assertEquals("25000", model.state.payments.single().amountText)
            assertFalse(model.state.balanceError)
            assertEquals(CheckoutBalance.Complete, model.state.balance)
        }

    @Test
    fun `the same amount written again keeps the warning, and a new one clears it`() = runTest {
        val model = viewModel()
        model.act(this, CheckoutAction.AmountChange(0, "20000"))
        model.act(this, CheckoutAction.Register)
        // El campo pierde el foco al tocar el botón y vuelve a avisar el mismo texto.
        model.act(this, CheckoutAction.AmountChange(0, "20000"))

        assertTrue(model.state.balanceError)

        model.act(this, CheckoutAction.AmountChange(0, "21000"))

        assertFalse(model.state.balanceError)
    }

    @Test
    fun `use due takes off what is over`() = runTest {
        val model = viewModel()
        model.act(this, CheckoutAction.AmountChange(0, "30000"))
        model.act(this, CheckoutAction.UseDue)

        assertEquals("25000", model.state.payments.single().amountText)
    }

    @Test
    fun `registering sends every payment, its written fee and the sale adjustment`() = runTest {
        val model = viewModel()
        model.act(this, CheckoutAction.OpenAdjustment)
        model.act(this, CheckoutAction.AdjustmentTextChange("1000"))
        model.act(this, CheckoutAction.ApplyAdjustment)

        // Con un solo pago que cubría todo, el pago sigue al nuevo total.
        assertEquals("24000", model.state.payments.single().amountText)
        assertEquals(Money(-1_000L), model.state.summary.writtenAdjustment)

        model.act(this, CheckoutAction.AmountChange(0, "14000"))
        model.act(this, CheckoutAction.AddPayment(11L))
        model.act(this, CheckoutAction.FeeChange(1, "250"))
        model.act(this, CheckoutAction.Register)

        val request = requests.single()
        assertEquals(Money(-1_000L), request.orderAdjustment)
        assertEquals(
            listOf(
                SalePaymentRequest(10L, Money(14_000L)),
                SalePaymentRequest(11L, Money(10_000L), Money(250L)),
            ),
            request.payments,
        )
        assertEquals(twoOils.lines, request.lines)
        assertEquals(RegisteredSale(99L, Money(24_500L)), model.state.registered)
    }

    @Test
    fun `a percent adjustment is taken from the lines, and a discount above them is refused`() =
        runTest {
            val model = viewModel()
            model.act(this, CheckoutAction.OpenAdjustment)
            model.act(this, CheckoutAction.AdjustmentKindChange(AdjustmentKind.SURCHARGE))
            model.act(this, CheckoutAction.AdjustmentInputChange(AdjustmentInput.PERCENT))
            model.act(this, CheckoutAction.AdjustmentTextChange("10"))
            model.act(this, CheckoutAction.ApplyAdjustment)

            assertEquals(Money(2_500L), model.state.adjustment)
            assertNull(model.state.sheet)

            model.act(this, CheckoutAction.OpenAdjustment)
            model.act(this, CheckoutAction.AdjustmentKindChange(AdjustmentKind.DISCOUNT))
            model.act(this, CheckoutAction.AdjustmentTextChange("25001"))
            model.act(this, CheckoutAction.ApplyAdjustment)

            val editor = (model.state.sheet as CheckoutSheet.Adjustment).editor
            assertEquals(AdjustmentError.DISCOUNT_ABOVE_TOTAL, editor.error)
            assertEquals(Money(2_500L), model.state.adjustment)

            model.act(this, CheckoutAction.RemoveAdjustment)

            assertEquals(Money.ZERO, model.state.adjustment)
            assertEquals("25000", model.state.payments.single().amountText)
        }

    @Test
    fun `a payment can be removed while another remains`() = runTest {
        val model = viewModel()
        model.act(this, CheckoutAction.AddPayment(12L))
        model.act(this, CheckoutAction.RemovePayment(0))

        assertEquals(listOf(12L), model.state.payments.map { it.method.id })

        model.act(this, CheckoutAction.RemovePayment(0))

        assertEquals(1, model.state.payments.size)
    }

    @Test
    fun `an amount that cannot be read is marked and not registered`() = runTest {
        val model = viewModel()
        model.act(this, CheckoutAction.AmountChange(0, "1,5"))

        assertEquals(SaleFieldError.NOT_A_NUMBER, model.state.payments.single().amountError)

        model.act(this, CheckoutAction.Register)

        assertTrue(requests.isEmpty())
    }

    @Test
    fun `a method archived meanwhile marks its payment and is not registered`() = runTest {
        val model = viewModel()
        model.act(this, CheckoutAction.AddPayment(12L))
        catalog.value = catalogOf(listOf(cash, card))
        advanceUntilIdle()

        assertTrue(model.state.payments[1].gone)
        assertTrue(model.state.hasGoneMethod)

        model.act(this, CheckoutAction.Register)

        assertTrue(requests.isEmpty())
    }

    @Test
    fun `a sale resumed from the wait leaves it when charged here`() = runTest {
        val model = viewModel(draft = twoOils.copy(heldSaleId = 7L))

        model.act(this, CheckoutAction.Register)

        assertEquals(7L, requests.single().heldSaleId)
    }

    @Test
    fun `a sale in zero asks before registering`() = runTest {
        val model =
            viewModel(draft = SaleDraft(lines = listOf(SaleLineRequest(5L, QuantityMilli(1_000L)))))
        model.act(this, CheckoutAction.Register)

        assertTrue(model.state.zeroDialog)
        assertTrue(requests.isEmpty())

        model.act(this, CheckoutAction.ConfirmZeroTotal)

        assertEquals(Money.ZERO, requests.single().payments.single().amount)
        assertEquals(10L, requests.single().paymentMethodId)
    }

    @Test
    fun `a failure keeps everything on screen to retry`() = runTest {
        result = RegisterSaleResult.StorageFailed(StorageFailure.DISK_FULL)
        val model = viewModel()
        model.act(this, CheckoutAction.Register)

        assertEquals(RegisterStatus.FAILED, model.state.status)
        assertEquals("25000", model.state.payments.single().amountText)

        result = RegisterSaleResult.PaymentsDoNotMatch(Money(1L), Money(2L))
        model.act(this, CheckoutAction.Register)

        assertTrue(model.state.balanceError)
    }

    @Test
    fun `opened to show the payment image, it shows it at once`() = runTest {
        val model = viewModel(draft = twoOils.copy(methodId = 12L), qrOnly = true)

        val qr = requireNotNull(model.state.qr)
        assertTrue(qr.qrOnly)
        assertEquals("Cuenta 0000", qr.instructions)
        assertEquals(Money(25_000L), qr.amount)

        model.act(this, CheckoutAction.CloseQr)

        assertNull(model.state.qr)
    }

    @Test
    fun `the checkout survives the process being closed`() = runTest {
        val first = viewModel()
        first.act(this, CheckoutAction.AmountChange(0, "5000"))
        first.act(this, CheckoutAction.AddPayment(12L))

        // Un ViewModel nuevo con el mismo estado guardado y sin venta dejada: retoma lo escrito.
        val second = viewModel(draft = null)

        assertEquals(listOf("5000", "20000"), second.state.payments.map { it.amountText })
        assertEquals(CheckoutBalance.Complete, second.state.balance)
    }

    @Test
    fun `cash is rounded to the step, and the change is what was received minus it`() = runTest {
        catalog.value = catalog.value.copy(cashRounding = Money(50L))
        val model = viewModel()
        model.act(this, CheckoutAction.OpenAdjustment)
        model.act(this, CheckoutAction.AdjustmentTextChange("30"))
        model.act(this, CheckoutAction.ApplyAdjustment)

        // 24970 en efectivo baja a 24950: el redondeo es −20, y se cobra 24950.
        assertEquals("24970", model.state.payments.single().amountText)
        assertEquals(Money(24_950L), model.state.payments.single().covers)
        assertEquals(Money(-20L), model.state.summary.cashRounding)
        assertEquals(Money(24_950L), model.state.summary.amountDue)
        assertTrue(model.state.payments.single().isCash)

        model.act(this, CheckoutAction.TenderedChange(0, "30000"))

        assertEquals(Money(5_050L), model.state.payments.single().change)

        model.act(this, CheckoutAction.ExactAmount(0))

        assertEquals("24950", model.state.payments.single().tenderedText)
        assertEquals(Money.ZERO, model.state.payments.single().change)

        model.act(this, CheckoutAction.Register)

        assertEquals(Money(24_950L), requests.single().payments.single().tenderedAmount)
        assertEquals(RegisteredSale(99L, Money(24_950L)), model.state.registered)
    }

    @Test
    fun `what was received below the cash payment is marked and not registered`() = runTest {
        val model = viewModel()
        model.act(this, CheckoutAction.TenderedChange(0, "20000"))

        assertTrue(model.state.payments.single().tenderedBelow)

        model.act(this, CheckoutAction.Register)

        assertTrue(requests.isEmpty())

        model.act(this, CheckoutAction.TenderedChange(0, ""))
        model.act(this, CheckoutAction.Register)

        assertNull(requests.single().payments.single().tenderedAmount)
    }

    @Test
    fun `what was received that cannot be read is marked, and a card offers none`() = runTest {
        val model = viewModel()
        model.act(this, CheckoutAction.TenderedChange(0, "1,5"))
        model.act(this, CheckoutAction.AddPayment(11L))

        assertEquals(SaleFieldError.NOT_A_NUMBER, model.state.payments[0].tenderedError)
        assertFalse(model.state.payments[1].isCash)

        model.act(this, CheckoutAction.Register)

        assertTrue(requests.isEmpty())
    }

    @Test
    fun `what was received survives the process being closed`() = runTest {
        val first = viewModel()
        first.act(this, CheckoutAction.TenderedChange(0, "30000"))

        val second = viewModel(draft = null)

        assertEquals("30000", second.state.payments.single().tenderedText)
        assertEquals(Money(5_000L), second.state.payments.single().change)
    }
}

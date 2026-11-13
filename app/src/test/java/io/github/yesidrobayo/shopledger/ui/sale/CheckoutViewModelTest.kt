package io.github.yesidrobayo.shopledger.ui.sale

import androidx.lifecycle.SavedStateHandle
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.customer.Customer
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerContact
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodDetails
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodRecord
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleCatalog
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePaymentRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.LoyaltySettings
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
        format: SaleNumberFormat = numbers,
        customers: List<Customer> = emptyList(),
        recent: List<Customer> = emptyList(),
    ): CheckoutViewModel {
        val model = CheckoutViewModel(
            catalogSource = { catalog },
            registerSale = {
                requests += it
                result
            },
            findImage = { null },
            savedState = state,
            numbers = format,
            offered = { draft?.let { CheckoutHandoff.Offered(it, qrOnly) } },
            computation = dispatcher,
            customersSource = { MutableStateFlow(customers) },
            recentCustomersSource = { MutableStateFlow(recent) },
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
    fun `a currency with cents opens the same way, without zeros the person did not write`() =
        runTest {
            val point = NumberSymbols(decimalSeparator = '.', minusSign = '-', grouping = null)
            val model = viewModel(format = SaleNumberFormat(point, 2, point, point))

            assertEquals(CheckoutContent.Ready, model.state.content)
            // 25.000 en unidad menor con dos decimales: se escribe 250, como lo escribiría la persona.
            assertEquals("250", model.state.payments.single().amountText)
            assertEquals(Money(25_000L), model.state.summary.amountDue)
            assertEquals(CheckoutBalance.Complete, model.state.balance)
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

    @Test
    fun `with tips on, the tip is charged and the single payment keeps covering everything`() =
        runTest {
            catalog.value = catalog.value.copy(tipsEnabled = true)
            val model = viewModel()

            assertTrue(model.state.tipsEnabled)

            model.act(this, CheckoutAction.TipChange("2000"))

            assertEquals(Money(2_000L), model.state.summary.tip)
            assertEquals(Money(27_000L), model.state.summary.amountDue)
            assertEquals("27000", model.state.payments.single().amountText)
            assertEquals(CheckoutBalance.Complete, model.state.balance)

            model.act(this, CheckoutAction.Register)

            assertEquals(Money(2_000L), requests.single().tipAmount)
            assertEquals(Money(27_000L), requests.single().payments.single().amount)
        }

    @Test
    fun `no tip takes it off, and a tip that cannot be read is marked`() = runTest {
        catalog.value = catalog.value.copy(tipsEnabled = true)
        val model = viewModel()
        model.act(this, CheckoutAction.TipChange("2000"))
        model.act(this, CheckoutAction.NoTip)

        assertEquals(Money.ZERO, model.state.summary.tip)
        assertEquals("", model.state.tipText)
        assertEquals("25000", model.state.payments.single().amountText)

        model.act(this, CheckoutAction.TipChange("1,5"))
        model.act(this, CheckoutAction.Register)

        assertEquals(SaleFieldError.NOT_A_NUMBER, model.state.tipError)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `with tips off there is no tip, even one written before`() = runTest {
        catalog.value = catalog.value.copy(tipsEnabled = true)
        val model = viewModel()
        model.act(this, CheckoutAction.TipChange("2000"))
        model.act(this, CheckoutAction.AmountChange(0, "25000"))
        catalog.value = catalog.value.copy(tipsEnabled = false)
        advanceUntilIdle()

        assertFalse(model.state.tipsEnabled)
        assertEquals(Money.ZERO, model.state.summary.tip)

        model.act(this, CheckoutAction.Register)

        assertEquals(Money.ZERO, requests.single().tipAmount)
    }

    @Test
    fun `the tip survives the process being closed`() = runTest {
        catalog.value = catalog.value.copy(tipsEnabled = true)
        val first = viewModel()
        first.act(this, CheckoutAction.TipChange("2000"))

        val second = viewModel(draft = null)

        assertEquals("2000", second.state.tipText)
        assertEquals(Money(27_000L), second.state.summary.amountDue)
    }

    // ------------------------------------------------------------------ a crédito

    private val credit = method(13L, PaymentMethodKind.ON_ACCOUNT)
    private val ana = customer(30L, "Ana", balance = 4_000L)

    private fun customer(id: Long, name: String, balance: Long = 0L, points: Long = 0L) = Customer(
        id = id,
        contact = CustomerContact(name, name.lowercase(), null, null, null),
        priceListId = null,
        balance = Money(balance),
        pointsBalance = points,
        anonymizedAt = null,
        archivedAt = null,
        createdAt = CREATED,
        updatedAt = CREATED,
    )

    @Test
    fun `a credit payment without a customer asks for one and registers nothing`() = runTest {
        catalog.value = catalog.value.copy(creditMethods = listOf(credit))
        val model = viewModel(customers = listOf(ana))
        assertTrue(model.state.addable.any { it.id == 13L })
        model.act(this, CheckoutAction.AmountChange(0, "5000"))

        model.act(this, CheckoutAction.AddPayment(13L))
        assertEquals(CheckoutSheet.Customers, model.state.sheet)
        assertEquals(listOf(30L), model.state.customerChoices.map { it.id })
        model.act(this, CheckoutAction.CloseSheet)

        model.act(this, CheckoutAction.Register)
        assertTrue(requests.isEmpty())
        assertEquals(CheckoutCustomer.Missing, model.state.customer)
        assertEquals(CheckoutSheet.Customers, model.state.sheet)
    }

    @Test
    fun `with a customer the sale carries it, the due date and the balance it leaves`() = runTest {
        catalog.value = catalog.value.copy(creditMethods = listOf(credit))
        val model = viewModel(customers = listOf(ana))
        model.act(this, CheckoutAction.AmountChange(0, "5000"))
        model.act(this, CheckoutAction.AddPayment(13L))
        model.act(this, CheckoutAction.PickCustomer(30L))
        val due = BusinessDate(LocalDate(2026, 11, 30))
        model.act(this, CheckoutAction.CreditDueChange(due))

        assertEquals(CheckoutCustomer.Chosen(30L, "Ana", Money(4_000L)), model.state.customer)
        assertEquals(Money(24_000L), model.state.credit?.balanceAfter)
        model.act(this, CheckoutAction.Register)

        val request = requests.single()
        assertEquals(30L, request.customerId)
        assertEquals(due, request.creditDueDate)
    }

    @Test
    fun `a due date before the sale is not taken`() = runTest {
        catalog.value = catalog.value.copy(creditMethods = listOf(credit))
        val model = viewModel(customers = listOf(ana))
        model.act(this, CheckoutAction.PickCustomer(30L))
        model.act(this, CheckoutAction.AddPayment(13L))

        model.act(this, CheckoutAction.CreditDueChange(BusinessDate(LocalDate(2026, 11, 1))))

        assertTrue(model.state.credit?.dueError == true)
        assertNull(model.state.credit?.due)
    }

    @Test
    fun `removing the customer takes the credit payments away and says so`() = runTest {
        catalog.value = catalog.value.copy(creditMethods = listOf(credit))
        val model = viewModel(customers = listOf(ana))
        model.act(this, CheckoutAction.AmountChange(0, "5000"))
        model.act(this, CheckoutAction.AddPayment(13L))
        model.act(this, CheckoutAction.PickCustomer(30L))

        model.act(this, CheckoutAction.RemoveCustomer)

        assertEquals(listOf(10L), model.state.payments.map { it.method.id })
        assertNull(model.state.customer)
        assertNull(model.state.credit)
        assertTrue(model.state.customerRemoved)
    }

    @Test
    fun `a sale opened from a customer arrives with that customer chosen`() = runTest {
        val model = viewModel(draft = twoOils.copy(customerId = 30L), customers = listOf(ana))

        assertEquals(CheckoutCustomer.Chosen(30L, "Ana", Money(4_000L)), model.state.customer)
        model.act(this, CheckoutAction.Register)
        assertEquals(30L, requests.single().customerId)
    }

    @Test
    fun `a cash sale with a customer carries the customer and no due date`() = runTest {
        val model = viewModel(customers = listOf(ana))
        model.act(this, CheckoutAction.PickCustomer(30L))
        model.act(this, CheckoutAction.Register)

        assertEquals(30L, requests.single().customerId)
        assertNull(requests.single().creditDueDate)
    }

    // ------------------------------------------------------------------ saldo a favor

    private val bruno = customer(33L, "Bruno", balance = -1_500L)
    private val carla = customer(34L, "Carla", balance = -30_000L)

    private fun TestScope.withCredit(vararg customers: Customer): CheckoutViewModel {
        catalog.value = catalog.value.copy(creditMethods = listOf(credit))
        return viewModel(customers = customers.toList())
    }

    @Test
    fun `only a customer with a credit balance is offered to use it`() = runTest {
        val model = withCredit(ana, bruno)
        assertNull(model.state.creditBalance)

        model.act(this, CheckoutAction.PickCustomer(30L))
        assertNull(model.state.creditBalance)

        model.act(this, CheckoutAction.PickCustomer(33L))
        assertEquals(CheckoutCreditBalance(Money(1_500L), canUse = true), model.state.creditBalance)
    }

    @Test
    fun `without a credit method the credit balance is not offered`() = runTest {
        val model = viewModel(customers = listOf(bruno))
        model.act(this, CheckoutAction.PickCustomer(33L))

        assertNull(model.state.creditBalance)
        model.act(this, CheckoutAction.UseCreditBalance)
        assertEquals(listOf(10L), model.state.payments.map { it.method.id })
    }

    @Test
    fun `a credit balance larger than the sale pays all of it, without a due date`() = runTest {
        val model = withCredit(carla)
        model.act(this, CheckoutAction.PickCustomer(34L))

        model.act(this, CheckoutAction.UseCreditBalance)

        // El efectivo quedó en cero y sale: el saldo a favor paga toda la venta.
        assertEquals(listOf(13L), model.state.payments.map { it.method.id })
        assertEquals("25000", model.state.payments.single().amountText)
        assertEquals(CheckoutBalance.Complete, model.state.balance)
        assertEquals(Money(25_000L), model.state.summary.amountDue)
        assertEquals(
            CheckoutCreditBalance(Money(5_000L), canUse = false),
            model.state.creditBalance,
        )
        assertEquals(Money(-5_000L), model.state.credit?.balanceAfter)
        assertEquals(false, model.state.credit?.owes)

        // Sin deuda nada vence, aunque se hubiera escrito una fecha.
        model.act(this, CheckoutAction.CreditDueChange(BusinessDate(LocalDate(2026, 11, 30))))
        model.act(this, CheckoutAction.Register)
        val request = requests.single()
        assertEquals(34L, request.customerId)
        assertNull(request.creditDueDate)
        assertEquals(
            listOf(13L to Money(25_000L)),
            request.payments.map {
                it.paymentMethodId to it.amount
            },
        )
    }

    @Test
    fun `a smaller credit balance pays its part and the rest stays in the other payment`() =
        runTest {
            val model = withCredit(bruno)
            model.act(this, CheckoutAction.PickCustomer(33L))

            model.act(this, CheckoutAction.UseCreditBalance)

            assertEquals(listOf("23500", "1500"), model.state.payments.map { it.amountText })
            assertEquals(listOf(10L, 13L), model.state.payments.map { it.method.id })
            assertEquals(CheckoutBalance.Complete, model.state.balance)
            assertEquals(
                CheckoutCreditBalance(Money.ZERO, canUse = false),
                model.state.creditBalance,
            )
            assertEquals(Money.ZERO, model.state.credit?.balanceAfter)
            model.act(this, CheckoutAction.Register)
            assertEquals(
                listOf(10L to Money(23_500L), 13L to Money(1_500L)),
                requests.single().payments.map { it.paymentMethodId to it.amount },
            )
            assertNull(requests.single().creditDueDate)
        }

    @Test
    fun `it covers what is missing first, and adds to a credit payment already written`() =
        runTest {
            val model = withCredit(bruno)
            model.act(this, CheckoutAction.PickCustomer(33L))
            model.act(this, CheckoutAction.AmountChange(0, "24000"))
            model.act(this, CheckoutAction.AddPayment(13L))
            model.act(this, CheckoutAction.AmountChange(1, "200"))
            // Faltan 800 y quedan 1.300 de saldo a favor: cubre lo que falta y 500 del efectivo.
            assertEquals(
                CheckoutCreditBalance(Money(1_300L), canUse = true),
                model.state.creditBalance,
            )

            model.act(this, CheckoutAction.UseCreditBalance)

            assertEquals(listOf("23500", "1500"), model.state.payments.map { it.amountText })
            assertEquals(CheckoutBalance.Complete, model.state.balance)
        }

    @Test
    fun `a credit payment beyond the credit balance leaves a debt that asks for a due date`() =
        runTest {
            val model = withCredit(bruno)
            model.act(this, CheckoutAction.PickCustomer(33L))
            model.act(this, CheckoutAction.AmountChange(0, "20000"))
            model.act(this, CheckoutAction.AddPayment(13L))

            assertEquals(Money(3_500L), model.state.credit?.balanceAfter)
            assertEquals(true, model.state.credit?.owes)
            assertEquals(
                CheckoutCreditBalance(Money.ZERO, canUse = false),
                model.state.creditBalance,
            )
            val due = BusinessDate(LocalDate(2026, 11, 30))
            model.act(this, CheckoutAction.CreditDueChange(due))
            model.act(this, CheckoutAction.Register)
            assertEquals(due, requests.single().creditDueDate)
        }

    @Test
    fun `removing the customer takes back the credit balance used`() = runTest {
        val model = withCredit(bruno)
        model.act(this, CheckoutAction.PickCustomer(33L))
        model.act(this, CheckoutAction.UseCreditBalance)

        model.act(this, CheckoutAction.RemoveCustomer)

        assertEquals(listOf(10L), model.state.payments.map { it.method.id })
        assertNull(model.state.creditBalance)
        assertTrue(model.state.customerRemoved)
    }

    // ------------------------------------------------------------------ recientes

    @Test
    fun `recent customers show with the list open and nothing searched`() = runTest {
        // Bruno ya no se puede elegir: aunque se le vendió hace poco, no aparece.
        val model = viewModel(customers = listOf(ana, carla), recent = listOf(carla, bruno, ana))
        assertTrue(model.state.customerRecents.isEmpty())

        model.act(this, CheckoutAction.OpenCustomers)
        assertEquals(listOf(34L, 30L), model.state.customerRecents.map { it.id })
        assertEquals(listOf(30L, 34L), model.state.customerChoices.map { it.id })

        model.act(this, CheckoutAction.CustomerQueryChange("an"))
        assertTrue(model.state.customerRecents.isEmpty())
    }

    @Test
    fun `view customers closes the list, and a customer picked there is chosen`() = runTest {
        val model = viewModel(customers = listOf(ana))
        model.act(this, CheckoutAction.OpenCustomers)

        model.act(this, CheckoutAction.ViewAllCustomers)
        assertNull(model.state.sheet)

        model.act(this, CheckoutAction.PickCustomer(30L))
        assertEquals(CheckoutCustomer.Chosen(30L, "Ana", Money(4_000L)), model.state.customer)
    }

    // ------------------------------------------------------------------ puntos

    private val program =
        LoyaltySettings(true, amountPerPoint = Money(1_000L), pointValue = Money(5L))
    private val luis = customer(31L, "Luis", points = 150L)
    private val eva = customer(32L, "Eva", points = 10_000L)

    private fun TestScope.withPoints(): CheckoutViewModel {
        catalog.value = catalog.value.copy(loyalty = program)
        val model = viewModel(customers = listOf(luis, eva))
        model.act(this, CheckoutAction.PickCustomer(31L))
        return model
    }

    @Test
    fun `without the program or without a customer points are never mentioned`() = runTest {
        val off = viewModel(customers = listOf(luis))
        off.act(this, CheckoutAction.PickCustomer(31L))
        assertNull(off.state.points)

        catalog.value = catalog.value.copy(loyalty = program)
        val noCustomer = viewModel(customers = listOf(luis), state = SavedStateHandle())
        assertNull(noCustomer.state.points)
    }

    @Test
    fun `redeeming a hundred points takes five hundred off and is never a payment`() = runTest {
        val model = withPoints()
        assertEquals(150L, model.state.points?.available)

        model.act(this, CheckoutAction.PointsChange("100"))
        // Lo escrito dice cuánto descontaría, sin tocar el total hasta canjear.
        assertEquals(Money(500L), model.state.points?.discount)
        assertEquals(Money(25_000L), model.state.summary.amountDue)
        model.act(this, CheckoutAction.RedeemPoints)

        assertEquals(100L, model.state.points?.redeemed)
        assertEquals(Money(500L), model.state.summary.pointsDiscount)
        assertEquals(Money(24_500L), model.state.summary.amountDue)
        // El pago único que cubría todo sigue cubriendo todo.
        assertEquals("24500", model.state.payments.single().amountText)
        assertEquals(CheckoutBalance.Complete, model.state.balance)
        result = RegisterSaleResult.Registered(99L, pointsEarned = 24L)
        model.act(this, CheckoutAction.Register)

        val request = requests.single()
        assertEquals(100L, request.pointsRedeemed)
        assertEquals(Money.ZERO, request.orderAdjustment)
        assertEquals(listOf(Money(24_500L)), request.payments.map { it.amount })
        assertEquals(24L, model.state.registered?.pointsEarned)
    }

    @Test
    fun `more points than the customer has are not redeemed`() = runTest {
        val model = withPoints()
        model.act(this, CheckoutAction.PointsChange("151"))
        model.act(this, CheckoutAction.RedeemPoints)

        assertEquals(CheckoutPointsError.NotEnough(150L), model.state.points?.error)
        assertEquals(0L, model.state.points?.redeemed)
        assertEquals(Money(25_000L), model.state.summary.amountDue)
        // Escribir de nuevo quita el aviso.
        model.act(this, CheckoutAction.PointsChange("15"))
        assertNull(model.state.points?.error)
    }

    @Test
    fun `points past the limit are more than the customer has`() = runTest {
        val model = withPoints()
        model.act(this, CheckoutAction.PointsChange("1000000000001"))
        model.act(this, CheckoutAction.RedeemPoints)

        assertEquals(CheckoutPointsError.NotEnough(150L), model.state.points?.error)
    }

    @Test
    fun `a discount above the sale is not redeemed`() = runTest {
        val model = withPoints()
        model.act(this, CheckoutAction.PickCustomer(32L))
        // 5001 puntos a 5 descuentan 25005, cinco más que la venta.
        model.act(this, CheckoutAction.PointsChange("5001"))
        model.act(this, CheckoutAction.RedeemPoints)

        assertEquals(CheckoutPointsError.AboveSale, model.state.points?.error)
        assertEquals(0L, model.state.points?.redeemed)
        model.act(this, CheckoutAction.PointsChange("5000"))
        model.act(this, CheckoutAction.RedeemPoints)
        assertEquals(Money.ZERO, model.state.summary.amountDue)
    }

    @Test
    fun `what is not a whole number is not redeemed, and blank removes the redemption`() = runTest {
        val model = withPoints()
        model.act(this, CheckoutAction.PointsChange("1,5"))
        model.act(this, CheckoutAction.RedeemPoints)
        assertEquals(CheckoutPointsError.NotANumber, model.state.points?.error)
        model.act(this, CheckoutAction.PointsChange("x"))
        model.act(this, CheckoutAction.RedeemPoints)
        assertEquals(CheckoutPointsError.NotANumber, model.state.points?.error)

        model.act(this, CheckoutAction.PointsChange("100"))
        model.act(this, CheckoutAction.RedeemPoints)
        model.act(this, CheckoutAction.PointsChange(""))
        model.act(this, CheckoutAction.RedeemPoints)

        assertEquals(0L, model.state.points?.redeemed)
        assertEquals(Money.ZERO, model.state.summary.pointsDiscount)
        assertEquals("25000", model.state.payments.single().amountText)
    }

    @Test
    fun `changing or removing the customer takes the redemption away`() = runTest {
        val model = withPoints()
        model.act(this, CheckoutAction.PointsChange("100"))
        model.act(this, CheckoutAction.RedeemPoints)

        model.act(this, CheckoutAction.PickCustomer(32L))
        assertEquals(0L, model.state.points?.redeemed)
        assertEquals("", model.state.points?.text)
        assertEquals("25000", model.state.payments.single().amountText)

        model.act(this, CheckoutAction.PointsChange("100"))
        model.act(this, CheckoutAction.RedeemPoints)
        model.act(this, CheckoutAction.RemoveCustomer)
        assertNull(model.state.points)
        assertTrue(model.state.customerRemoved)
        assertEquals(Money(25_000L), model.state.summary.amountDue)
        model.act(this, CheckoutAction.Register)
        assertEquals(0L, requests.single().pointsRedeemed)
    }

    @Test
    fun `turning the program off while charging stops the redemption from counting`() = runTest {
        val model = withPoints()
        model.act(this, CheckoutAction.PointsChange("100"))
        model.act(this, CheckoutAction.RedeemPoints)

        catalog.value = catalog.value.copy(loyalty = program.copy(enabled = false))
        advanceUntilIdle()

        assertNull(model.state.points)
        assertEquals(Money.ZERO, model.state.summary.pointsDiscount)
        model.act(this, CheckoutAction.UseDue)
        model.act(this, CheckoutAction.Register)
        assertEquals(0L, requests.single().pointsRedeemed)
    }

    @Test
    fun `a balance that changed while charging is said on the field`() = runTest {
        val model = withPoints()
        model.act(this, CheckoutAction.PointsChange("100"))
        model.act(this, CheckoutAction.RedeemPoints)
        result = RegisterSaleResult.NotEnoughPoints(40L)

        model.act(this, CheckoutAction.Register)

        assertEquals(CheckoutPointsError.NotEnough(40L), model.state.points?.error)
        assertEquals(RegisterStatus.IDLE, model.state.status)
        result = RegisterSaleResult.PointsAboveSale
        model.act(this, CheckoutAction.Register)
        assertEquals(CheckoutPointsError.AboveSale, model.state.points?.error)
        result = RegisterSaleResult.PointsNotAvailable
        model.act(this, CheckoutAction.Register)
        assertEquals(RegisterStatus.FAILED, model.state.status)
    }

    @Test
    fun `the redemption survives the process being closed`() = runTest {
        val first = withPoints()
        first.act(this, CheckoutAction.PointsChange("100"))
        first.act(this, CheckoutAction.RedeemPoints)

        val second = viewModel(draft = null, customers = listOf(luis))

        assertEquals(100L, second.state.points?.redeemed)
        assertEquals("100", second.state.points?.text)
        assertEquals(Money(24_500L), second.state.summary.amountDue)
    }
}

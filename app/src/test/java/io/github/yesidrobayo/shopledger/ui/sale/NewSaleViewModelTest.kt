package io.github.yesidrobayo.shopledger.ui.sale

import androidx.lifecycle.SavedStateHandle
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodDetails
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodRecord
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.sale.LineDiscount
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleCatalog
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxSettings
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.rule.sale.SaleCorrection
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.ui.product.COUNT_UNIT
import io.github.yesidrobayo.shopledger.ui.product.CREATED
import io.github.yesidrobayo.shopledger.ui.product.product
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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
 * La venta nueva sin base: agregar con un toque, sumar a la línea, los productos por peso y sin
 * precio, confirmar directo con el primer método, la venta en cero, las fallas sin perder nada,
 * salir con productos y la venta que sobrevive al cierre del proceso. Productos inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewSaleViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val kilo = MeasureUnit(2L, "KILOGRAM", null, null, UnitKind.MASS, true, null)
    private val rice = product(1L, "Arroz", salePrice = 4_200L, onHand = 1_000L)
    private val oil = product(2L, "Aceite", salePrice = 12_500L, onHand = 23_000L)
    private val cheese = product(3L, "Queso", salePrice = 22_000L).let {
        it.copy(details = it.details.copy(baseUnitId = kilo.id))
    }
    private val sample = product(4L, "Muestra")
    private val today = BusinessDate(LocalDate(2026, 11, 2))
    private val catalog = MutableStateFlow(catalogOf(listOf(rice, oil, cheese, sample)))
    private val requests = mutableListOf<SaleRequest>()
    private val corrections = mutableListOf<Long>()
    private var result: RegisterSaleResult = RegisterSaleResult.Registered(99L)
    private val savedState = SavedStateHandle()
    private val symbols = NumberSymbols(decimalSeparator = ',', minusSign = '-', grouping = null)
    private val numbers = SaleNumberFormat(symbols, 0, symbols, symbols)

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun catalogOf(
        products: List<Product>,
        archived: List<Product> = emptyList(),
        methods: List<PaymentMethodRecord> = listOf(method(10L, "CASH"), method(11L, "CARD")),
        taxes: TaxSettings = TaxSettings(TaxMode.NONE, null, purchaseTaxRecoverable = false),
    ) = SaleCatalog(
        products = products,
        frequent = products,
        archived = archived,
        units = listOf(COUNT_UNIT, kilo).associateBy { it.id },
        methods = methods,
        taxes = taxes,
        taxRates = mapOf(20L to BasisPoints(1_900L)),
        today = today,
        weekStartDay = DayOfWeek.MONDAY,
    )

    private fun method(id: Long, key: String, adjustment: Long = 0L) = PaymentMethodRecord(
        id = id,
        systemKey = key,
        details = PaymentMethodDetails(
            name = null,
            normalizedName = null,
            kind = PaymentMethodKind.valueOf(key),
            feeRate = BasisPoints.ZERO,
            feeFixed = Money.ZERO,
            settlementDays = 0,
            priceAdjustment = BasisPoints(adjustment),
            moneyAccountId = null,
            instructionsText = null,
            instructionsMediaId = null,
        ),
        sortOrder = id,
        archivedAt = null,
        createdAt = CREATED,
        updatedAt = CREATED,
    )

    private fun TestScope.viewModel(
        source: () -> Flow<SaleCatalog> = { catalog },
        prefill: Long? = null,
        state: SavedStateHandle = savedState,
        correctsSale: Long? = null,
        correction: SaleCorrection? = null,
    ): NewSaleViewModel {
        val model = NewSaleViewModel(
            catalogSource = source,
            registerSale = {
                requests += it
                result
            },
            reactivate = { it == 7L },
            savedState = state,
            numbers = numbers,
            computation = dispatcher,
            prefillProductId = prefill,
            prefillFromSaleId = correctsSale,
            loadCorrection = {
                corrections += it
                correction
            },
        )
        // El estado se arma mientras alguien lo mira, como en la pantalla.
        backgroundScope.launch { model.uiState.collect {} }
        advanceUntilIdle()
        return model
    }

    private fun NewSaleViewModel.act(scope: TestScope, action: NewSaleAction) {
        onAction(action)
        scope.advanceUntilIdle()
    }

    private val NewSaleViewModel.state: NewSaleUiState get() = uiState.value

    @Test
    fun `a correction opens with the lines, prices, method, date and note of the voided sale`() =
        runTest(dispatcher) {
            val past = today.plusDays(-3)
            val correction = SaleCorrection(
                lines = listOf(
                    SaleLineRequest(1L, QuantityMilli(2_000L), Money(4_000L), LineDiscount.None),
                    SaleLineRequest(
                        2L,
                        QuantityMilli(1_000L),
                        Money(12_500L),
                        LineDiscount.Amount(Money(500L)),
                    ),
                ),
                paymentMethodId = 11L,
                date = past,
                note = "Mesa 4",
                leftOut = 2,
            )
            val model = viewModel(correctsSale = 7L, correction = correction)

            assertEquals(listOf(7L), corrections)
            assertEquals(Money(20_000L), model.state.cart.total)
            assertEquals(11L, model.state.payment.selectedId)
            assertEquals(SaleNotice.LINES_LEFT_OUT, model.state.notice)
            assertEquals(2, model.state.leftOut)
            model.act(this, NewSaleAction.NoticeShown)
            model.act(this, NewSaleAction.Register)

            val request = requests.single()
            assertEquals(correction.lines, request.lines)
            assertEquals(11L, request.paymentMethodId)
            assertEquals(past, request.date)
            assertEquals("Mesa 4", request.note)
        }

    @Test
    fun `a correction is brought once, also after the process is closed`() = runTest(dispatcher) {
        val correction = SaleCorrection(
            lines = listOf(SaleLineRequest(1L, QuantityMilli(1_000L), Money(4_000L))),
            paymentMethodId = 10L,
            date = today,
            note = null,
            leftOut = 0,
        )
        val first = viewModel(correctsSale = 7L, correction = correction)
        first.act(this, NewSaleAction.Increment(1L))
        assertNull(first.state.notice)

        // Android cierra el proceso: la venta vuelve como estaba, sin traer la corrección otra vez.
        val again = viewModel(
            state = SavedStateHandle(savedState.keys().associateWith { savedState.get<Any>(it) }),
            correctsSale = 7L,
            correction = correction,
        )

        assertEquals(listOf(7L), corrections)
        assertEquals(QuantityMilli(2_000L), again.state.cart.lines.single().quantity)
    }

    @Test
    fun `a correction in cash rounds again, like any sale in cash`() = runTest(dispatcher) {
        // El redondeo de la venta anulada no viaja: la venta nueva lo calcula con su total.
        val bread = product(9L, "Pan", salePrice = 1_234L, onHand = 10_000L)
        catalog.value = catalogOf(listOf(bread)).copy(cashRounding = Money(50L))
        val correction = SaleCorrection(
            lines = listOf(SaleLineRequest(bread.id, QuantityMilli(1_000L), Money(1_234L))),
            paymentMethodId = 10L,
            date = today,
            note = null,
            leftOut = 0,
        )
        val model = viewModel(correctsSale = 7L, correction = correction)

        assertEquals(Money(1_250L), model.state.cart.total)
        assertEquals(Money(16L), model.state.cart.cashRounding)
        model.act(this, NewSaleAction.Register)

        assertEquals(RegisteredSale(99L, Money(1_250L)), model.state.registered)
    }

    @Test
    fun `a sale to correct that no longer exists opens an empty sale`() = runTest(dispatcher) {
        val model = viewModel(correctsSale = 404L, correction = null)

        assertTrue(model.state.cart.isEmpty)
        assertNull(model.state.notice)
    }

    @Test
    fun `it opens with the best sellers and nothing in the sale`() = runTest(dispatcher) {
        val model = viewModel()

        val content = model.state.content as SaleContent.Products
        assertEquals(listOf(1L, 2L, 3L, 4L), content.tiles.map { it.productId })
        assertFalse(content.searching)
        assertTrue(model.state.cart.isEmpty)
        assertEquals(10L, model.state.payment.selectedId)
    }

    @Test
    fun `with tax added and a card surcharge, the total is what the sale will charge`() =
        runTest(dispatcher) {
            catalog.value = catalogOf(
                listOf(oil),
                methods = listOf(method(10L, "CASH"), method(11L, "CARD", adjustment = 1_000L)),
                taxes = TaxSettings(TaxMode.ADDED, 20L, purchaseTaxRecoverable = false),
            )
            val model = viewModel()
            model.act(this, NewSaleAction.AddProduct(oil.id))
            // 12.500 más 19 % de impuesto.
            assertEquals(Money(14_875L), model.state.cart.total)
            assertEquals(Money(2_375L), model.state.cart.tax)

            model.act(this, NewSaleAction.SelectMethod(11L))

            // El recargo del 10 % sale de lo que se cobra, 1.488, y paga impuesto: 13.988 + 2.658.
            assertEquals(Money(16_646L), model.state.cart.total)
        }

    @Test
    fun `an empty catalog invites to create the first product`() = runTest(dispatcher) {
        catalog.value = catalogOf(emptyList())

        assertEquals(SaleContent.EmptyCatalog, viewModel().state.content)
    }

    @Test
    fun `a read failure shows the error and retry reads again`() = runTest(dispatcher) {
        var fail = true
        val model = viewModel(source = {
            flow {
                if (fail) error("Falla inventada")
                emit(catalog.value)
            }
        })
        assertEquals(SaleContent.ReadError, model.state.content)

        fail = false
        model.act(this, NewSaleAction.RetryRead)

        assertTrue(model.state.content is SaleContent.Products)
    }

    @Test
    fun `one tap adds one, a second tap adds to the same line, and selling short only warns`() =
        runTest(dispatcher) {
            val model = viewModel()

            model.act(this, NewSaleAction.AddProduct(rice.id))
            model.act(this, NewSaleAction.AddProduct(rice.id))

            val line = model.state.cart.lines.single()
            assertEquals(QuantityMilli(2_000L), line.quantity)
            assertEquals(Money(8_400L), model.state.cart.total)
            assertEquals(2L, model.state.cart.itemCount)
            val tile = (model.state.content as SaleContent.Products).tiles.first()
            assertEquals(QuantityMilli(-1_000L), tile.line?.oversell)
        }

    @Test
    fun `minus on the last unit removes the line`() = runTest(dispatcher) {
        val model = viewModel()
        model.act(this, NewSaleAction.AddProduct(oil.id))
        model.act(this, NewSaleAction.Increment(oil.id))
        model.act(this, NewSaleAction.Decrement(oil.id))
        assertTrue(model.state.cart.lines.single().removesLine)

        model.act(this, NewSaleAction.Decrement(oil.id))

        assertTrue(model.state.cart.isEmpty)
    }

    @Test
    fun `a product by weight opens the quantity sheet and a shortcut adds and closes`() =
        runTest(dispatcher) {
            val model = viewModel()

            model.act(this, NewSaleAction.AddProduct(cheese.id))
            val sheet = model.state.sheet as SaleSheet.Quantity
            assertTrue(sheet.adding)
            model.act(this, NewSaleAction.QuantityShortcut(QuantityMilli(500L)))

            assertNull(model.state.sheet)
            assertEquals(QuantityMilli(500L), model.state.cart.lines.single().quantity)
            // Por peso, la línea cuenta como un producto.
            assertEquals(1L, model.state.cart.itemCount)
            assertEquals(Money(11_000L), model.state.cart.total)
        }

    @Test
    fun `the quantity sheet reads what was typed and marks a zero`() = runTest(dispatcher) {
        val model = viewModel()
        model.act(this, NewSaleAction.AddProduct(cheese.id))

        model.act(this, NewSaleAction.QuantityTextChange("0"))
        model.act(this, NewSaleAction.QuantityConfirm)
        assertEquals(SaleFieldError.ZERO_QUANTITY, (model.state.sheet as SaleSheet.Quantity).error)

        model.act(this, NewSaleAction.QuantityTextChange("1,25"))
        model.act(this, NewSaleAction.QuantityConfirm)
        assertEquals(QuantityMilli(1_250L), model.state.cart.lines.single().quantity)

        model.act(this, NewSaleAction.EditQuantity(cheese.id))
        val edit = model.state.sheet as SaleSheet.Quantity
        assertFalse(edit.adding)
        assertEquals("1,25", edit.text)
    }

    @Test
    fun `a product without price opens its line with the price focused`() = runTest(dispatcher) {
        val model = viewModel()

        model.act(this, NewSaleAction.AddProduct(sample.id))

        val editor = (model.state.sheet as SaleSheet.Line).editor
        assertTrue(editor.focusPrice)
        assertEquals("", editor.priceText)
        model.act(this, NewSaleAction.LinePriceChange("3500"))
        assertEquals(Money(3_500L), model.state.cart.total)
        model.act(this, NewSaleAction.LinePriceChange("abc"))
        assertEquals(
            SaleFieldError.NOT_A_NUMBER,
            (model.state.sheet as SaleSheet.Line).editor.priceError,
        )
        // Lo mal escrito no cambia la línea: conserva su último precio bueno.
        assertEquals(Money(3_500L), model.state.cart.total)
    }

    @Test
    fun `a discount over the line is marked and the sale is not confirmed`() = runTest(dispatcher) {
        val model = viewModel()
        model.act(this, NewSaleAction.AddProduct(rice.id))
        model.act(this, NewSaleAction.OpenLine(rice.id))

        model.act(this, NewSaleAction.LineDiscountChange("5000"))

        assertNull(model.state.cart.total)
        assertFalse(model.state.cart.canRegister)
        assertEquals(
            SaleFieldError.DISCOUNT_OVER_LINE,
            (model.state.sheet as SaleSheet.Line).editor.discountError,
        )
        model.act(this, NewSaleAction.CloseSheet)
        model.act(this, NewSaleAction.Register)
        assertTrue(requests.isEmpty())
        assertTrue(model.state.sheet is SaleSheet.Line)
    }

    @Test
    fun `a ten percent discount takes it off the line`() = runTest(dispatcher) {
        val model = viewModel()
        model.act(this, NewSaleAction.AddProduct(oil.id))
        model.act(this, NewSaleAction.OpenLine(oil.id))

        model.act(this, NewSaleAction.LineDiscountModeChange(DiscountMode.PERCENT))
        model.act(this, NewSaleAction.LineDiscountChange("10"))

        assertEquals(Money(11_250L), model.state.cart.total)
        model.act(this, NewSaleAction.LineDiscountChange("150"))
        assertEquals(
            SaleFieldError.DISCOUNT_OVER_LINE,
            (model.state.sheet as SaleSheet.Line).editor.discountError,
        )
    }

    @Test
    fun `register confirms directly with the first method and goes back with the total`() =
        runTest(dispatcher) {
            val model = viewModel()
            model.act(this, NewSaleAction.AddProduct(oil.id))

            model.act(this, NewSaleAction.Register)

            val request = requests.single()
            assertEquals(listOf(SaleLineRequest(oil.id, QuantityMilli(1_000L))), request.lines)
            assertEquals(10L, request.paymentMethodId)
            assertNull(request.date)
            assertNull(request.note)
            assertEquals(RegisteredSale(99L, Money(12_500L)), model.state.registered)
            assertTrue(SaleDraftStore.read(savedState).lines.isEmpty())
        }

    @Test
    fun `the chosen method, a past date and the note go with the sale`() = runTest(dispatcher) {
        val model = viewModel()
        model.act(this, NewSaleAction.AddProduct(oil.id))
        model.act(this, NewSaleAction.SelectMethod(11L))
        model.act(this, NewSaleAction.DateChange(today.plusDays(-1)))
        model.act(this, NewSaleAction.NoteChange("Mesa 4"))

        model.act(this, NewSaleAction.Register)

        val request = requests.single()
        assertEquals(11L, request.paymentMethodId)
        assertEquals(today.plusDays(-1), request.date)
        assertEquals("Mesa 4", request.note)
    }

    @Test
    fun `a sale of zero asks first, the only sale that does`() = runTest(dispatcher) {
        val model = viewModel()
        model.act(this, NewSaleAction.AddProduct(sample.id))
        model.act(this, NewSaleAction.CloseSheet)

        model.act(this, NewSaleAction.Register)
        assertEquals(SaleDialog.ZeroTotal, model.state.dialog)
        assertTrue(requests.isEmpty())

        model.act(this, NewSaleAction.ConfirmZeroTotal)
        assertEquals(1, requests.size)
    }

    @Test
    fun `a failed write keeps the whole sale and can be retried`() = runTest(dispatcher) {
        result = RegisterSaleResult.StorageFailed(StorageFailure.DISK_FULL)
        val model = viewModel()
        model.act(this, NewSaleAction.AddProduct(oil.id))

        model.act(this, NewSaleAction.Register)
        assertEquals(RegisterStatus.FAILED, model.state.status)
        assertEquals(1, model.state.cart.lines.size)

        result = RegisterSaleResult.Registered(5L)
        model.act(this, NewSaleAction.Register)
        assertEquals(5L, model.state.registered?.saleId)
    }

    @Test
    fun `without a confirmed currency the sale asks for it and keeps everything`() =
        runTest(dispatcher) {
            result = RegisterSaleResult.CurrencyNotConfirmed
            val model = viewModel()
            model.act(this, NewSaleAction.AddProduct(oil.id))

            model.act(this, NewSaleAction.Register)

            assertEquals(SaleSheet.CurrencyRequired, model.state.sheet)
            assertEquals(RegisterStatus.IDLE, model.state.status)
            assertEquals(1, model.state.cart.lines.size)
        }

    @Test
    fun `leaving with products asks before discarding, and without them just closes`() =
        runTest(dispatcher) {
            val model = viewModel()
            model.act(this, NewSaleAction.AddProduct(oil.id))
            model.act(this, NewSaleAction.Close)
            assertEquals(SaleDialog.Leave(1L), model.state.dialog)
            model.act(this, NewSaleAction.DismissDialog)
            assertFalse(model.state.closed)

            model.act(this, NewSaleAction.ConfirmLeave)
            assertTrue(model.state.closed)

            val empty = viewModel(state = SavedStateHandle())
            empty.act(this, NewSaleAction.Close)
            assertTrue(empty.state.closed)
        }

    @Test
    fun `clearing the sale asks first and keeps the chosen method`() = runTest(dispatcher) {
        val model = viewModel()
        model.act(this, NewSaleAction.AddProduct(oil.id))
        model.act(this, NewSaleAction.SelectMethod(11L))

        model.act(this, NewSaleAction.ClearSale)
        assertEquals(SaleDialog.Clear(1L), model.state.dialog)
        model.act(this, NewSaleAction.ConfirmClear)

        assertTrue(model.state.cart.isEmpty)
        assertEquals(11L, model.state.payment.selectedId)
    }

    @Test
    fun `the sale in progress survives the process being closed`() = runTest(dispatcher) {
        val first = viewModel()
        first.act(this, NewSaleAction.AddProduct(oil.id))
        first.act(this, NewSaleAction.OpenLine(oil.id))
        first.act(this, NewSaleAction.LineDiscountModeChange(DiscountMode.PERCENT))
        first.act(this, NewSaleAction.LineDiscountChange("10"))

        val restored = viewModel()

        assertEquals(
            LineDiscount.Percent(BasisPoints(1_000L)),
            SaleDraftStore.read(savedState).lines.single().discount,
        )
        assertEquals(Money(11_250L), restored.state.cart.total)
    }

    @Test
    fun `searching filters after a pause, and offers to create what is not there`() =
        runTest(dispatcher) {
            catalog.value = catalogOf(listOf(rice, oil), archived = listOf(product(7L, "Arrocera")))
            val model = viewModel()

            model.onAction(NewSaleAction.QueryChange("arr"))
            advanceTimeBy(NewSaleViewModel.SEARCH_PAUSE_MILLIS + 1)
            advanceUntilIdle()
            val found = model.state.content as SaleContent.Products
            assertEquals(listOf(1L), found.tiles.map { it.productId })
            assertTrue(found.searching)
            assertEquals(listOf(7L), found.archived.map { it.productId })

            model.act(this, NewSaleAction.QueryChange("leche"))
            assertEquals(SaleContent.NoResults("leche"), model.state.content)
        }

    @Test
    fun `a product created from the search is added when it appears`() = runTest(dispatcher) {
        val model = viewModel()
        model.act(this, NewSaleAction.QueryChange("leche"))
        model.act(this, NewSaleAction.CreateProduct("leche"))

        val milk = product(8L, "Leche", salePrice = 3_000L)
        catalog.value = catalogOf(listOf(rice, oil, cheese, sample, milk))
        advanceUntilIdle()

        assertEquals(listOf(milk.id), model.state.cart.lines.map { it.productId })
        assertEquals("", model.state.query)
    }

    @Test
    fun `a product opened from its detail is added once`() = runTest(dispatcher) {
        val model = viewModel(prefill = oil.id)
        assertEquals(1, model.state.cart.lines.size)

        catalog.value = catalogOf(listOf(rice, oil, cheese))
        advanceUntilIdle()

        assertEquals(QuantityMilli(1_000L), model.state.cart.lines.single().quantity)
    }

    @Test
    fun `a failed reactivation is told, and a chosen method that is gone falls back`() =
        runTest(dispatcher) {
            val model = viewModel()
            model.act(this, NewSaleAction.Reactivate(9L))
            assertEquals(SaleNotice.REACTIVATE_FAILED, model.state.notice)
            model.act(this, NewSaleAction.NoticeShown)
            assertNull(model.state.notice)

            model.act(this, NewSaleAction.SelectMethod(11L))
            catalog.value = catalogOf(listOf(rice), methods = listOf(method(10L, "CASH")))
            advanceUntilIdle()
            assertEquals(10L, model.state.payment.selectedId)
        }

    @Test
    fun `in cash the panel shows what is charged, rounded, and a card shows the exact total`() =
        runTest(dispatcher) {
            val bread = product(9L, "Pan", salePrice = 1_234L, onHand = 10_000L)
            catalog.value = catalogOf(listOf(bread)).copy(cashRounding = Money(50L))
            val model = viewModel()
            model.act(this, NewSaleAction.AddProduct(bread.id))

            assertEquals(Money(1_250L), model.state.cart.total)
            assertEquals(Money(16L), model.state.cart.cashRounding)

            model.act(this, NewSaleAction.SelectMethod(11L))

            assertEquals(Money(1_234L), model.state.cart.total)
            assertEquals(Money.ZERO, model.state.cart.cashRounding)

            model.act(this, NewSaleAction.SelectMethod(10L))
            model.act(this, NewSaleAction.Register)

            assertEquals(RegisteredSale(99L, Money(1_250L)), model.state.registered)
        }
}

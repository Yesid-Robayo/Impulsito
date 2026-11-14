package io.github.yesidrobayo.shopledger.ui.sale

import androidx.lifecycle.SavedStateHandle
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodDetails
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodRecord
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleCatalog
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxSettings
import io.github.yesidrobayo.shopledger.domain.model.stock.LotChoice
import io.github.yesidrobayo.shopledger.domain.model.stock.LotChoices
import io.github.yesidrobayo.shopledger.domain.model.stock.LotDue
import io.github.yesidrobayo.shopledger.domain.model.stock.LotStock
import io.github.yesidrobayo.shopledger.domain.rule.sale.CartLineIssue
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Vender un producto con lotes desde la venta nueva, sin base: la línea avisa si el lote que sale
 * ya venció, sin frenar la venta, y la hoja deja elegir otro lote o volver al automático. Productos,
 * lotes y fechas inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewSaleLotsTest {
    private val dispatcher = StandardTestDispatcher()
    private val box = MeasureUnit(5L, "BOX", null, null, UnitKind.COUNT, false, null)
    private val kilo = MeasureUnit(2L, "KILOGRAM", null, null, UnitKind.MASS, true, null)
    private val oil = product(2L, "Aceite", salePrice = 12_500L, onHand = 23_000L).let {
        it.copy(details = it.details.copy(tracksLots = true))
    }
    private val cheese = product(3L, "Queso", salePrice = 22_000L).let {
        it.copy(details = it.details.copy(baseUnitId = kilo.id))
    }
    private val conversions = mapOf(
        oil.id to mapOf(box.id to ConversionDetails(box.id, 12_000L, null, null, true, false)),
        cheese.id to
            mapOf(box.id to ConversionDetails(box.id, 5_000L, Money(100_000L), null, true, false)),
    )
    private val catalog = MutableStateFlow(catalogOf(conversions))
    private val requests = mutableListOf<SaleRequest>()
    private val lotReads = mutableListOf<Long>()
    private var choices = LotChoices(
        listOf(
            choice(31L, "A-1", "2026-10-30", -3),
            choice(32L, "A-2", "2026-11-20", 18),
        ),
        null,
    )
    private val symbols = NumberSymbols(decimalSeparator = ',', minusSign = '-', grouping = null)

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun catalogOf(byProduct: Map<Long, Map<Long, ConversionDetails>>) = SaleCatalog(
        products = listOf(oil, cheese),
        frequent = listOf(oil, cheese),
        archived = emptyList(),
        units = listOf(COUNT_UNIT, kilo, box).associateBy { it.id },
        methods = listOf(
            PaymentMethodRecord(
                id = 10L,
                systemKey = "CASH",
                details = PaymentMethodDetails(
                    name = null,
                    normalizedName = null,
                    kind = PaymentMethodKind.CASH,
                    feeRate = BasisPoints.ZERO,
                    feeFixed = Money.ZERO,
                    settlementDays = 0,
                    priceAdjustment = BasisPoints.ZERO,
                    moneyAccountId = null,
                    instructionsText = null,
                    instructionsMediaId = null,
                ),
                sortOrder = 1,
                archivedAt = null,
                createdAt = CREATED,
                updatedAt = CREATED,
            ),
        ),
        taxes = TaxSettings(TaxMode.NONE, null, purchaseTaxRecoverable = false),
        taxRates = emptyMap(),
        today = BusinessDate(LocalDate(2026, 11, 2)),
        weekStartDay = DayOfWeek.MONDAY,
        conversions = byProduct,
    )

    private fun TestScope.viewModel(): NewSaleViewModel {
        val model = NewSaleViewModel(
            catalogSource = { catalog },
            registerSale = {
                requests += it
                RegisterSaleResult.Registered(99L)
            },
            reactivate = { false },
            savedState = SavedStateHandle(),
            numbers = SaleNumberFormat(symbols, 0, symbols, symbols),
            computation = dispatcher,
            lotChoices = { productId, _ ->
                lotReads += productId
                choices.takeIf { productId == oil.id }
            },
        )
        backgroundScope.launch { model.uiState.collect {} }
        advanceUntilIdle()
        return model
    }

    private fun NewSaleViewModel.act(scope: TestScope, action: NewSaleAction) {
        onAction(action)
        scope.advanceUntilIdle()
    }

    private val NewSaleViewModel.state: NewSaleUiState get() = uiState.value

    private val NewSaleViewModel.editor: LineEditor
        get() = (state.sheet as SaleSheet.Line).editor

    private fun choice(id: Long, code: String, date: String, left: Int) = LotChoice(
        LotStock(id, code, BusinessDate.parse(date), false, QuantityMilli(2_000L)),
        LotDue(requireNotNull(BusinessDate.parse(date)), left, soon = left <= 30),
    )

    @Test
    fun `a line of a lot product warns that the lot going out expired, and never blocks`() =
        runTest(dispatcher) {
            val model = viewModel()
            model.act(this, NewSaleAction.AddProduct(oil.id))

            assertEquals(listOf(oil.id), lotReads)
            val line = model.state.cart.lines.single { it.productId == oil.id }
            assertEquals(BusinessDate.parse("2026-10-30"), line.expiredLot)
            assertEquals(choices, model.state.lots[oil.id])

            model.act(this, NewSaleAction.Register)
            assertEquals(1, requests.size)
        }

    @Test
    fun `choosing a lot sells from it and drops the warning, and auto goes back`() =
        runTest(dispatcher) {
            val model = viewModel()
            model.act(this, NewSaleAction.AddProduct(oil.id))
            model.act(this, NewSaleAction.OpenLine(oil.id))

            model.act(this, NewSaleAction.LineLotChange(32L))
            val chosen = model.state.cart.lines.single()
            assertEquals(32L, chosen.lotId)
            assertNull(chosen.expiredLot)

            model.act(this, NewSaleAction.LineLotChange(null))
            assertEquals(
                BusinessDate.parse("2026-10-30"),
                model.state.cart.lines.single().expiredLot,
            )

            model.act(this, NewSaleAction.LineLotChange(32L))
            model.act(this, NewSaleAction.CloseSheet)
            model.act(this, NewSaleAction.Register)
            assertEquals(32L, requests.single().lines.single().lotId)
        }

    @Test
    fun `without lots to read the line has no picker and no warning`() = runTest(dispatcher) {
        choices = LotChoices(emptyList(), null)
        val model = viewModel()
        model.act(this, NewSaleAction.AddProduct(oil.id))

        assertNull(model.state.cart.lines.single().expiredLot)
    }
}

package io.github.yesidrobayo.shopledger.ui.sale

import androidx.lifecycle.SavedStateHandle
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductUsage
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodDetails
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodRecord
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.sale.LineDiscount
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleCatalog
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxSettings
import io.github.yesidrobayo.shopledger.domain.rule.sale.SaleCorrection
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * La venta sin insumos: no se ofrecen en los más vendidos ni en la búsqueda, ni entran por su
 * código, y una corrección que deja fuera líneas de insumos lo dice con su propio aviso. Lo que
 * también se vende sí se ofrece. Productos inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewSaleUsageTest {
    private val dispatcher = StandardTestDispatcher()
    private val flour = product(1L, "Harina", salePrice = 3_000L, onHand = 5_000L, barcode = CODE)
        .used(ProductUsage.INPUT)
    private val sugar = product(2L, "Azúcar", salePrice = 2_000L, onHand = 5_000L)
        .used(ProductUsage.BOTH)
    private val oil = product(3L, "Aceite", salePrice = 12_500L, onHand = 5_000L)

    // Lo que da el caso de uso del catálogo: el catálogo completo, sin los insumos.
    private val catalog = MutableStateFlow(catalogOf(listOf(oil, sugar, flour)).forSale())
    private val symbols = NumberSymbols(decimalSeparator = ',', minusSign = '-', grouping = null)

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun Product.used(usage: ProductUsage) = copy(details = details.copy(usage = usage))

    private fun catalogOf(products: List<Product>) = SaleCatalog(
        products = products,
        frequent = products,
        archived = emptyList(),
        units = mapOf(COUNT_UNIT.id to COUNT_UNIT),
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
    )

    private fun TestScope.viewModel(
        correctsSale: Long? = null,
        correction: SaleCorrection? = null,
    ): NewSaleViewModel {
        val model = NewSaleViewModel(
            catalogSource = { catalog },
            registerSale = { RegisterSaleResult.Registered(99L) },
            reactivate = { false },
            savedState = SavedStateHandle(),
            numbers = SaleNumberFormat(symbols, 0, symbols, symbols),
            computation = dispatcher,
            prefillFromSaleId = correctsSale,
            loadCorrection = { correction },
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

    private fun oilLine() =
        SaleLineRequest(oil.id, QuantityMilli(1_000L), Money(12_500L), LineDiscount.None)

    private val NewSaleViewModel.tileIds: List<Long>
        get() = (state.content as SaleContent.Products).tiles.map { it.productId }

    @Test
    fun `an input is neither offered nor found, but what is also sold is`() =
        runTest(dispatcher) {
            val model = viewModel()

            assertEquals(listOf(oil.id, sugar.id), model.tileIds)

            model.act(this, NewSaleAction.QueryChange("Harina"))
            assertTrue(model.state.content is SaleContent.NoResults)
            model.act(this, NewSaleAction.QueryChange("Azúcar"))
            assertEquals(listOf(sugar.id), model.tileIds)
        }

    @Test
    fun `the code of an input adds nothing, typed or scanned`() = runTest(dispatcher) {
        val model = viewModel()

        model.act(this, NewSaleAction.AddScanned(flour.id, unitId = null))
        model.act(this, NewSaleAction.QueryChange(CODE))
        model.act(this, NewSaleAction.SubmitSearch)
        model.act(this, NewSaleAction.AddProduct(flour.id))

        assertEquals(emptyList<Any>(), model.state.cart.lines)
    }

    @Test
    fun `a correction without its input lines says why, counting the archived ones too`() =
        runTest(dispatcher) {
            val correction = SaleCorrection(
                lines = listOf(oilLine()),
                paymentMethodId = 10L,
                date = BusinessDate(LocalDate(2026, 11, 2)),
                note = null,
                leftOut = 1,
                notSoldLeftOut = 2,
            )

            val model = viewModel(correctsSale = 5L, correction = correction)

            assertEquals(SaleNotice.LINES_NOT_SOLD, model.state.notice)
            assertEquals(3, model.state.leftOut)
            assertEquals(listOf(oil.id), model.state.cart.lines.map { it.productId })
        }

    @Test
    fun `a correction with only archived lines keeps the archived notice`() =
        runTest(dispatcher) {
            val correction = SaleCorrection(
                lines = listOf(oilLine()),
                paymentMethodId = 10L,
                date = BusinessDate(LocalDate(2026, 11, 2)),
                note = null,
                leftOut = 2,
            )

            val model = viewModel(correctsSale = 5L, correction = correction)

            assertEquals(SaleNotice.LINES_LEFT_OUT, model.state.notice)
            assertEquals(2, model.state.leftOut)
        }

    private companion object {
        const val CODE = "2900000111116"
    }
}

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
import io.github.yesidrobayo.shopledger.domain.model.sale.HeldSale
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleCatalog
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxSettings
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.sale.HoldSaleRequest
import io.github.yesidrobayo.shopledger.domain.usecase.sale.HoldSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.ResumeHeldSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.ResumedSale
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
 * Poner en espera y retomar desde la venta nueva, sin base: lo puesto en espera deja la pantalla
 * lista para otra persona, una falla no pierde nada, retomar con otra venta en edición la guarda
 * primero, y la venta retomada sale de la espera al confirmarla. Productos inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewSaleHeldSaleTest {
    private val dispatcher = StandardTestDispatcher()
    private val rice = product(1L, "Arroz", salePrice = 4_200L, onHand = 1_000L)
    private val oil = product(2L, "Aceite", salePrice = 12_500L, onHand = 23_000L)
    private val held = MutableStateFlow<List<HeldSale>>(emptyList())
    private val holds = mutableListOf<HoldSaleRequest>()
    private var holdResult: HoldSaleResult = HoldSaleResult.Held(5L)
    private val resumed = mutableListOf<Long>()
    private var resumeResult: ResumeHeldSaleResult = ResumeHeldSaleResult.NotFound
    private val registered = mutableListOf<SaleRequest>()
    private val savedState = SavedStateHandle()
    private val symbols = NumberSymbols(decimalSeparator = ',', minusSign = '-', grouping = null)

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private val catalog = SaleCatalog(
        products = listOf(rice, oil),
        frequent = listOf(rice, oil),
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

    private fun TestScope.viewModel(state: SavedStateHandle = savedState): NewSaleViewModel {
        val model = NewSaleViewModel(
            catalogSource = { MutableStateFlow(catalog) },
            registerSale = {
                registered += it
                RegisterSaleResult.Registered(99L)
            },
            reactivate = { false },
            savedState = state,
            numbers = SaleNumberFormat(symbols, 0, symbols, symbols),
            computation = dispatcher,
            heldSales = { held },
            holdSale = {
                holds += it
                holdResult
            },
            resumeHeldSale = {
                resumed += it
                resumeResult
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

    private fun heldSale(id: Long) = HeldSale(id, null, emptyList(), CREATED, CREATED)

    @Test
    fun `holding leaves the screen ready for the next person`() = runTest(dispatcher) {
        val model = viewModel()
        model.act(this, NewSaleAction.AddProduct(rice.id))
        model.act(this, NewSaleAction.AddProduct(oil.id))

        model.act(this, NewSaleAction.OpenHold)
        model.act(this, NewSaleAction.HoldLabelChange("Mesa 4"))
        model.act(this, NewSaleAction.ConfirmHold)

        val request = holds.single()
        assertEquals(listOf(rice.id, oil.id), request.lines.map { it.productId })
        assertEquals("Mesa 4", request.label)
        assertNull(request.heldSaleId)
        assertTrue(model.state.cart.isEmpty)
        assertNull(model.state.sheet)
        assertEquals(HeldNotice.Held, model.state.heldNotice)
    }

    @Test
    fun `a failed hold keeps the sale and the sheet with what was written`() = runTest(dispatcher) {
        holdResult = HoldSaleResult.StorageFailed(StorageFailure.DISK_FULL)
        val model = viewModel()
        model.act(this, NewSaleAction.AddProduct(rice.id))
        model.act(this, NewSaleAction.OpenHold)
        model.act(this, NewSaleAction.HoldLabelChange("Mesa 4"))

        model.act(this, NewSaleAction.ConfirmHold)

        assertEquals(1, model.state.cart.lines.size)
        assertEquals(SaleSheet.Hold("Mesa 4"), model.state.sheet)
        assertEquals(HeldNotice.HoldFailed, model.state.heldNotice)
    }

    @Test
    fun `a name over forty characters is marked and not saved`() = runTest(dispatcher) {
        val model = viewModel()
        model.act(this, NewSaleAction.AddProduct(rice.id))
        model.act(this, NewSaleAction.OpenHold)

        model.act(this, NewSaleAction.HoldLabelChange("x".repeat(41)))
        model.act(this, NewSaleAction.ConfirmHold)

        assertTrue((model.state.sheet as SaleSheet.Hold).tooLong)
        assertTrue(holds.isEmpty())
    }

    @Test
    fun `an empty sale has nothing to hold`() = runTest(dispatcher) {
        val model = viewModel()

        model.act(this, NewSaleAction.OpenHold)

        assertNull(model.state.sheet)
    }

    @Test
    fun `a resumed sale leaves the wait when it is registered`() = runTest(dispatcher) {
        resumeResult = ResumeHeldSaleResult.Resumed(
            ResumedSale(
                heldSaleId = 7L,
                label = "Mesa 4",
                lines = listOf(SaleLineRequest(oil.id, QuantityMilli(2_000L), Money(12_000L))),
                removedProducts = 1,
                changedPrices = 2,
            ),
        )
        val model = viewModel()

        model.act(this, NewSaleAction.ResumeHeld(7L))

        assertEquals(listOf(7L), resumed)
        assertTrue(holds.isEmpty())
        assertEquals(7L, model.state.heldSaleId)
        assertEquals(Money(24_000L), model.state.cart.total)
        assertEquals(HeldNotice.Resumed(changedPrices = 2, removed = 1), model.state.heldNotice)
        model.act(this, NewSaleAction.Register)
        assertEquals(7L, registered.single().heldSaleId)
        assertNull(model.state.heldSaleId)
    }

    @Test
    fun `resuming over another sale puts that one on hold first`() = runTest(dispatcher) {
        resumeResult = ResumeHeldSaleResult.Resumed(
            ResumedSale(7L, null, listOf(SaleLineRequest(oil.id, QuantityMilli(1_000L))), 0, 0),
        )
        val model = viewModel()
        model.act(this, NewSaleAction.AddProduct(rice.id))

        model.act(this, NewSaleAction.ResumeHeld(7L))

        assertEquals(listOf(rice.id), holds.single().lines.map { it.productId })
        assertEquals(listOf(oil.id), model.state.cart.lines.map { it.productId })
        // Nada que decir: ningún precio cambió y ningún producto quedó fuera.
        assertNull(model.state.heldNotice)
    }

    @Test
    fun `if the current sale cannot be held, the chosen one is not resumed`() =
        runTest(dispatcher) {
            holdResult = HoldSaleResult.StorageFailed(StorageFailure.IO_ERROR)
            val model = viewModel()
            model.act(this, NewSaleAction.AddProduct(rice.id))

            model.act(this, NewSaleAction.ResumeHeld(7L))

            assertTrue(resumed.isEmpty())
            assertEquals(listOf(rice.id), model.state.cart.lines.map { it.productId })
            assertEquals(HeldNotice.HoldFailed, model.state.heldNotice)
        }

    @Test
    fun `a held sale that no longer exists says so`() = runTest(dispatcher) {
        val model = viewModel()

        model.act(this, NewSaleAction.ResumeHeld(7L))

        assertEquals(HeldNotice.ResumeFailed, model.state.heldNotice)
        assertTrue(model.state.cart.isEmpty)
    }

    @Test
    fun `holding a resumed sale saves it over itself with its name`() = runTest(dispatcher) {
        resumeResult = ResumeHeldSaleResult.Resumed(
            ResumedSale(7L, "Mesa 4", listOf(SaleLineRequest(oil.id, QuantityMilli(1_000L))), 0, 0),
        )
        val model = viewModel()
        model.act(this, NewSaleAction.ResumeHeld(7L))

        model.act(this, NewSaleAction.OpenHold)
        assertEquals("Mesa 4", (model.state.sheet as SaleSheet.Hold).label)
        model.act(this, NewSaleAction.ConfirmHold)

        assertEquals(7L, holds.single().heldSaleId)
    }

    @Test
    fun `the count leaves out the sale being edited`() = runTest(dispatcher) {
        held.value = listOf(heldSale(7L), heldSale(8L))
        resumeResult = ResumeHeldSaleResult.Resumed(
            ResumedSale(7L, null, listOf(SaleLineRequest(oil.id, QuantityMilli(1_000L))), 0, 0),
        )
        val model = viewModel()
        assertEquals(2, model.state.heldCount)

        model.act(this, NewSaleAction.ResumeHeld(7L))

        assertEquals(1, model.state.heldCount)
    }

    @Test
    fun `a resumed sale survives the process closing`() = runTest(dispatcher) {
        resumeResult = ResumeHeldSaleResult.Resumed(
            ResumedSale(7L, "Mesa 4", listOf(SaleLineRequest(oil.id, QuantityMilli(1_000L))), 0, 0),
        )
        val model = viewModel()
        model.act(this, NewSaleAction.ResumeHeld(7L))

        val again =
            viewModel(
                SavedStateHandle(
                    savedState.keys().associateWith {
                        savedState.get<Any?>(it)
                    },
                ),
            )

        assertEquals(7L, again.state.heldSaleId)
        assertEquals(listOf(oil.id), again.state.cart.lines.map { it.productId })
    }
}

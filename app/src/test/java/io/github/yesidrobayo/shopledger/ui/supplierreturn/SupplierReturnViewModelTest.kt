package io.github.yesidrobayo.shopledger.ui.supplierreturn

import androidx.lifecycle.SavedStateHandle
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Supplier
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierDetails
import io.github.yesidrobayo.shopledger.domain.model.purchase.NewPurchase
import io.github.yesidrobayo.shopledger.domain.model.purchase.NewPurchaseLine
import io.github.yesidrobayo.shopledger.domain.model.purchase.Purchase
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseHistoryRow
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLine
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.returns.LineAmounts
import io.github.yesidrobayo.shopledger.domain.model.returns.PurchaseReturnResolution
import io.github.yesidrobayo.shopledger.domain.model.returns.SupplierReturnLineRequest
import io.github.yesidrobayo.shopledger.domain.model.returns.SupplierReturnRequest
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.returns.RegisterSupplierReturnResult
import io.github.yesidrobayo.shopledger.domain.usecase.returns.ReturnablePurchase
import io.github.yesidrobayo.shopledger.domain.usecase.returns.ReturnablePurchaseLine
import io.github.yesidrobayo.shopledger.domain.usecase.returns.SupplierReturnForm
import io.github.yesidrobayo.shopledger.ui.product.COUNT_UNIT
import io.github.yesidrobayo.shopledger.ui.product.CREATED
import io.github.yesidrobayo.shopledger.ui.product.product
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * La devolución a proveedor sin base: lo que se ve es lo que se guarda, con compra y sin ella, lo
 * que pasa de lo comprado, el stock que quedaría negativo, el crédito sin proveedor, los errores sin
 * perder nada y lo elegido que sobrevive al cierre del proceso. Los valores son el ejemplo de la
 * regla: `Q = 12000`, `V = 14400` y `C = 1200`, y una línea de 2 unidades a 1400. Nombres inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SupplierReturnViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val flour =
        product(1L, "Harina", averageCost = 1_200L, stockValue = 14_400L, onHand = 12_000L)
    private val salt = product(2L, "Sal", averageCost = 500L, stockValue = 500L, onHand = 1_000L)
    private val supplier = Supplier(
        id = 5L,
        details = SupplierDetails("Molino", "molino", null, null, null, null),
        balance = Money(10_000L),
        archivedAt = null,
        createdAt = CREATED,
        updatedAt = CREATED,
    )
    private val date = BusinessDate(LocalDate(2026, 11, 2))
    private val line = PurchaseLine(
        id = 40L,
        purchaseId = 9L,
        content = NewPurchaseLine(
            productId = 1L,
            unitId = COUNT_UNIT.id,
            unitFactor = QuantityMilli(1_000L),
            enteredQuantity = QuantityMilli(2_000L),
            quantity = QuantityMilli(2_000L),
            unitCost = Money(1_400L),
            lineTotal = Money(2_800L),
            taxRate = BasisPoints.ZERO,
            taxNameSnapshot = null,
            taxAmount = Money.ZERO,
            costAmount = Money(2_800L),
            lotId = null,
            referenceUnitCost = null,
        ),
    )
    private val purchase = Purchase(
        id = 9L,
        content = NewPurchase(
            supplierId = 5L,
            invoiceReference = null,
            netTotal = Money(2_800L),
            taxTotal = Money.ZERO,
            total = Money(2_800L),
            costTotal = Money(2_800L),
            amountPaid = Money(2_800L),
            moneyAccountId = null,
            dueDate = null,
            locationId = 1L,
            foreignCurrency = null,
            occurredAt = CREATED,
            businessDate = date,
            createdAt = CREATED,
        ),
        voiding = null,
    )
    private val returned = MutableStateFlow(LineAmounts.NONE)
    private val requests = mutableListOf<SupplierReturnRequest>()
    private var result: RegisterSupplierReturnResult =
        RegisterSupplierReturnResult.Registered(31L, Money(2_800L))
    private val savedState = SavedStateHandle()
    private val symbols = NumberSymbols(decimalSeparator = ',', minusSign = '-', grouping = null)

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun formOf(purchaseId: Long?, supplierId: Long?): Flow<SupplierReturnForm> =
        returned.map { already ->
            SupplierReturnForm(
                products = listOf(flour, salt),
                units = mapOf(COUNT_UNIT.id to COUNT_UNIT),
                suppliers = listOf(supplier),
                supplier = supplier.takeIf { supplierId == it.id },
                purchase = if (purchaseId == purchase.id) {
                    ReturnablePurchase(
                        purchase,
                        listOf(ReturnablePurchaseLine(line, flour, already)),
                    )
                } else {
                    null
                },
            )
        }

    private fun TestScope.viewModel(
        purchaseId: Long? = null,
        supplierId: Long? = null,
        state: SavedStateHandle = savedState,
        source: (Long?, Long?) -> Flow<SupplierReturnForm> = ::formOf,
    ): SupplierReturnViewModel {
        val model = SupplierReturnViewModel(
            formSource = source,
            register = {
                requests += it
                result
            },
            purchasesOf = {
                listOf(
                    PurchaseHistoryRow(9L, CREATED, date, Money(2_800L), false, "Molino", 1, null),
                    PurchaseHistoryRow(8L, CREATED, date, Money(1_000L), true, null, 1, null),
                )
            },
            savedState = state,
            quantitySymbols = symbols,
            computation = dispatcher,
            prefillPurchaseId = purchaseId,
            prefillSupplierId = supplierId,
        )
        backgroundScope.launch { model.uiState.collect {} }
        advanceUntilIdle()
        return model
    }

    private fun SupplierReturnViewModel.act(scope: TestScope, action: SupplierReturnAction) {
        onAction(action)
        scope.advanceUntilIdle()
    }

    private val SupplierReturnViewModel.state: SupplierReturnUiState get() = uiState.value

    @Test
    fun `with a purchase each line shows its cost, the new average and registers what it shows`() =
        runTest(dispatcher) {
            val model = viewModel(purchaseId = 9L)
            assertEquals("Molino", model.state.supplierName)
            assertTrue(model.state.supplierLocked)
            assertEquals(ReturnOriginUi(9L, date), model.state.origin)

            model.act(this, SupplierReturnAction.QuantityChange(40L, "2"))

            val shown = model.state.lines.single()
            assertEquals(Money(1_400L), shown.unitCost)
            assertEquals(Money(2_800L), shown.lineTotal)
            assertEquals(Money(1_200L), shown.costBefore)
            assertEquals(Money(1_160L), shown.costAfter)
            assertEquals(Money(2_800L), model.state.total)
            model.act(
                this,
                SupplierReturnAction.ResolutionChange(PurchaseReturnResolution.SUPPLIER_CREDIT),
            )
            assertEquals(Money(7_200L), model.state.balanceAfter)

            model.act(this, SupplierReturnAction.Register)

            assertEquals(
                SupplierReturnRequest(
                    lines = listOf(
                        SupplierReturnLineRequest.FromPurchaseLine(40L, QuantityMilli(2_000L)),
                    ),
                    resolution = PurchaseReturnResolution.SUPPLIER_CREDIT,
                    purchaseId = 9L,
                    supplierId = 5L,
                ),
                requests.single(),
            )
            assertEquals(Money(2_800L), model.state.registered)
            model.act(this, SupplierReturnAction.RegisteredShown)
            assertTrue(model.closed.value)
        }

    @Test
    fun `more than what remains of the line is marked and not registered`() = runTest(dispatcher) {
        returned.value =
            LineAmounts(QuantityMilli(1_000L), Money(1_400L), Money.ZERO, Money(1_400L))
        val model = viewModel(purchaseId = 9L)

        model.act(this, SupplierReturnAction.QuantityChange(40L, "2"))
        model.act(this, SupplierReturnAction.Register)

        assertEquals(
            SupplierReturnLineError.AboveRemaining(QuantityMilli(1_000L)),
            model.state.lines.single().error,
        )
        assertNull(model.state.total)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `without a purchase products are added and a stock left negative warns`() =
        runTest(dispatcher) {
            val model = viewModel(supplierId = 5L)
            assertTrue(model.state.lines.isEmpty())

            model.act(this, SupplierReturnAction.OpenProducts)
            assertEquals(
                listOf(ReturnProductOption(1L, "Harina"), ReturnProductOption(2L, "Sal")),
                (model.state.sheet as SupplierReturnSheet.Products).options,
            )
            model.act(this, SupplierReturnAction.AddProduct(2L))
            val key = model.state.lines.single().key
            model.act(this, SupplierReturnAction.QuantityChange(key, "3"))

            val shown = model.state.lines.single()
            assertEquals(Money(1_500L), shown.lineTotal)
            assertEquals(QuantityMilli(-2_000L), shown.leftAfter)
            model.act(this, SupplierReturnAction.Register)
            assertEquals(
                listOf(SupplierReturnLineRequest.Loose(2L, QuantityMilli(3_000L))),
                requests.single().lines,
            )
        }

    @Test
    fun `a credit without supplier asks for one and registers nothing`() = runTest(dispatcher) {
        val model = viewModel()
        model.act(this, SupplierReturnAction.AddProduct(2L))
        model.act(this, SupplierReturnAction.QuantityChange(model.state.lines.single().key, "1"))
        model.act(
            this,
            SupplierReturnAction.ResolutionChange(PurchaseReturnResolution.SUPPLIER_CREDIT),
        )

        model.act(this, SupplierReturnAction.Register)

        assertTrue(model.state.creditNeedsSupplier)
        assertTrue(requests.isEmpty())
        model.act(this, SupplierReturnAction.OpenSuppliers)
        model.act(this, SupplierReturnAction.SelectSupplier(5L))
        assertEquals("Molino", model.state.supplierName)
    }

    @Test
    fun `fields with errors are marked and nothing is lost`() = runTest(dispatcher) {
        val model = viewModel()
        model.act(this, SupplierReturnAction.AddProduct(1L))
        val key = model.state.lines.single().key

        model.act(this, SupplierReturnAction.Register)
        assertEquals(SupplierReturnNotice.NothingToReturn, model.state.notice)
        assertEquals(SupplierReturnLineError.NotANumber, model.state.lines.single().error)

        model.act(this, SupplierReturnAction.QuantityChange(key, "1,5"))
        assertEquals(SupplierReturnLineError.FractionNotAllowed, model.state.lines.single().error)
        model.act(this, SupplierReturnAction.QuantityChange(key, "abc"))
        assertEquals(SupplierReturnLineError.NotANumber, model.state.lines.single().error)
        model.act(this, SupplierReturnAction.QuantityChange(key, "9999999999"))
        assertEquals(SupplierReturnLineError.TooHigh, model.state.lines.single().error)
        model.act(this, SupplierReturnAction.NoteChange("n".repeat(201)))
        assertTrue(model.state.noteTooLong)
        assertEquals("9999999999", model.state.lines.single().quantityText)
        model.act(this, SupplierReturnAction.RemoveLine(key))
        assertTrue(model.state.lines.isEmpty())
    }

    @Test
    fun `a failure keeps everything and retries with the same`() = runTest(dispatcher) {
        result = RegisterSupplierReturnResult.StorageFailed(StorageFailure.DISK_FULL)
        val model = viewModel(purchaseId = 9L)
        model.act(this, SupplierReturnAction.QuantityChange(40L, "1"))

        model.act(this, SupplierReturnAction.Register)
        assertEquals(SupplierReturnNotice.RegisterFailed(outOfSpace = true), model.state.notice)
        assertEquals("1", model.state.lines.single().quantityText)

        result = RegisterSupplierReturnResult.CurrencyNotConfirmed
        model.act(this, SupplierReturnAction.Retry)
        assertEquals(SupplierReturnNotice.CurrencyUnconfirmed, model.state.notice)
        result = RegisterSupplierReturnResult.AccountUnavailable(
            io.github.yesidrobayo.shopledger.domain.usecase.money.AccountIssue.ARCHIVED,
        )
        model.act(this, SupplierReturnAction.Retry)
        assertEquals(SupplierReturnNotice.AccountUnavailable, model.state.notice)
        result = RegisterSupplierReturnResult.TotalTooLarge
        model.act(this, SupplierReturnAction.Retry)
        assertEquals(SupplierReturnNotice.RegisterFailed(outOfSpace = false), model.state.notice)
        assertEquals(4, requests.size)
    }

    @Test
    fun `the origin can be chosen among active purchases and dropped`() = runTest(dispatcher) {
        val model = viewModel(supplierId = 5L)

        model.act(this, SupplierReturnAction.OpenPurchases)
        assertEquals(
            listOf(ReturnPurchaseOption(9L, date, "Molino", Money(2_800L))),
            (model.state.sheet as SupplierReturnSheet.Purchases).options,
        )
        model.act(this, SupplierReturnAction.SelectPurchase(9L))
        assertEquals(1, model.state.lines.size)
        assertNull(model.state.sheet)

        model.act(this, SupplierReturnAction.SelectPurchase(null))
        assertNull(model.state.origin)
        assertTrue(model.state.lines.isEmpty())
    }

    @Test
    fun `leaving with something written asks first`() = runTest(dispatcher) {
        val model = viewModel(purchaseId = 9L)
        model.act(this, SupplierReturnAction.Close)
        assertTrue(model.closed.value)

        val other = viewModel(purchaseId = 9L, state = SavedStateHandle())
        other.act(this, SupplierReturnAction.QuantityChange(40L, "1"))
        other.act(this, SupplierReturnAction.Close)
        assertTrue(other.state.discardAsked)
        other.act(this, SupplierReturnAction.DismissDialog)
        assertTrue(!other.state.discardAsked)
        other.act(this, SupplierReturnAction.Close)
        other.act(this, SupplierReturnAction.ConfirmLeave)
        assertTrue(other.closed.value)
    }

    @Test
    fun `what was chosen survives the process and a failed read retries`() = runTest(dispatcher) {
        val model = viewModel(purchaseId = 9L)
        model.act(this, SupplierReturnAction.QuantityChange(40L, "2"))
        model.act(this, SupplierReturnAction.NoteChange("Golpeado"))

        val restored = viewModel(purchaseId = null, state = savedState)
        assertEquals("2", restored.state.lines.single().quantityText)
        assertEquals("Golpeado", restored.state.note)

        var fail = true
        val flaky = viewModel(state = SavedStateHandle(), source = { purchaseId, supplierId ->
            flow {
                if (fail) error("Lectura inventada que falla")
                formOf(purchaseId, supplierId).collect { emit(it) }
            }
        })
        assertTrue(flaky.state.loadFailed)
        fail = false
        flaky.act(this, SupplierReturnAction.Retry)
        assertTrue(!flaky.state.loadFailed)
    }
}

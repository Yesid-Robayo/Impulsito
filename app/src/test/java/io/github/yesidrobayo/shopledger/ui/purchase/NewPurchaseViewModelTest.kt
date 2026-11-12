package io.github.yesidrobayo.shopledger.ui.purchase

import androidx.lifecycle.SavedStateHandle
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.Supplier
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseCatalog
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseCostChange
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineAmount
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineRejection
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineRequest
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseRequest
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxSettings
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxRateDetails
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.RegisterPurchaseResult
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
 * La compra nueva sin base: lo que se ve es lo que se guarda, costo unitario o total, el aviso de
 * costo muy distinto, el proveedor habitual, los errores sin perder nada, salir con líneas y la
 * compra que sobrevive al cierre del proceso. Productos y proveedores inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewPurchaseViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val kilo = MeasureUnit(2L, "KILOGRAM", null, null, UnitKind.MASS, true, null)
    private val box = MeasureUnit(3L, "BOX", null, null, UnitKind.COUNT, false, null)
    private val soap =
        product(1L, "Jabón", averageCost = 1_000L, stockValue = 10_000L, onHand = 10_000L)
    private val flour = product(
        2L,
        "Harina",
        averageCost = 8_000L,
        stockValue = 16_000L,
        onHand = 2_000L,
    )
        .let { it.copy(details = it.details.copy(baseUnitId = kilo.id, defaultSupplierId = 5L)) }
    private val archived = product(3L, "Vela").copy(archivedAt = CREATED)
    private val today = BusinessDate(LocalDate(2026, 11, 2))
    private val catalog = MutableStateFlow(catalogOf())
    private val requests = mutableListOf<PurchaseRequest>()
    private var result: RegisterPurchaseResult = RegisterPurchaseResult.Registered(
        purchaseId = 9L,
        costChanges = listOf(PurchaseCostChange(1L, Money(1_000L), Money(1_100L))),
    )
    private val savedState = SavedStateHandle()
    private val symbols = NumberSymbols(decimalSeparator = ',', minusSign = '-', grouping = null)
    private val numbers = PurchaseNumberFormat(symbols, 0, symbols)

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun catalogOf(
        products: List<Product> = listOf(soap, flour),
        taxes: TaxSettings = TaxSettings(TaxMode.NONE, null, purchaseTaxRecoverable = false),
        conversions: Map<Long, Map<Long, ConversionDetails>> = emptyMap(),
    ) = PurchaseCatalog(
        products = products,
        archived = listOf(archived),
        units = listOf(COUNT_UNIT, kilo, box).associateBy { it.id },
        suppliers = listOf(supplier(5L, "Proveedor uno"), supplier(6L, "Proveedor dos")),
        taxes = taxes,
        taxRates = mapOf(
            20L to TaxRateDetails("Tasa inventada", "tasa inventada", BasisPoints(2_100L)),
        ),
        today = today,
        weekStartDay = DayOfWeek.MONDAY,
        conversions = conversions,
    )

    private fun supplier(id: Long, name: String) = Supplier(
        id = id,
        details = SupplierDetails(name, name.lowercase(), null, null, null, null),
        balance = Money.ZERO,
        archivedAt = null,
        createdAt = CREATED,
        updatedAt = CREATED,
    )

    private fun TestScope.viewModel(
        source: () -> Flow<PurchaseCatalog> = { catalog },
        state: SavedStateHandle = savedState,
        prefillProduct: Long? = null,
        prefillSupplier: Long? = null,
    ): NewPurchaseViewModel {
        val model = NewPurchaseViewModel(
            catalogSource = source,
            registerPurchase = {
                requests += it
                result
            },
            reactivate = { it == 3L },
            savedState = state,
            numbers = numbers,
            computation = dispatcher,
            prefillProductId = prefillProduct,
            prefillSupplierId = prefillSupplier,
        )
        backgroundScope.launch { model.uiState.collect {} }
        advanceUntilIdle()
        return model
    }

    private fun NewPurchaseViewModel.act(scope: TestScope, action: NewPurchaseAction) {
        onAction(action)
        scope.advanceUntilIdle()
    }

    private val NewPurchaseViewModel.state: NewPurchaseUiState get() = uiState.value

    private fun NewPurchaseViewModel.line(index: Int = 0) = state.lines[index]

    @Test
    fun `a line shows its total and the cost it leaves, and registers what it shows`() =
        runTest(dispatcher) {
            val model = viewModel()
            model.act(this, NewPurchaseAction.AddProduct(1L))
            val key = model.line().key
            model.act(this, NewPurchaseAction.QuantityChange(key, "10"))
            model.act(this, NewPurchaseAction.AmountChange(key, "1200"))

            // 10 a 1200 dan 12000; el costo pasa de 1000 a (10000 + 12000) / 20 = 1100.
            assertEquals(Money(12_000L), model.line().lineTotal)
            assertEquals(Money(12_000L), model.line().derived)
            assertEquals(Money(1_000L), model.line().costBefore)
            assertEquals(Money(1_100L), model.line().costAfter)
            assertEquals(Money(12_000L), model.state.total)

            model.act(this, NewPurchaseAction.Register)

            val expected = PurchaseLineRequest(
                1L,
                QuantityMilli(10_000L),
                PurchaseLineAmount.UnitCost(Money(1_200L)),
            )
            assertEquals(listOf(PurchaseRequest(listOf(expected))), requests)
            val summary = requireNotNull(model.state.registered)
            assertEquals(Money(12_000L), summary.total)
            assertEquals(
                listOf(CostChangeUi(1L, "Jabón", Money(1_000L), Money(1_100L))),
                summary.costs,
            )
            // Lo escrito ya está guardado: el estado guardado vuelve a empezar.
            assertTrue(PurchaseDraftStore.read(savedState).lines.isEmpty())
            model.act(this, NewPurchaseAction.Finish)
            assertTrue(model.state.closed)
        }

    @Test
    fun `a line total shows the unit cost and keeps the total for the cost`() =
        runTest(dispatcher) {
            val model = viewModel()
            model.act(this, NewPurchaseAction.AddProduct(2L))
            val key = model.line().key
            model.act(this, NewPurchaseAction.AmountModeChange(key, byTotal = true))
            model.act(this, NewPurchaseAction.QuantityChange(key, "3"))
            model.act(this, NewPurchaseAction.AmountChange(key, "25000"))

            // 3 kg por 25000: 8333 por kilogramo, y el costo pasa de 8000 a 41000 / 5 = 8200.
            assertEquals(Money(8_333L), model.line().derived)
            assertEquals(Money(25_000L), model.line().lineTotal)
            assertEquals(Money(8_200L), model.line().costAfter)
        }

    @Test
    fun `changing the amount mode clears the written amount`() = runTest(dispatcher) {
        val model = viewModel()
        model.act(this, NewPurchaseAction.AddProduct(1L))
        val key = model.line().key
        model.act(this, NewPurchaseAction.AmountChange(key, "500"))
        model.act(this, NewPurchaseAction.AmountModeChange(key, byTotal = false))
        assertEquals("500", model.line().amountText)

        model.act(this, NewPurchaseAction.AmountModeChange(key, byTotal = true))

        assertEquals("", model.line().amountText)
        assertTrue(model.line().byTotal)
    }

    @Test
    fun `a cost far from the previous one warns without blocking`() = runTest(dispatcher) {
        val model = viewModel()
        model.act(this, NewPurchaseAction.AddProduct(1L))
        val key = model.line().key
        model.act(this, NewPurchaseAction.QuantityChange(key, "1"))
        model.act(this, NewPurchaseAction.AmountChange(key, "3001"))
        assertEquals(Money(1_000L), model.line().costDiffersFrom)

        model.act(this, NewPurchaseAction.AmountChange(key, "3000"))
        assertNull(model.line().costDiffersFrom)
        model.act(this, NewPurchaseAction.AmountChange(key, "333"))
        assertEquals(Money(1_000L), model.line().costDiffersFrom)

        model.act(this, NewPurchaseAction.Register)
        assertEquals(1, requests.size)
    }

    @Test
    fun `the usual supplier is chosen unless the person chose one`() = runTest(dispatcher) {
        val model = viewModel()
        model.act(this, NewPurchaseAction.AddProduct(2L))
        assertEquals("Proveedor uno", model.state.supplierName)

        model.act(this, NewPurchaseAction.SelectSupplier(null))
        model.act(this, NewPurchaseAction.AddProduct(2L))
        assertNull(model.state.supplierName)
        assertEquals(2, model.state.lines.size)
    }

    @Test
    fun `a supplier and a product given on entry are already chosen`() = runTest(dispatcher) {
        val model = viewModel(prefillProduct = 1L, prefillSupplier = 6L)

        assertEquals("Proveedor dos", model.state.supplierName)
        assertEquals(listOf(1L), model.state.lines.map { it.productId })
    }

    @Test
    fun `empty fields are marked only after trying to register`() = runTest(dispatcher) {
        val model = viewModel()
        model.act(this, NewPurchaseAction.AddProduct(1L))
        assertNull(model.line().quantityError)

        model.act(this, NewPurchaseAction.Register)

        assertTrue(requests.isEmpty())
        assertEquals(PurchaseFieldError.REQUIRED, model.line().quantityError)
        assertEquals(PurchaseFieldError.REQUIRED, model.line().amountError)
    }

    @Test
    fun `wrong values are marked while typing`() = runTest(dispatcher) {
        val model = viewModel()
        model.act(this, NewPurchaseAction.AddProduct(1L))
        val key = model.line().key
        model.act(this, NewPurchaseAction.QuantityChange(key, "1,5"))
        assertEquals(PurchaseFieldError.FRACTION_NOT_ALLOWED, model.line().quantityError)
        model.act(this, NewPurchaseAction.QuantityChange(key, "0"))
        assertEquals(PurchaseFieldError.ZERO_QUANTITY, model.line().quantityError)
        model.act(this, NewPurchaseAction.QuantityChange(key, "abc"))
        assertEquals(PurchaseFieldError.NOT_A_NUMBER, model.line().quantityError)
        model.act(this, NewPurchaseAction.AmountChange(key, "-5"))
        assertEquals(PurchaseFieldError.NOT_A_NUMBER, model.line().amountError)
        model.act(this, NewPurchaseAction.AmountChange(key, "9999999999999"))
        assertEquals(PurchaseFieldError.TOO_HIGH, model.line().amountError)
    }

    @Test
    fun `a stock over its cap marks the quantity`() = runTest(dispatcher) {
        catalog.value =
            catalogOf(products = listOf(soap.copy(onHand = QuantityMilli(7_999_999_000L))))
        val model = viewModel()
        model.act(this, NewPurchaseAction.AddProduct(1L))
        val key = model.line().key
        model.act(this, NewPurchaseAction.QuantityChange(key, "2"))
        model.act(this, NewPurchaseAction.AmountChange(key, "1"))

        assertEquals(PurchaseFieldError.STOCK_OVER_LIMIT, model.line().quantityError)
        assertNull(model.state.total)
    }

    @Test
    fun `the tax of the business enters the total`() = runTest(dispatcher) {
        catalog.value =
            catalogOf(taxes = TaxSettings(TaxMode.ADDED, 20L, purchaseTaxRecoverable = true))
        val model = viewModel()
        model.act(this, NewPurchaseAction.AddProduct(1L))
        val key = model.line().key
        model.act(this, NewPurchaseAction.QuantityChange(key, "10"))
        model.act(this, NewPurchaseAction.AmountChange(key, "1000"))

        // 10000 netos con 21 % sumado: se pagan 12100, y el costo recupera el impuesto.
        assertEquals(Money(12_100L), model.state.total)
        assertEquals(Money(1_000L), model.line().costAfter)
    }

    @Test
    fun `a failure keeps everything written and retries the same purchase`() = runTest(dispatcher) {
        result = RegisterPurchaseResult.StorageFailed(StorageFailure.DISK_FULL)
        val model = viewModel()
        model.act(this, NewPurchaseAction.AddProduct(1L))
        val key = model.line().key
        model.act(this, NewPurchaseAction.QuantityChange(key, "2"))
        model.act(this, NewPurchaseAction.AmountChange(key, "100"))
        model.act(this, NewPurchaseAction.Register)

        assertEquals(PurchaseNotice.RegisterFailed(outOfSpace = true), model.state.notice)
        assertEquals("2", model.line().quantityText)

        result = RegisterPurchaseResult.Registered(4L, emptyList())
        model.act(this, NewPurchaseAction.Retry)
        assertEquals(2, requests.size)
        assertEquals(requests[0], requests[1])
        assertEquals(4L, model.state.registered?.purchaseId)
    }

    @Test
    fun `each refusal of the register shows where to fix it`() = runTest(dispatcher) {
        val model = viewModel()
        model.act(this, NewPurchaseAction.AddProduct(1L))
        val key = model.line().key
        model.act(this, NewPurchaseAction.QuantityChange(key, "2"))
        model.act(this, NewPurchaseAction.AmountChange(key, "100"))
        val cases = mapOf(
            RegisterPurchaseResult.CurrencyNotConfirmed to PurchaseNotice.CurrencyUnconfirmed,
            RegisterPurchaseResult.PurchaseTotalTooLarge to PurchaseNotice.TotalTooLarge,
            RegisterPurchaseResult.TooManyLines to PurchaseNotice.TooManyLines,
            RegisterPurchaseResult.EmptyPurchase to PurchaseNotice.TooManyLines,
            RegisterPurchaseResult.SupplierArchived to PurchaseNotice.SupplierUnavailable,
            RegisterPurchaseResult.SupplierNotFound to PurchaseNotice.SupplierUnavailable,
            RegisterPurchaseResult.FutureDate to PurchaseNotice.RegisterFailed(false),
            RegisterPurchaseResult.InvoiceReferenceTooLong to PurchaseNotice.RegisterFailed(false),
            RegisterPurchaseResult.UnknownTimeZone("X/Y") to PurchaseNotice.RegisterFailed(false),
            RegisterPurchaseResult.StorageFailed(StorageFailure.IO_ERROR) to
                PurchaseNotice.RegisterFailed(false),
        )
        for ((refusal, notice) in cases) {
            result = refusal
            model.act(this, NewPurchaseAction.Register)
            assertEquals(refusal.toString(), notice, model.state.notice)
            model.act(this, NewPurchaseAction.DismissNotice)
        }
        result = RegisterPurchaseResult.LineRejected(0, PurchaseLineRejection.STOCK_ABOVE_LIMIT)
        model.act(this, NewPurchaseAction.Register)
        assertEquals(PurchaseFieldError.STOCK_OVER_LIMIT, model.line().quantityError)
        // Editar la línea deja atrás el rechazo.
        model.act(this, NewPurchaseAction.QuantityChange(key, "1"))
        assertNull(model.line().quantityError)
    }

    @Test
    fun `leaving with lines asks first, and without them it just closes`() = runTest(dispatcher) {
        val model = viewModel()
        model.act(this, NewPurchaseAction.Close)
        assertTrue(model.state.closed)

        val second = viewModel(state = SavedStateHandle())
        second.act(this, NewPurchaseAction.AddProduct(1L))
        second.act(this, NewPurchaseAction.Close)
        assertEquals(PurchaseDialog.Discard, second.state.dialog)
        second.act(this, NewPurchaseAction.DismissDialog)
        assertNull(second.state.dialog)
        second.act(this, NewPurchaseAction.Close)
        second.act(this, NewPurchaseAction.ConfirmLeave)
        assertTrue(second.state.closed)
    }

    @Test
    fun `the purchase survives the process being closed`() = runTest(dispatcher) {
        val model = viewModel()
        model.act(this, NewPurchaseAction.AddProduct(2L))
        val key = model.line().key
        model.act(this, NewPurchaseAction.AmountModeChange(key, byTotal = true))
        model.act(this, NewPurchaseAction.QuantityChange(key, "1,250"))
        model.act(this, NewPurchaseAction.AmountChange(key, "9000"))
        model.act(this, NewPurchaseAction.ReferenceChange("F-0042"))
        model.act(this, NewPurchaseAction.DateChange(today.plusDays(-2)))

        val restored = viewModel(state = savedState)

        assertEquals(model.state.lines, restored.state.lines)
        assertEquals("F-0042", restored.state.reference)
        assertEquals(today.plusDays(-2), restored.state.date)
        assertEquals("Proveedor uno", restored.state.supplierName)
    }

    @Test
    fun `an archived product is offered to reactivate before adding it`() = runTest(dispatcher) {
        val model = viewModel()
        model.act(this, NewPurchaseAction.OpenProducts)
        model.act(this, NewPurchaseAction.QueryChange("vel"))
        val sheet = model.state.sheet as PurchaseSheet.Products
        assertEquals(listOf(ProductOption(3L, "Vela", archived = true)), sheet.options)

        model.act(this, NewPurchaseAction.AddProduct(3L))
        assertEquals(PurchaseDialog.Reactivate(3L, "Vela"), model.state.dialog)
        model.act(this, NewPurchaseAction.ConfirmReactivate)
        catalog.value = catalogOf(products = listOf(soap, flour, archived.copy(archivedAt = null)))
        advanceUntilIdle()

        assertEquals(listOf(3L), model.state.lines.map { it.productId })
    }

    @Test
    fun `a product created from the search joins the purchase`() = runTest(dispatcher) {
        val model = viewModel()
        model.act(this, NewPurchaseAction.OpenProducts)
        model.act(this, NewPurchaseAction.CreateProduct("Nuevo"))
        assertNull(model.state.sheet)

        catalog.value = catalogOf(products = listOf(soap, flour, product(10L, "Nuevo")))
        advanceUntilIdle()

        assertEquals(listOf(10L), model.state.lines.map { it.productId })
    }

    @Test
    fun `the supplier sheet offers no supplier and filters by name`() = runTest(dispatcher) {
        val model = viewModel()
        model.act(this, NewPurchaseAction.OpenSuppliers)
        val all = model.state.sheet as PurchaseSheet.Suppliers
        assertEquals(listOf(null, 5L, 6L), all.options.map { it.id })

        model.act(this, NewPurchaseAction.QueryChange("dos"))
        val some = model.state.sheet as PurchaseSheet.Suppliers
        assertEquals(listOf(6L), some.options.map { it.id })

        model.act(this, NewPurchaseAction.CloseSheet)
        assertNull(model.state.sheet)
    }

    @Test
    fun `a catalog that cannot be read offers to retry`() = runTest(dispatcher) {
        var reads = 0
        val model = viewModel(
            source = {
                reads++
                if (reads == 1) flow { error("lectura inventada") } else catalog
            },
        )
        assertTrue(model.state.loadFailed)

        model.act(this, NewPurchaseAction.Retry)
        assertEquals(false, model.state.loadFailed)
    }

    @Test
    fun `removing a line drops it and its total`() = runTest(dispatcher) {
        val model = viewModel()
        model.act(this, NewPurchaseAction.AddProduct(1L))
        val key = model.line().key
        model.act(this, NewPurchaseAction.QuantityChange(key, "1"))
        model.act(this, NewPurchaseAction.AmountChange(key, "100"))

        model.act(this, NewPurchaseAction.RemoveLine(key))

        assertTrue(model.state.lines.isEmpty())
        assertNull(model.state.total)
    }

    @Test
    fun `a box bought by its total shows the cost of each unit and registers the box`() =
        runTest(dispatcher) {
            val boxOf24 = ConversionDetails(box.id, 24_000L, null, null, false, true)
            catalog.value = catalogOf(conversions = mapOf(1L to mapOf(box.id to boxOf24)))
            val model = viewModel()
            model.act(this, NewPurchaseAction.AddProduct(1L))
            val key = model.line().key
            assertEquals(listOf(COUNT_UNIT, box), model.line().units)

            model.act(this, NewPurchaseAction.UnitChange(key, box.id))
            model.act(this, NewPurchaseAction.AmountModeChange(key, byTotal = true))
            model.act(this, NewPurchaseAction.QuantityChange(key, "1,5"))
            assertEquals(PurchaseFieldError.FRACTION_NOT_ALLOWED, model.line().quantityError)
            model.act(this, NewPurchaseAction.QuantityChange(key, "1"))
            model.act(this, NewPurchaseAction.AmountChange(key, "10000"))

            assertEquals(box, model.line().unit)
            // La caja cuesta 10000; con 10 unidades a 1000, el costo pasa a 20000 / 34 = 588.
            assertEquals(Money(10_000L), model.line().derived)
            assertEquals(Money(588L), model.line().costAfter)
            assertEquals(box.id, PurchaseDraftStore.read(savedState).lines.single().unitId)

            model.act(this, NewPurchaseAction.UnitChange(key, null))
            assertEquals(COUNT_UNIT, model.line().unit)
            model.act(this, NewPurchaseAction.UnitChange(key, box.id))

            model.act(this, NewPurchaseAction.Register)
            assertEquals(box.id, requests.single().lines.single().unitId)
        }

    @Test
    fun `a unit that is no longer bought marks the quantity`() = runTest(dispatcher) {
        result = RegisterPurchaseResult.LineRejected(0, PurchaseLineRejection.UNIT_UNAVAILABLE)
        val model = viewModel()
        model.act(this, NewPurchaseAction.AddProduct(1L))
        val key = model.line().key
        model.act(this, NewPurchaseAction.QuantityChange(key, "1"))
        model.act(this, NewPurchaseAction.AmountChange(key, "100"))
        model.act(this, NewPurchaseAction.Register)

        assertEquals(PurchaseFieldError.UNIT_UNAVAILABLE, model.line().quantityError)
    }

    @Test
    fun `a margin left below its target asks to review the price in the summary`() =
        runTest(dispatcher) {
            result = RegisterPurchaseResult.Registered(
                purchaseId = 9L,
                costChanges = listOf(
                    PurchaseCostChange(1L, Money(1_000L), Money(1_200L), marginBelowTarget = true),
                ),
            )
            val model = viewModel()
            model.act(this, NewPurchaseAction.AddProduct(1L))
            val key = model.line().key
            model.act(this, NewPurchaseAction.QuantityChange(key, "10"))
            model.act(this, NewPurchaseAction.AmountChange(key, "1400"))
            model.act(this, NewPurchaseAction.Register)

            assertTrue(requireNotNull(model.state.registered).costs.single().reviewPrice)
            // Revisar el precio lo resuelve la navegación: el resumen sigue ahí.
            model.act(this, NewPurchaseAction.ReviewPrice(1L))
            assertTrue(model.state.registered != null)
        }
}

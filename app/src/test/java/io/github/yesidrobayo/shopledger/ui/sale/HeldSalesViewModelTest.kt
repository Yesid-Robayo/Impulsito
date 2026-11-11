package io.github.yesidrobayo.shopledger.ui.sale

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.sale.HeldSale
import io.github.yesidrobayo.shopledger.domain.model.sale.HeldSaleLine
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleCatalog
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxSettings
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.HeldSaleChangeResult
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
 * La lista de ventas en espera sin base: el total estimado con los precios vigentes, la venta que
 * se edita fuera de la lista, retomar con o sin otra venta en edición, descartar y renombrar.
 * Productos inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HeldSalesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val rice = product(1L, "Arroz", salePrice = 4_200L)
    private val oil = product(2L, "Aceite", salePrice = 12_500L)
    private val held = MutableStateFlow(
        listOf(
            heldSale(
                7L,
                "Mesa 4",
                HeldSaleLine(rice.id, COUNT_UNIT.id, QuantityMilli(2_000L), null, Money.ZERO),
                HeldSaleLine(
                    oil.id,
                    COUNT_UNIT.id,
                    QuantityMilli(1_000L),
                    Money(12_000L),
                    Money(500L),
                ),
                // Un producto que ya no está disponible no cuenta en el total.
                HeldSaleLine(9L, COUNT_UNIT.id, QuantityMilli(1_000L), null, Money.ZERO),
            ),
            heldSale(
                8L,
                null,
                HeldSaleLine(rice.id, COUNT_UNIT.id, QuantityMilli(1_000L), null, Money.ZERO),
            ),
        ),
    )
    private var inProgress = false
    private var editing: Long? = null
    private val discarded = mutableListOf<Long>()
    private val renamed = mutableListOf<Pair<Long, String?>>()
    private var change: HeldSaleChangeResult = HeldSaleChangeResult.Changed

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun heldSale(id: Long, label: String?, vararg lines: HeldSaleLine) =
        HeldSale(id, label, lines.toList(), CREATED, CREATED)

    private val catalog = SaleCatalog(
        products = listOf(rice, oil),
        frequent = emptyList(),
        archived = emptyList(),
        units = mapOf(COUNT_UNIT.id to COUNT_UNIT),
        methods = emptyList(),
        taxes = TaxSettings(TaxMode.NONE, null, purchaseTaxRecoverable = false),
        taxRates = emptyMap(),
        today = BusinessDate(LocalDate(2026, 11, 2)),
        weekStartDay = DayOfWeek.MONDAY,
    )

    private fun TestScope.viewModel(
        sales: () -> Flow<List<HeldSale>> = { held },
    ): HeldSalesViewModel {
        val model = HeldSalesViewModel(
            heldSales = sales,
            catalog = { MutableStateFlow(catalog) },
            calendar = {
                MutableStateFlow<BusinessCalendarResult>(
                    BusinessCalendar.of("Etc/GMT+5", DayOfWeek.MONDAY),
                )
            },
            rename = { id, label ->
                renamed += id to label
                change
            },
            discard = {
                discarded += it
                change
            },
            saleInProgress = { inProgress },
            editingHeldSaleId = { editing },
            computation = dispatcher,
        )
        backgroundScope.launch { model.uiState.collect {} }
        advanceUntilIdle()
        return model
    }

    private fun HeldSalesViewModel.act(scope: TestScope, action: HeldSalesAction) {
        onAction(action)
        scope.advanceUntilIdle()
    }

    private val HeldSalesViewModel.state: HeldSalesUiState get() = uiState.value

    private val HeldSalesViewModel.rows: List<HeldSaleRow>
        get() = (state.content as HeldSalesContent.Sales).rows

    @Test
    fun `each row has its estimated total with today's prices`() = runTest(dispatcher) {
        val model = viewModel()

        val first = model.rows.first()
        assertEquals("Mesa 4", first.label)
        // 2 × 4.200 vigente, más 12.000 escrito a mano menos 500.
        assertEquals(Money(19_900L), first.estimatedTotal)
        assertEquals(3L, first.itemCount)
        assertNull(model.rows[1].label)
        assertTrue((model.state.content as HeldSalesContent.Sales).zone != null)
    }

    @Test
    fun `the sale being edited is not listed`() = runTest(dispatcher) {
        editing = 7L

        val model = viewModel()

        assertEquals(listOf(8L), model.rows.map { it.id })
    }

    @Test
    fun `no held sales shows the empty state, and a read failure the error`() =
        runTest(dispatcher) {
            held.value = emptyList()
            assertEquals(HeldSalesContent.Empty, viewModel().state.content)

            val failing = viewModel(sales = { flow { error("Falla inventada") } })

            assertEquals(HeldSalesContent.ReadError, failing.state.content)
        }

    @Test
    fun `without a sale in progress a row resumes at once`() = runTest(dispatcher) {
        val model = viewModel()

        model.act(this, HeldSalesAction.Resume(8L))

        assertEquals(8L, model.state.resume)
        assertNull(model.state.dialog)
    }

    @Test
    fun `with a sale in progress it asks before swapping`() = runTest(dispatcher) {
        inProgress = true
        val model = viewModel()

        model.act(this, HeldSalesAction.Resume(8L))
        assertEquals(HeldSalesDialog.Swap(8L), model.state.dialog)
        assertNull(model.state.resume)

        model.act(this, HeldSalesAction.ConfirmSwap)

        assertEquals(8L, model.state.resume)
        assertNull(model.state.dialog)
    }

    @Test
    fun `discarding asks first and then deletes only the held sale`() = runTest(dispatcher) {
        val model = viewModel()

        model.act(this, HeldSalesAction.AskDiscard(7L))
        assertTrue(discarded.isEmpty())
        model.act(this, HeldSalesAction.ConfirmDiscard)

        assertEquals(listOf(7L), discarded)
        assertNull(model.state.notice)
    }

    @Test
    fun `renaming starts with the name it has and marks a long one`() = runTest(dispatcher) {
        val model = viewModel()

        model.act(this, HeldSalesAction.OpenRename(7L))
        assertEquals("Mesa 4", model.state.rename?.text)
        model.act(this, HeldSalesAction.RenameChange("x".repeat(41)))
        model.act(this, HeldSalesAction.ConfirmRename)
        assertTrue(renamed.isEmpty())

        model.act(this, HeldSalesAction.RenameChange("Mesa 7"))
        model.act(this, HeldSalesAction.ConfirmRename)

        assertEquals(listOf(7L to "Mesa 7"), renamed)
        assertNull(model.state.rename)
    }

    @Test
    fun `a change that fails is said, and the list stays as it was`() = runTest(dispatcher) {
        change = HeldSaleChangeResult.StorageFailed(StorageFailure.DISK_FULL)
        val model = viewModel()

        model.act(this, HeldSalesAction.AskDiscard(7L))
        model.act(this, HeldSalesAction.ConfirmDiscard)

        assertEquals(HeldSalesNotice.CHANGE_FAILED, model.state.notice)
        model.act(this, HeldSalesAction.NoticeShown)
        assertNull(model.state.notice)
    }
}

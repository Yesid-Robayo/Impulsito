package io.github.yesidrobayo.shopledger.ui.sale

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.Voiding
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.sale.LineStockEffect
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSale
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSaleLine
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSalePayment
import io.github.yesidrobayo.shopledger.domain.model.sale.Sale
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleDetail
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLine
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePayment
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.VoidSaleResult
import io.github.yesidrobayo.shopledger.ui.product.COUNT_UNIT
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * El detalle de una venta sin base: muestra líneas, pagos y lo que dejó; anular con motivo y nota
 * la deja anulada sin reabrir; una falla la deja activa y se reintenta. Datos inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SaleDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val date = BusinessDate(LocalDate(2026, 11, 2))

    /** 2026-11-02 15:00 UTC: lunes 2026-11-02 10:00 en `Etc/GMT+5`. */
    private val at = Instant.fromEpochMilliseconds(1_793_631_600_000L)
    private val calendar: BusinessCalendarResult = BusinessCalendar.of(
        "Etc/GMT+5",
        DayOfWeek.MONDAY,
    )
    private val detail = MutableStateFlow<SaleDetail?>(detailOf(voiding = null))
    private val voids = mutableListOf<Triple<Long, VoidReason, String?>>()
    private var voidResult: VoidSaleResult = VoidSaleResult.Voided

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun detailOf(voiding: Voiding?): SaleDetail {
        val sale = Sale(
            id = 7L,
            content = NewSale(
                netTotal = Money(31_000L),
                taxTotal = Money.ZERO,
                total = Money(31_000L),
                totalCost = Money(17_817L),
                totalReplacementCost = Money(17_817L),
                orderAdjustment = Money.ZERO,
                tipAmount = Money.ZERO,
                cashRoundingAmount = Money.ZERO,
                amountDue = Money(31_000L),
                customerId = null,
                priceListId = 1L,
                locationId = 1L,
                cashSessionId = null,
                quoteId = null,
                orderId = null,
                note = "Mesa 4",
                occurredAt = at,
                businessDate = date,
                createdAt = at,
            ),
            voiding = voiding,
        )
        val line = SaleLine(
            id = 70L,
            saleId = 7L,
            content = NewSaleLine(
                productId = 3L,
                unitId = COUNT_UNIT.id,
                unitFactor = QuantityMilli(1_000L),
                enteredQuantity = QuantityMilli(1_000L),
                quantity = QuantityMilli(1_000L),
                unitPrice = Money(20_000L),
                unitCost = Money(11_111L),
                lineDiscount = Money(2_000L),
                orderAdjustmentShare = Money.ZERO,
                netAmount = Money(18_000L),
                taxRate = BasisPoints.ZERO,
                taxNameSnapshot = null,
                taxAmount = Money.ZERO,
                lineTotal = Money(18_000L),
                lineCost = Money(11_111L),
                unitReplacementCost = Money(11_111L),
                lineReplacementCost = Money(11_111L),
                productNameSnapshot = "Crema",
                stockEffect = LineStockEffect.DIRECT,
                parentLineId = null,
                priceListId = 1L,
            ),
        )
        val payment = SalePayment(
            id = 700L,
            saleId = 7L,
            content = NewSalePayment(
                paymentMethodId = 2L,
                methodNameSnapshot = "Tarjeta",
                methodKind = PaymentMethodKind.CARD,
                amount = Money(31_000L),
                feeAmount = Money(899L),
                settlementDate = date.plusDays(2),
                moneyAccountId = null,
                createdAt = at,
            ),
        )
        return SaleDetail(sale, listOf(line), listOf(payment), archivedProductIds = setOf(3L))
    }

    private fun TestScope.viewModel(failRead: Boolean = false): SaleDetailViewModel {
        val model = SaleDetailViewModel(
            saleId = 7L,
            observeDetail = { if (failRead) flow { error("Falla inventada") } else detail },
            observeCalendar = { MutableStateFlow(calendar) },
            observeUnits = { MutableStateFlow<List<MeasureUnit>>(listOf(COUNT_UNIT)) },
            voidSale = { id, reason, note ->
                voids += Triple(id, reason, note)
                if (voidResult == VoidSaleResult.Voided) {
                    detail.value = detailOf(Voiding(at, reason, note))
                }
                voidResult
            },
            computation = dispatcher,
        )
        backgroundScope.launch { model.uiState.collect {} }
        advanceUntilIdle()
        return model
    }

    private fun SaleDetailViewModel.act(scope: TestScope, action: SaleDetailAction) {
        onAction(action)
        scope.advanceUntilIdle()
    }

    private val SaleDetailViewModel.ready: SaleDetailUiState.Ready
        get() = uiState.value as SaleDetailUiState.Ready

    @Test
    fun `the detail shows lines, payments and what the sale left`() = runTest(dispatcher) {
        val sale = viewModel().ready.sale

        val line = sale.lines.single()
        assertEquals("Crema", line.name)
        assertEquals(Money(2_000L), line.discount)
        assertTrue(line.archived)
        assertEquals(date.plusDays(2), sale.payments.single().settlesOn)
        assertEquals(Money(13_183L), sale.results.profit)
        assertEquals(Margin.Defined(BasisPoints(4_253L)), sale.results.margin)
        assertEquals(Money(30_101L), sale.results.netReceived)
        assertEquals("Mesa 4", sale.note)
        assertTrue(sale.canVoid)
    }

    @Test
    fun `each payment shows its fee and net, and the charges explain the total`() =
        runTest(dispatcher) {
            val base = detailOf(voiding = null)
            val line = base.lines.single()
            fun taxed(id: Long, name: String?, tax: Long) = line.copy(
                id = id,
                content = line.content.copy(taxNameSnapshot = name, taxAmount = Money(tax)),
            )
            val card = base.payments.single()
            // El pago del ejemplo de comisión: 10.000 con 314 de comisión dejan 9.686.
            val cardPayment = card.copy(
                content = card.content.copy(amount = Money(10_000L), feeAmount = Money(314L)),
            )
            val cashPayment = card.copy(
                id = 701L,
                content = card.content.copy(
                    methodNameSnapshot = "Efectivo",
                    methodKind = PaymentMethodKind.CASH,
                    amount = Money(2_280L),
                    feeAmount = Money.ZERO,
                    settlementDate = date,
                ),
            )
            detail.value = base.copy(
                sale = base.sale.copy(
                    content = base.sale.content.copy(
                        orderAdjustment = Money(-500L),
                        tipAmount = Money(1_000L),
                        cashRoundingAmount = Money(-100L),
                    ),
                ),
                lines = listOf(
                    taxed(70L, "Tasa general", 1_900L),
                    taxed(71L, null, 300L),
                    taxed(72L, "Tasa general", 950L),
                    taxed(73L, "Tasa reducida", 0L),
                ),
                payments = listOf(cardPayment, cashPayment),
            )

            val sale = viewModel().ready.sale

            val (first, second) = sale.payments
            assertEquals(Money(314L), first.fee)
            assertEquals(Money(9_686L), first.netReceived)
            assertTrue(first.hasFee)
            assertFalse(second.hasFee)
            assertEquals(Money(2_280L), second.netReceived)
            assertNull(second.settlesOn)
            assertEquals(
                listOf(
                    SaleDetailTax("Tasa general", Money(2_850L)),
                    SaleDetailTax(null, Money(300L)),
                ),
                sale.charges.taxes,
            )
            assertEquals(Money(-500L), sale.charges.orderAdjustment)
            assertEquals(Money(1_000L), sale.charges.tip)
            assertEquals(Money(-100L), sale.charges.cashRounding)
            assertFalse(sale.charges.isEmpty)
        }

    @Test
    fun `a sale without tax, adjustment, tip or rounding has no charges`() = runTest(dispatcher) {
        assertTrue(viewModel().ready.sale.charges.isEmpty)
    }

    @Test
    fun `voiding with a reason and a note shows the sale voided without reopening`() =
        runTest(dispatcher) {
            val model = viewModel()
            model.act(this, SaleDetailAction.OpenVoid)
            model.act(this, SaleDetailAction.ReasonChange(VoidReason.CUSTOMER_RETURN))
            model.act(this, SaleDetailAction.NoteChange("  Devolvió todo  "))

            model.act(this, SaleDetailAction.ConfirmVoid)

            assertEquals(listOf(Triple(7L, VoidReason.CUSTOMER_RETURN, "Devolvió todo")), voids)
            val ready = model.ready
            assertTrue(ready.voided)
            assertNull(ready.sheet)
            assertFalse(ready.sale.canVoid)
            assertEquals(VoidReason.CUSTOMER_RETURN, ready.sale.voiding?.reason)
            assertEquals(date, ready.sale.voiding?.date)
            model.act(this, SaleDetailAction.VoidedShown)
            assertFalse(model.ready.voided)
        }

    @Test
    fun `a failed void keeps the sale active and can be retried`() = runTest(dispatcher) {
        voidResult = VoidSaleResult.StorageFailed(StorageFailure.DISK_FULL)
        val model = viewModel()
        model.act(this, SaleDetailAction.OpenVoid)

        model.act(this, SaleDetailAction.ConfirmVoid)
        assertEquals(VoidStatus.FAILED, model.ready.status)
        assertTrue(model.ready.sale.canVoid)

        voidResult = VoidSaleResult.Voided
        model.act(this, SaleDetailAction.ConfirmVoid)
        assertFalse(model.ready.sale.canVoid)
    }

    @Test
    fun `a note too long is not sent, and closing the sheet keeps the sale`() =
        runTest(dispatcher) {
            val model = viewModel()
            model.act(this, SaleDetailAction.OpenVoid)
            model.act(this, SaleDetailAction.NoteChange("x".repeat(201)))

            model.act(this, SaleDetailAction.ConfirmVoid)
            assertTrue(voids.isEmpty())
            model.act(this, SaleDetailAction.CloseSheet)
            assertNull(model.ready.sheet)
            model.act(this, SaleDetailAction.Back)
            assertTrue(model.ready.sale.canVoid)
        }

    @Test
    fun `a missing sale and a read failure have their own states`() = runTest(dispatcher) {
        detail.value = null
        assertEquals(SaleDetailUiState.NotFound, viewModel().uiState.value)

        val failing = viewModel(failRead = true)
        assertEquals(SaleDetailUiState.ReadError, failing.uiState.value)
        failing.act(this, SaleDetailAction.Retry)
        assertEquals(SaleDetailUiState.ReadError, failing.uiState.value)
    }

    @Test
    fun `a sale already voided elsewhere counts as voided`() = runTest(dispatcher) {
        voidResult = VoidSaleResult.AlreadyVoided
        val model = viewModel()
        model.act(this, SaleDetailAction.OpenVoid)

        model.act(this, SaleDetailAction.ConfirmVoid)

        assertTrue(model.ready.voided)
    }
}

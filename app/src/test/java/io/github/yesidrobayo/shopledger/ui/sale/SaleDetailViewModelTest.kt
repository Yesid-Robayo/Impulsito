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
import io.github.yesidrobayo.shopledger.domain.model.returns.LineAmounts
import io.github.yesidrobayo.shopledger.domain.model.returns.NewSaleReturn
import io.github.yesidrobayo.shopledger.domain.model.returns.NewSaleReturnLine
import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnResolution
import io.github.yesidrobayo.shopledger.domain.model.returns.SaleReturn
import io.github.yesidrobayo.shopledger.domain.model.returns.SaleReturnLine
import io.github.yesidrobayo.shopledger.domain.model.sale.ForeignTender
import io.github.yesidrobayo.shopledger.domain.model.sale.LineStockEffect
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSale
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSaleLine
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSalePayment
import io.github.yesidrobayo.shopledger.domain.model.sale.Sale
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleDetail
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLine
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePayment
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.returns.VoidSaleReturnResult
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

    private val returnLines = mutableListOf<SaleReturnLine>()
    private val returnVoids = mutableListOf<Triple<Long, VoidReason, String?>>()
    private var returnVoidResult: VoidSaleReturnResult = VoidSaleReturnResult.Voided
    private var replacementActive = true

    /** La venta con una devolución activa de 1 unidad, que es parte de un cambio si [exchange]. */
    private fun withReturn(exchange: Boolean = false): SaleDetail {
        val base = detailOf(voiding = null)
        val header = SaleReturn(
            id = 41L,
            content = NewSaleReturn(
                saleId = 7L,
                resolution = if (exchange) ReturnResolution.EXCHANGE else ReturnResolution.REFUND,
                netRefund = Money(18_000L),
                taxRefund = Money.ZERO,
                totalRefund = Money(18_000L),
                costReturned = Money(11_111L),
                moneyAccountId = 1L,
                replacementSaleId = if (exchange) 9L else null,
                note = null,
                occurredAt = at,
                businessDate = date,
                createdAt = at,
            ),
            voiding = null,
        )
        returnLines += SaleReturnLine(
            id = 410L,
            saleReturnId = 41L,
            content = NewSaleReturnLine(
                saleLineId = 70L,
                quantity = QuantityMilli(1_000L),
                netAmount = Money(18_000L),
                taxAmount = Money.ZERO,
                lineTotal = Money(18_000L),
                lineCost = Money(11_111L),
                restock = true,
            ),
        )
        return base.copy(
            returns = listOf(header),
            returned = mapOf(
                70L to LineAmounts(
                    QuantityMilli(1_000L),
                    Money(18_000L),
                    Money.ZERO,
                    Money(11_111L),
                ),
            ),
        )
    }

    private fun TestScope.viewModel(
        failRead: Boolean = false,
        businessName: String? = null,
    ): SaleDetailViewModel {
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
            observeBusinessName = { MutableStateFlow(businessName) },
            loadReturnLines = { id -> returnLines.filter { it.saleReturnId == id } },
            voidReturn = { id, reason, note ->
                returnVoids += Triple(id, reason, note)
                if (returnVoidResult == VoidSaleReturnResult.Voided) {
                    // Como la base: la devolución queda anulada y deja de contar en lo devuelto.
                    val current = requireNotNull(detail.value)
                    detail.value = current.copy(
                        returns = current.returns.map {
                            if (it.id == id) it.copy(voiding = Voiding(at, reason, note)) else it
                        },
                        returned = emptyMap(),
                    )
                }
                returnVoidResult
            },
            isSaleActive = { replacementActive },
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
    fun `a payment received in another currency shows the currency, the amount and the rate`() =
        runTest(dispatcher) {
            val base = detailOf(voiding = null)
            val card = base.payments.single()
            val tender = ForeignTender(
                currency = requireNotNull(CurrencyCode.of("XTS")),
                decimals = 2,
                amountMinor = 2_000L,
                rateMicro = 4_150_500_000L,
            )
            detail.value = base.copy(
                payments = listOf(card.copy(content = card.content.copy(foreignTender = tender))),
            )

            val payment = viewModel().ready.sale.payments.single()

            // El monto sigue en la moneda del negocio; lo entregado va aparte.
            assertEquals(Money(31_000L), payment.amount)
            assertEquals(tender, payment.foreign)
        }

    @Test
    fun `a payment in the business currency has no other currency`() = runTest(dispatcher) {
        assertNull(viewModel().ready.sale.payments.single().foreign)
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
    fun `a sale with an active return is not offered to void or correct`() = runTest(dispatcher) {
        val returned = SaleReturn(
            id = 3L,
            content = NewSaleReturn(
                saleId = 7L,
                resolution = ReturnResolution.REFUND,
                netRefund = Money(1_000L),
                taxRefund = Money.ZERO,
                totalRefund = Money(1_000L),
                costReturned = Money.ZERO,
                moneyAccountId = null,
                replacementSaleId = null,
                note = null,
                occurredAt = at,
                businessDate = date,
                createdAt = at,
            ),
            voiding = null,
        )
        detail.value = detailOf(voiding = null).copy(returns = listOf(returned))
        val model = viewModel()

        assertTrue(model.ready.sale.hasActiveReturns)
        assertFalse(model.ready.sale.canVoid)
        model.act(this, SaleDetailAction.OpenVoid)
        assertNull(model.ready.sheet)

        // Anulada la devolución, se vuelve a ofrecer.
        detail.value = detailOf(voiding = null).copy(
            returns = listOf(returned.copy(voiding = Voiding(at, VoidReason.ENTRY_ERROR, null))),
        )
        advanceUntilIdle()
        assertTrue(model.ready.sale.canVoid)
    }

    @Test
    fun `the detail shows what was returned, what is left and each return`() = runTest(dispatcher) {
        val base = detailOf(voiding = null)
        val line = base.lines.single()
        // Tres unidades vendidas, una ya devuelta: queda dos, y se ofrece devolver el resto.
        val sold = line.copy(
            content = line.content.copy(
                enteredQuantity = QuantityMilli(3_000L),
                quantity = QuantityMilli(3_000L),
            ),
        )
        val header = SaleReturn(
            id = 41L,
            content = NewSaleReturn(
                saleId = 7L,
                resolution = ReturnResolution.STORE_CREDIT,
                netRefund = Money(6_000L),
                taxRefund = Money.ZERO,
                totalRefund = Money(6_000L),
                costReturned = Money(3_704L),
                moneyAccountId = null,
                replacementSaleId = null,
                note = "Talla equivocada",
                occurredAt = at,
                businessDate = date,
                createdAt = at,
            ),
            voiding = null,
        )
        returnLines += SaleReturnLine(
            id = 410L,
            saleReturnId = 41L,
            content = NewSaleReturnLine(
                saleLineId = 70L,
                quantity = QuantityMilli(1_000L),
                netAmount = Money(6_000L),
                taxAmount = Money.ZERO,
                lineTotal = Money(6_000L),
                lineCost = Money(3_704L),
                restock = false,
            ),
        )
        detail.value = base.copy(
            lines = listOf(sold),
            returns = listOf(header),
            returned = mapOf(
                70L to LineAmounts(
                    QuantityMilli(1_000L),
                    Money(6_000L),
                    Money.ZERO,
                    Money(3_704L),
                ),
            ),
        )
        val model = viewModel()

        val shown = model.ready.sale
        assertEquals(QuantityMilli(1_000L), shown.lines.single().returned)
        assertEquals(QuantityMilli(2_000L), shown.lines.single().left)
        assertTrue(shown.canReturn)
        val row = shown.returns.single()
        assertEquals(Money(6_000L), row.total)
        assertEquals(ReturnResolution.STORE_CREDIT, row.resolution)
        assertFalse(row.voided)

        model.act(this, SaleDetailAction.OpenReturnDetail(41L))
        val sheet = requireNotNull(model.ready.returnSheet)
        assertEquals("Talla equivocada", sheet.row.note)
        val returnedLine = requireNotNull(sheet.lines).single()
        assertEquals("Crema", returnedLine.name)
        assertEquals(QuantityMilli(1_000L), returnedLine.quantity)
        assertFalse(returnedLine.restock)

        model.act(this, SaleDetailAction.CloseReturnDetail)
        assertNull(model.ready.returnSheet)
    }

    @Test
    fun `voiding a return asks the reason and gives the sale back its void`() =
        runTest(dispatcher) {
            detail.value = withReturn()
            val model = viewModel()
            assertFalse(model.ready.sale.canVoid)
            model.act(this, SaleDetailAction.OpenReturnDetail(41L))
            assertTrue(requireNotNull(model.ready.returnSheet).canVoid)
            assertNull(model.ready.returnSheet?.lockedBySale)

            model.act(this, SaleDetailAction.OpenReturnVoid)
            assertEquals(VoidSheet(), model.ready.returnSheet?.void)
            model.act(this, SaleDetailAction.ReturnVoidReasonChange(VoidReason.OTHER))
            model.act(this, SaleDetailAction.ReturnVoidNoteChange("  Otro cliente  "))
            model.act(this, SaleDetailAction.ConfirmReturnVoid)

            assertEquals(listOf(Triple(41L, VoidReason.OTHER, "Otro cliente")), returnVoids)
            assertNull(model.ready.returnSheet)
            assertTrue(model.ready.returnVoided)
            val shown = model.ready.sale
            assertTrue(shown.returns.single().voided)
            assertEquals(QuantityMilli.ZERO, shown.lines.single().returned)
            assertTrue(shown.canVoid)
            model.act(this, SaleDetailAction.ReturnVoidedShown)
            assertFalse(model.ready.returnVoided)
        }

    @Test
    fun `a failed return void keeps it active and can be retried`() = runTest(dispatcher) {
        detail.value = withReturn()
        returnVoidResult = VoidSaleReturnResult.StorageFailed(StorageFailure.IO_ERROR)
        val model = viewModel()
        model.act(this, SaleDetailAction.OpenReturnDetail(41L))
        model.act(this, SaleDetailAction.OpenReturnVoid)

        model.act(this, SaleDetailAction.ConfirmReturnVoid)

        val sheet = requireNotNull(model.ready.returnSheet)
        assertEquals(VoidStatus.FAILED, sheet.voidStatus)
        assertFalse(sheet.row.voided)
        assertFalse(model.ready.returnVoided)
        // Cambiar la nota limpia la falla; reintentar vuelve a llamar.
        model.act(this, SaleDetailAction.ReturnVoidNoteChange("Error"))
        assertEquals(VoidStatus.IDLE, model.ready.returnSheet?.voidStatus)
        returnVoidResult = VoidSaleReturnResult.Voided
        model.act(this, SaleDetailAction.ConfirmReturnVoid)
        assertEquals(2, returnVoids.size)
        assertNull(model.ready.returnSheet)
    }

    @Test
    fun `a note too long is not sent, and a return voided elsewhere just closes`() =
        runTest(dispatcher) {
            detail.value = withReturn()
            returnVoidResult = VoidSaleReturnResult.AlreadyVoided
            val model = viewModel()
            model.act(this, SaleDetailAction.OpenReturnDetail(41L))
            model.act(this, SaleDetailAction.OpenReturnVoid)
            model.act(this, SaleDetailAction.ReturnVoidNoteChange("n".repeat(201)))

            model.act(this, SaleDetailAction.ConfirmReturnVoid)
            assertTrue(returnVoids.isEmpty())

            model.act(this, SaleDetailAction.ReturnVoidNoteChange(""))
            model.act(this, SaleDetailAction.ConfirmReturnVoid)
            assertEquals(listOf(Triple(41L, VoidReason.ENTRY_ERROR, null)), returnVoids)
            assertNull(model.ready.returnSheet)
            assertFalse(model.ready.returnVoided)
        }

    @Test
    fun `an exchange with its new sale active is not offered to void`() = runTest(dispatcher) {
        detail.value = withReturn(exchange = true)
        val model = viewModel()

        model.act(this, SaleDetailAction.OpenReturnDetail(41L))

        val sheet = requireNotNull(model.ready.returnSheet)
        assertFalse(sheet.canVoid)
        assertEquals(9L, sheet.lockedBySale)
    }

    @Test
    fun `an exchange with its new sale voided is voided like any return`() = runTest(dispatcher) {
        detail.value = withReturn(exchange = true)
        replacementActive = false
        returnVoidResult = VoidSaleReturnResult.PartOfExchange(9L)
        val model = viewModel()
        model.act(this, SaleDetailAction.OpenReturnDetail(41L))
        assertTrue(requireNotNull(model.ready.returnSheet).canVoid)
        model.act(this, SaleDetailAction.OpenReturnVoid)

        // La venta nueva se reactivó por otro camino: la anulación lo dice, y la hoja también.
        model.act(this, SaleDetailAction.ConfirmReturnVoid)

        val sheet = requireNotNull(model.ready.returnSheet)
        assertNull(sheet.void)
        assertEquals(9L, sheet.lockedBySale)
    }

    @Test
    fun `without an open return there is nothing to void`() = runTest(dispatcher) {
        detail.value = withReturn()
        val model = viewModel()

        model.act(this, SaleDetailAction.OpenReturnVoid)
        model.act(this, SaleDetailAction.ReturnVoidReasonChange(VoidReason.OTHER))
        model.act(this, SaleDetailAction.ConfirmReturnVoid)

        assertNull(model.ready.returnSheet)
        assertTrue(returnVoids.isEmpty())
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
    fun `void and correct asks first, voids as an entry error and opens the new sale`() =
        runTest(dispatcher) {
            val model = viewModel()
            model.act(this, SaleDetailAction.OpenCorrect)
            assertTrue(model.ready.correcting)

            model.act(this, SaleDetailAction.ConfirmCorrect)

            assertEquals(listOf(Triple(7L, VoidReason.ENTRY_ERROR, null as String?)), voids)
            assertEquals(7L, model.ready.corrected)
            assertFalse(model.ready.correcting)
            model.act(this, SaleDetailAction.CorrectShown)
            assertNull(model.ready.corrected)
        }

    @Test
    fun `a failed correction keeps the sale active and says so`() = runTest(dispatcher) {
        voidResult = VoidSaleResult.StorageFailed(StorageFailure.DISK_FULL)
        val model = viewModel()
        model.act(this, SaleDetailAction.OpenCorrect)

        model.act(this, SaleDetailAction.ConfirmCorrect)

        assertTrue(model.ready.correctFailed)
        assertNull(model.ready.corrected)
        assertTrue(model.ready.sale.canVoid)
        model.act(this, SaleDetailAction.CorrectShown)
        assertFalse(model.ready.correctFailed)
    }

    @Test
    fun `going back from the question keeps the sale, and confirming without it does nothing`() =
        runTest(dispatcher) {
            val model = viewModel()
            model.act(this, SaleDetailAction.ConfirmCorrect)
            model.act(this, SaleDetailAction.OpenCorrect)
            model.act(this, SaleDetailAction.DismissCorrect)

            assertFalse(model.ready.correcting)
            assertTrue(voids.isEmpty())
        }

    @Test
    fun `the receipt has no cost and goes away once the sale is voided`() = runTest(dispatcher) {
        val model = viewModel(businessName = "Tienda del barrio")
        val receipt = requireNotNull(model.ready.sale.receipt)

        assertEquals("Tienda del barrio", receipt.businessName)
        assertEquals(Money(31_000L), receipt.total)
        assertFalse(receipt.toString().contains("11111"))
        model.act(this, SaleDetailAction.Share)

        model.act(this, SaleDetailAction.OpenVoid)
        model.act(this, SaleDetailAction.ConfirmVoid)
        assertNull(model.ready.sale.receipt)
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

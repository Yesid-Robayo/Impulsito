package io.github.yesidrobayo.shopledger.ui.payable

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.Supplier
import io.github.yesidrobayo.shopledger.domain.model.catalog.SupplierDetails
import io.github.yesidrobayo.shopledger.domain.model.payable.NewSupplierLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.payable.SupplierLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.payable.SupplierLedgerEntryType
import io.github.yesidrobayo.shopledger.domain.model.region.DigitGrouping
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.rule.payable.SupplierDebts
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.customer.PaymentChoices
import io.github.yesidrobayo.shopledger.domain.usecase.payable.PaySupplierResult
import io.github.yesidrobayo.shopledger.domain.usecase.payable.PayableRow
import io.github.yesidrobayo.shopledger.domain.usecase.payable.PayableStatus
import io.github.yesidrobayo.shopledger.domain.usecase.payable.PayablesLoad
import io.github.yesidrobayo.shopledger.domain.usecase.payable.PayablesOverview
import io.github.yesidrobayo.shopledger.domain.usecase.payable.SupplierPaymentRequest
import io.github.yesidrobayo.shopledger.domain.usecase.payable.SupplierStatement
import io.github.yesidrobayo.shopledger.domain.usecase.payable.VoidSupplierPaymentResult
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
 * Las cuentas por pagar con respuestas fijas del dominio: la lista con sus filtros y estados, y el
 * libro de un proveedor con su vencimiento, el pago, lo que deja en vivo y la anulación. Nombres y
 * montos inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PayableViewModelsTest {
    private val dispatcher = StandardTestDispatcher()
    private val operations = FakePayableOperations()
    private val numbers = PayableNumberFormat(NumberSymbols(',', '-', DigitGrouping('.', 3, 3)), 0)

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    // ------------------------------------------------------------------ lista

    @Test
    fun `the list filters by status and keeps the totals of every supplier`() =
        runTest(dispatcher) {
            val model = PayablesViewModel(operations)
            collect(model.uiState)

            val all = model.uiState.value.content as PayablesContent.Ready
            assertEquals(3, all.rows.size)
            assertEquals(Money(9_000), all.overview.owed)

            model.onAction(PayablesAction.FilterChange(PayableFilter.OVERDUE))
            advanceUntilIdle()
            val overdue = model.uiState.value.content as PayablesContent.Ready
            assertEquals(listOf(1L), overdue.rows.map { it.supplier.id })
            // Los totales no cambian con el filtro.
            assertEquals(Money(9_000), overdue.overview.owed)

            model.onAction(PayablesAction.FilterChange(PayableFilter.CREDIT))
            advanceUntilIdle()
            assertEquals(
                listOf(3L),
                (model.uiState.value.content as PayablesContent.Ready).rows.map { it.supplier.id },
            )

            model.onAction(PayablesAction.ClearFilters)
            advanceUntilIdle()
            assertEquals(PayableFilter.ALL, model.uiState.value.filter)
        }

    @Test
    fun `nothing owed is empty, an unknown zone asks for it and a damaged ledger is an error`() =
        runTest(dispatcher) {
            operations.load.value = PayablesLoad.Ready(
                PayablesOverview(TODAY, emptyList(), Money.ZERO, Money.ZERO, 0),
            )
            val model = PayablesViewModel(operations)
            collect(model.uiState)
            assertEquals(PayablesContent.Empty, model.uiState.value.content)

            operations.load.value = PayablesLoad.UnknownTimeZone("Zona/Desconocida")
            advanceUntilIdle()
            assertEquals(PayablesContent.UnknownZone, model.uiState.value.content)

            operations.load.value = PayablesLoad.LedgerDamaged
            advanceUntilIdle()
            assertEquals(PayablesContent.ReadError, model.uiState.value.content)
        }

    @Test
    fun `a read that fails offers to retry and retrying reads again`() = runTest(dispatcher) {
        operations.failPayables = true
        val model = PayablesViewModel(operations)
        collect(model.uiState)
        assertEquals(PayablesContent.ReadError, model.uiState.value.content)

        operations.failPayables = false
        model.onAction(PayablesAction.Retry)
        advanceUntilIdle()
        assertTrue(model.uiState.value.content is PayablesContent.Ready)
    }

    // ------------------------------------------------------------------ detalle

    @Test
    fun `the ledger goes newest first, with each saved balance and only a live payment voidable`() =
        runTest(dispatcher) {
            val view = ready(openDetail())

            assertEquals(listOf(4L, 3L, 2L, 1L), view.lines.map { it.entry.id })
            assertEquals(
                listOf(6_000L, 10_000L, 4_000L, 10_000L).map(::Money),
                view.lines.map { it.entry.content.balanceAfter },
            )
            val voided = view.lines.first { it.entry.id == 2L }
            assertTrue(voided.voided)
            assertFalse(voided.voidable)
            assertTrue(view.lines.first { it.entry.id == 4L }.voidable)
            assertFalse(view.lines.first { it.entry.id == 1L }.voidable)
            assertEquals(PayableDue.DueOn(DUE), view.due)
        }

    @Test
    fun `a charge past its due date is overdue the day after, never the same day`() =
        runTest(dispatcher) {
            operations.today = DUE
            assertEquals(PayableDue.DueOn(DUE), ready(openDetail()).due)

            operations.today = DUE.plusDays(1)
            val late = ready(openDetail())
            assertEquals(PayableDue.Overdue(DUE), late.due)
            assertTrue(late.overdue)
        }

    @Test
    fun `zero is up to date, negative is credit, and a voided paid purchase says so`() =
        runTest(dispatcher) {
            // Compra a plazo de 10000, pago de 4000, y la compra se anula: quedan 4000 a favor.
            operations.entries.value = listOf(
                entry(1, SupplierLedgerEntryType.CREDIT_PURCHASE, 10_000, 10_000, purchase = 7),
                entry(2, SupplierLedgerEntryType.PAYMENT, -4_000, 6_000),
                entry(3, SupplierLedgerEntryType.ENTRY_VOID, -10_000, -4_000, reverses = 1),
            )
            operations.balance = -4_000
            val credit = ready(openDetail())
            assertEquals(PayableDue.Credit, credit.due)
            assertTrue(credit.voidedPurchaseLeftCredit)

            operations.entries.value = emptyList()
            operations.balance = 0
            val clear = ready(openDetail())
            assertEquals(PayableDue.Clear, clear.due)
            assertFalse(clear.voidedPurchaseLeftCredit)
        }

    @Test
    fun `the sheet proposes the debt and shows what it leaves, or the credit if paid in excess`() =
        runTest(dispatcher) {
            val model = openDetail()
            model.onAction(PayableDetailAction.OpenPayment)
            advanceUntilIdle()

            val sheet = requireNotNull(model.uiState.value.payment)
            assertEquals("6000", sheet.amountText)
            assertEquals(PayablePreview.Remaining(Money.ZERO), sheet.preview)

            model.onAction(PayableDetailAction.PaymentAmount("2.000"))
            assertEquals(
                PayablePreview.Remaining(Money(4_000)),
                model.uiState.value.payment?.preview,
            )

            model.onAction(PayableDetailAction.PaymentAmount("7000"))
            assertEquals(
                PayablePreview.CreditLeft(Money(1_000)),
                model.uiState.value.payment?.preview,
            )
        }

    @Test
    fun `a zero amount or a future date never reach the ledger`() = runTest(dispatcher) {
        val model = openDetail()
        model.onAction(PayableDetailAction.OpenPayment)
        model.onAction(PayableDetailAction.PaymentAmount("0"))
        model.onAction(PayableDetailAction.SavePayment)
        advanceUntilIdle()
        assertEquals(setOf(PayableFormError.AMOUNT_ZERO), model.uiState.value.payment?.errors)

        model.onAction(PayableDetailAction.PaymentAmount("1000"))
        model.onAction(PayableDetailAction.PaymentDate(TODAY.plusDays(1)))
        model.onAction(PayableDetailAction.SavePayment)
        advanceUntilIdle()
        assertEquals(setOf(PayableFormError.DATE_FUTURE), model.uiState.value.payment?.errors)
        assertTrue(operations.paid.isEmpty())
    }

    @Test
    fun `without accounts in use the payment carries no account, and with them the chosen one`() =
        runTest(dispatcher) {
            val model = openDetail()
            model.onAction(PayableDetailAction.OpenPayment)
            model.onAction(PayableDetailAction.PaymentAccount(9))
            model.onAction(PayableDetailAction.PaymentNote("  recibo 12  "))
            model.onAction(PayableDetailAction.SavePayment)
            advanceUntilIdle()
            assertEquals(
                SupplierPaymentRequest(SUPPLIER, Money(6_000), null, "recibo 12", null),
                operations.paid.single(),
            )
            assertNull(model.uiState.value.payment)
            assertEquals(PayableDetailMessage.PaymentRecorded, model.uiState.value.message)

            operations.choices.value = PaymentChoices(accountsInUse = true)
            advanceUntilIdle()
            model.onAction(PayableDetailAction.OpenPayment)
            model.onAction(PayableDetailAction.PaymentAccount(9))
            model.onAction(PayableDetailAction.SavePayment)
            advanceUntilIdle()
            assertEquals(9L, operations.paid.last().moneyAccountId)
        }

    @Test
    fun `a failure keeps what was written and says why inside the sheet`() = runTest(dispatcher) {
        operations.payResult = PaySupplierResult.StorageFailed(StorageFailure.DISK_FULL)
        val model = openDetail()
        model.onAction(PayableDetailAction.OpenPayment)
        model.onAction(PayableDetailAction.PaymentAmount("2500"))
        model.onAction(PayableDetailAction.SavePayment)
        advanceUntilIdle()

        val sheet = requireNotNull(model.uiState.value.payment)
        assertEquals("2500", sheet.amountText)
        assertFalse(sheet.working)
        assertEquals(PayableFormNotice.SaveFailed(outOfSpace = true), sheet.notice)

        operations.payResult = PaySupplierResult.MoneyAccountArchived
        model.onAction(PayableDetailAction.SavePayment)
        advanceUntilIdle()
        assertEquals(PayableFormNotice.AccountUnavailable, model.uiState.value.payment?.notice)

        operations.payResult = PaySupplierResult.CurrencyNotConfirmed
        model.onAction(PayableDetailAction.SavePayment)
        advanceUntilIdle()
        assertEquals(PayableFormNotice.CurrencyUnconfirmed, model.uiState.value.payment?.notice)
    }

    @Test
    fun `voiding asks first, then writes once and says so`() = runTest(dispatcher) {
        val model = openDetail()
        model.onAction(PayableDetailAction.AskVoidPayment(4))
        assertEquals(4L, model.uiState.value.voidingEntryId)
        assertTrue(operations.voided.isEmpty())

        model.onAction(PayableDetailAction.ConfirmVoid)
        advanceUntilIdle()
        assertEquals(listOf(4L), operations.voided)
        assertNull(model.uiState.value.voidingEntryId)
        assertEquals(PayableDetailMessage.PaymentVoided, model.uiState.value.message)

        operations.voidResult = VoidSupplierPaymentResult.AlreadyVoided
        model.onAction(PayableDetailAction.MessageShown)
        model.onAction(PayableDetailAction.AskVoidPayment(4))
        model.onAction(PayableDetailAction.ConfirmVoid)
        advanceUntilIdle()
        assertNull(model.uiState.value.message)
    }

    @Test
    fun `a missing supplier or an unknown zone never close the screen`() = runTest(dispatcher) {
        operations.exists = false
        assertEquals(PayableDetailContent.Missing, openDetail().uiState.value.content)

        operations.exists = true
        operations.zoneKnown = false
        assertEquals(PayableDetailContent.UnknownZone, openDetail().uiState.value.content)
    }

    @Test
    fun `the preview is null without an amount and never overflows`() {
        assertNull(previewOf(Money(1), null))
        assertNull(previewOf(null, Money(1)))
        assertNull(previewOf(Money(1), Money.ZERO))
        assertNull(previewOf(Money(Long.MIN_VALUE), Money(1)))
    }

    // ------------------------------------------------------------------ apoyo

    private fun TestScope.openDetail(): PayableDetailViewModel {
        val model = PayableDetailViewModel(SUPPLIER, operations, numbers)
        advanceUntilIdle()
        return model
    }

    private fun ready(model: PayableDetailViewModel): PayableDetailView =
        (model.uiState.value.content as PayableDetailContent.Ready).view

    private fun TestScope.collect(flow: Flow<*>) {
        backgroundScope.launch(dispatcher) { flow.collect {} }
        advanceUntilIdle()
    }

    private class FakePayableOperations : PayableOperations {
        val load = MutableStateFlow<PayablesLoad>(
            PayablesLoad.Ready(
                PayablesOverview(
                    TODAY,
                    listOf(
                        row(1, PayableStatus.OVERDUE, 6_000),
                        row(2, PayableStatus.UPCOMING, 3_000),
                        row(3, PayableStatus.IN_FAVOR, 0),
                    ),
                    Money(9_000),
                    Money(3_000),
                    1,
                ),
            ),
        )
        var failPayables = false
        val entries = MutableStateFlow(
            listOf(
                entry(1, SupplierLedgerEntryType.CREDIT_PURCHASE, 10_000, 10_000, 7, due = DUE),
                entry(2, SupplierLedgerEntryType.PAYMENT, -6_000, 4_000),
                entry(3, SupplierLedgerEntryType.ENTRY_VOID, 6_000, 10_000, reverses = 2),
                entry(4, SupplierLedgerEntryType.PAYMENT, -4_000, 6_000),
            ),
        )
        var balance = 6_000L
        var exists = true
        var zoneKnown = true
        var today: BusinessDate = TODAY
        val choices = MutableStateFlow(PaymentChoices())
        var payResult: PaySupplierResult = PaySupplierResult.Paid(5)
        var voidResult: VoidSupplierPaymentResult = VoidSupplierPaymentResult.Voided
        val paid = mutableListOf<SupplierPaymentRequest>()
        val voided = mutableListOf<Long>()

        override fun payables(): Flow<PayablesLoad> =
            if (failPayables) flow { error("lectura fallida") } else load

        override fun statement(supplierId: Long): Flow<SupplierStatement?> = flow {
            entries.collect { list ->
                emit(
                    if (exists) {
                        SupplierStatement(
                            supplier(supplierId, balance),
                            list,
                            SupplierDebts.of(list),
                        )
                    } else {
                        null
                    },
                )
            }
        }

        override fun calendar(): Flow<BusinessCalendarResult> = flowOf(
            if (zoneKnown) {
                BusinessCalendarResult.Ready(CALENDAR)
            } else {
                BusinessCalendarResult.UnknownZone("Zona/Desconocida")
            },
        )

        override fun paymentChoices(): Flow<PaymentChoices> = choices

        override suspend fun pay(request: SupplierPaymentRequest): PaySupplierResult {
            paid += request
            return payResult
        }

        override suspend fun voidPayment(entryId: Long): VoidSupplierPaymentResult {
            voided += entryId
            return voidResult
        }

        // Mediodía UTC del día elegido: en la zona del negocio, que es UTC, sigue siendo ese día.
        override fun now(): Instant = Instant.parse("${today}T12:00:00Z")
    }

    private companion object {
        const val SUPPLIER = 5L
        val AT: Instant = Instant.fromEpochMilliseconds(0)
        val TODAY = BusinessDate(LocalDate(2026, 11, 10))
        val DUE = BusinessDate(LocalDate(2026, 11, 20))
        val CALENDAR =
            BusinessCalendar(requireNotNull(BusinessTimeZone.of("Etc/UTC")), DayOfWeek.MONDAY)

        fun supplier(id: Long, balance: Long) = Supplier(
            id = id,
            details = SupplierDetails("Proveedor $id", "proveedor $id", null, null, null, null),
            balance = Money(balance),
            archivedAt = null,
            createdAt = AT,
            updatedAt = AT,
        )

        fun row(id: Long, status: PayableStatus, owed: Long) = PayableRow(
            supplier = supplier(id, owed),
            status = status,
            owed = Money(owed),
            creditInFavor = if (status == PayableStatus.IN_FAVOR) Money(1_000) else Money.ZERO,
            overdueSince = null,
            overdueAmount = Money.ZERO,
            nextDue = null,
        )

        fun entry(
            id: Long,
            type: SupplierLedgerEntryType,
            amount: Long,
            after: Long,
            purchase: Long? = null,
            reverses: Long? = null,
            due: BusinessDate? = null,
        ) = SupplierLedgerEntry(
            id,
            NewSupplierLedgerEntry(
                supplierId = SUPPLIER,
                type = type,
                amount = Money(amount),
                balanceAfter = Money(after),
                purchaseId = purchase,
                reversesEntryId = reverses,
                dueDate = due,
                occurredAt = AT,
                businessDate = TODAY,
                createdAt = AT,
            ),
        )
    }
}

package io.github.yesidrobayo.shopledger.ui.customer

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.customer.Customer
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerContact
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerEntrySource
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerForm
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerLedgerEntryType
import io.github.yesidrobayo.shopledger.domain.model.region.DigitGrouping
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleHistoryCursor
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleHistoryRow
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.rule.customer.CustomerDueRules
import io.github.yesidrobayo.shopledger.domain.rule.customer.PaymentAllocation
import io.github.yesidrobayo.shopledger.domain.rule.customer.PaymentPreview
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.customer.AdjustCustomerBalanceResult
import io.github.yesidrobayo.shopledger.domain.usecase.customer.AnonymizeCustomerResult
import io.github.yesidrobayo.shopledger.domain.usecase.customer.CustomerLifecycleResult
import io.github.yesidrobayo.shopledger.domain.usecase.customer.CustomerPaymentRequest
import io.github.yesidrobayo.shopledger.domain.usecase.customer.CustomerStatement
import io.github.yesidrobayo.shopledger.domain.usecase.customer.PaymentChoices
import io.github.yesidrobayo.shopledger.domain.usecase.customer.RecordCustomerPaymentResult
import io.github.yesidrobayo.shopledger.domain.usecase.customer.SaveCustomerResult
import io.github.yesidrobayo.shopledger.domain.usecase.customer.VoidCustomerPaymentResult
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.DayOfWeek
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * El detalle de un cliente con respuestas fijas del dominio: extracto, vencidos, abono, ajuste,
 * anulación y extracto compartido. Nombres y montos inventados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CustomerDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val operations = FakeDetailOperations()
    private val numbers = CustomerNumberFormat(NumberSymbols(',', '-', DigitGrouping('.', 3, 3)), 0)

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    @Test
    fun `the statement goes newest first, keeps each saved balance and marks the voided payment`() =
        runTest(dispatcher) {
            val model = open()

            val view = ready(model)
            assertEquals(listOf(4L, 3L, 2L, 1L), view.lines.map { it.entry.id })
            assertEquals(
                listOf(7_000L, 10_000L, 4_000L, 10_000L).map(::Money),
                view.lines.map { it.entry.balanceAfter },
            )
            // El abono 2 está compensado por la fila 3: se ve anulado y ya no se puede anular.
            val payment = view.lines.first { it.entry.id == 2L }
            assertTrue(payment.voided)
            assertFalse(payment.voidable)
            assertFalse(view.lines.first { it.entry.id == 3L }.voidable)
            assertTrue(view.lines.first { it.entry.id == 4L }.voidable)
        }

    @Test
    fun `a charge past its due date shows overdue since that day, and today is not overdue yet`() =
        runTest(dispatcher) {
            operations.today = DUE_DATE
            val onTime = ready(open())
            assertEquals(DueStatus.DueOn(DUE_DATE), onTime.due)

            operations.today = requireNotNull(BusinessDate.parse("2026-11-21"))
            val late = ready(open())
            assertEquals(DueStatus.Overdue(DUE_DATE), late.due)
            assertTrue(late.overdue)
        }

    @Test
    fun `zero balance is up to date and a negative one is credit`() = runTest(dispatcher) {
        operations.entries.value = listOf(entry(1, CustomerLedgerEntryType.PAYMENT, -2_000, -2_000))
        operations.customer.value = customer(balance = -2_000)
        assertEquals(DueStatus.Credit, ready(open()).due)

        operations.entries.value = emptyList()
        operations.customer.value = customer(balance = 0)
        assertEquals(DueStatus.Clear, ready(open()).due)
    }

    @Test
    fun `a missing customer or a zone the phone does not know never closes the screen`() =
        runTest(dispatcher) {
            operations.customer.value = null
            val missing = open()
            assertEquals(CustomerDetailContent.Missing, missing.uiState.value.content)

            operations.customer.value = customer()
            operations.calendar = BusinessCalendarResult.UnknownZone("Mars/Base")
            assertEquals(CustomerDetailContent.UnknownZone, open().uiState.value.content)
        }

    @Test
    fun `the payment sheet suggests the debt and previews the balance and the credit left`() =
        runTest(dispatcher) {
            val model = open()
            model.onAction(CustomerDetailAction.OpenPayment)
            val sheet = requireNotNull(model.uiState.value.payment)
            assertEquals("7000", sheet.amountText)
            assertEquals(PaymentPreview.RemainingDebt(Money.ZERO), sheet.preview)

            model.onAction(CustomerDetailAction.PaymentAmount("4000"))
            assertEquals(
                PaymentPreview.RemainingDebt(Money(3_000)),
                model.uiState.value.payment?.preview,
            )
            model.onAction(CustomerDetailAction.PaymentAmount("12500"))
            assertEquals(
                PaymentPreview.CreditLeft(Money(5_500)),
                model.uiState.value.payment?.preview,
            )
        }

    @Test
    fun `a zero payment is refused in the sheet and nothing is recorded`() = runTest(dispatcher) {
        val model = open()
        model.onAction(CustomerDetailAction.OpenPayment)
        model.onAction(CustomerDetailAction.PaymentAmount(""))
        model.onAction(CustomerDetailAction.SavePayment)
        advanceUntilIdle()

        assertEquals(setOf(CustomerFormError.AMOUNT_ZERO), model.uiState.value.payment?.errors)
        assertTrue(operations.payments.isEmpty())
    }

    @Test
    fun `without money accounts the payment goes to no account, with them to the method account`() =
        runTest(dispatcher) {
            val model = open()
            model.onAction(CustomerDetailAction.OpenPayment)
            model.onAction(CustomerDetailAction.PaymentAmount("3000"))
            model.onAction(CustomerDetailAction.PaymentNote("  en efectivo  "))
            model.onAction(CustomerDetailAction.SavePayment)
            advanceUntilIdle()

            assertEquals(
                CustomerPaymentRequest(CUSTOMER_ID, Money(3_000), null, null, "en efectivo"),
                operations.payments.single(),
            )
            assertNull(model.uiState.value.payment)
            assertEquals(CustomerDetailMessage.PaymentRecorded, model.uiState.value.message)
        }

    @Test
    fun `a failed payment keeps what was written and says why inside the sheet`() =
        runTest(dispatcher) {
            operations.paymentResult =
                RecordCustomerPaymentResult.StorageFailed(StorageFailure.DISK_FULL)
            val model = open()
            model.onAction(CustomerDetailAction.OpenPayment)
            model.onAction(CustomerDetailAction.PaymentAmount("3000"))
            model.onAction(CustomerDetailAction.SavePayment)
            advanceUntilIdle()

            val sheet = requireNotNull(model.uiState.value.payment)
            assertEquals("3000", sheet.amountText)
            assertEquals(CustomerFormNotice.SaveFailed(outOfSpace = true), sheet.notice)
            assertFalse(sheet.working)
        }

    @Test
    fun `an adjustment needs a note and accepts a negative amount`() = runTest(dispatcher) {
        val model = open()
        model.onAction(CustomerDetailAction.OpenAdjust)
        model.onAction(CustomerDetailAction.AdjustAmount("-1500"))
        model.onAction(CustomerDetailAction.SaveAdjust)
        advanceUntilIdle()
        assertEquals(setOf(CustomerFormError.NOTE_REQUIRED), model.uiState.value.adjust?.errors)
        assertTrue(operations.adjustments.isEmpty())

        model.onAction(CustomerDetailAction.AdjustNote("Error de suma"))
        model.onAction(CustomerDetailAction.SaveAdjust)
        advanceUntilIdle()
        assertEquals(listOf(Money(-1_500) to "Error de suma"), operations.adjustments)
        assertEquals(CustomerDetailMessage.Adjusted, model.uiState.value.message)
    }

    @Test
    fun `voiding a payment asks first and then writes its compensation`() = runTest(dispatcher) {
        val model = open()
        model.onAction(CustomerDetailAction.AskVoidPayment(4))
        assertEquals(CustomerDetailDialog.VoidPayment(4), model.uiState.value.dialog)
        assertTrue(operations.voided.isEmpty())

        model.onAction(CustomerDetailAction.ConfirmDialog)
        advanceUntilIdle()
        assertEquals(listOf(4L), operations.voided)
        assertEquals(CustomerDetailMessage.PaymentVoided, model.uiState.value.message)
    }

    @Test
    fun `archiving and anonymizing carry the balance into the question`() = runTest(dispatcher) {
        val model = open()
        model.onAction(CustomerDetailAction.AskArchive)
        assertEquals(
            CustomerDetailDialog.Archive(NAME, Money(7_000)),
            model.uiState.value.dialog,
        )
        model.onAction(CustomerDetailAction.ConfirmDialog)
        advanceUntilIdle()
        assertEquals(CustomerDetailMessage.Archived, model.uiState.value.message)

        model.onAction(CustomerDetailAction.AskAnonymize)
        assertEquals(
            CustomerDetailDialog.Anonymize(NAME, Money(7_000)),
            model.uiState.value.dialog,
        )
        model.onAction(CustomerDetailAction.ConfirmDialog)
        advanceUntilIdle()
        assertEquals(CustomerDetailMessage.Anonymized, model.uiState.value.message)
        model.onAction(CustomerDetailAction.UpdateCopies)
        advanceUntilIdle()
        assertEquals(1, operations.copiesRequested)
    }

    @Test
    fun `the shared statement goes oldest first, without notes, and its rows add up`() =
        runTest(dispatcher) {
            operations.businessName = "Tienda de prueba"
            val model = open()
            model.onAction(CustomerDetailAction.ShareStatement)
            val document = requireNotNull(model.uiState.value.statement)

            assertEquals("Tienda de prueba", document.businessName)
            assertEquals(NAME, document.customerName)
            assertEquals(Money(7_000), document.balance)
            assertEquals(4, document.lines.size)
            assertEquals(listOf(false, true, false, false), document.lines.map { it.voided })
            // Cada fila deja el saldo de la anterior más su monto, como en el libro.
            var running = Money.ZERO
            document.lines.forEach { line ->
                running += line.amount
                assertEquals(running, line.balanceAfter)
            }
            assertEquals(document.balance, document.lines.last().balanceAfter)

            model.onAction(CustomerDetailAction.StatementShared(ok = false))
            assertNull(model.uiState.value.statement)
            assertEquals(CustomerDetailMessage.StatementFailed, model.uiState.value.message)
        }

    @Test
    fun `sales load in pages when their segment opens and stop at the last one`() =
        runTest(dispatcher) {
            operations.sales = List(45) { sale(it + 1L) }
            val model = open()
            assertTrue(operations.salesPages.isEmpty())

            model.onAction(CustomerDetailAction.SelectTab(CustomerDetailTab.SALES))
            advanceUntilIdle()
            assertEquals(30, model.uiState.value.sales.rows.size)
            model.onAction(CustomerDetailAction.LoadMoreSales)
            advanceUntilIdle()
            val sales = model.uiState.value.sales
            assertEquals(45, sales.rows.size)
            assertTrue(sales.complete)
            model.onAction(CustomerDetailAction.LoadMoreSales)
            advanceUntilIdle()
            assertEquals(2, operations.salesPages.size)
        }

    private fun TestScope.open(): CustomerDetailViewModel {
        val model = CustomerDetailViewModel(CUSTOMER_ID, operations, numbers)
        backgroundScope.launch { model.uiState.collect {} }
        advanceUntilIdle()
        return model
    }

    private fun ready(model: CustomerDetailViewModel): CustomerDetailView =
        (model.uiState.value.content as CustomerDetailContent.Ready).view

    private class FakeDetailOperations : CustomerDetailOperations {
        val customer = MutableStateFlow<Customer?>(customer())

        /**
         * Una venta a crédito de 10000 con vencimiento, un abono de 6000 que se anuló y otro de 3000
         * que sigue: el cliente debe 7000, todo del cargo con vencimiento.
         */
        val entries = MutableStateFlow(
            listOf(
                entry(
                    1,
                    CustomerLedgerEntryType.CREDIT_SALE,
                    10_000,
                    10_000,
                    CustomerEntrySource.Sale(7),
                    DUE_DATE,
                ),
                entry(2, CustomerLedgerEntryType.PAYMENT, -6_000, 4_000),
                entry(
                    3,
                    CustomerLedgerEntryType.ENTRY_VOID,
                    6_000,
                    10_000,
                    CustomerEntrySource.Reverses(2),
                ),
                entry(4, CustomerLedgerEntryType.PAYMENT, -3_000, 7_000),
            ),
        )
        var today: BusinessDate = requireNotNull(BusinessDate.parse("2026-11-12"))
        var calendar: BusinessCalendarResult? = null
        var businessName: String? = null
        var paymentResult: RecordCustomerPaymentResult? = null
        var sales: List<SaleHistoryRow> = emptyList()
        val payments = mutableListOf<CustomerPaymentRequest>()
        val adjustments = mutableListOf<Pair<Money, String>>()
        val voided = mutableListOf<Long>()
        val salesPages = mutableListOf<SaleHistoryCursor?>()
        var copiesRequested = 0

        override fun statement(customerId: Long): Flow<CustomerStatement?> =
            combine(customer, entries) { found, rows ->
                found?.let { CustomerStatement(it, rows.reversed(), CustomerDueRules.duesOf(rows)) }
            }

        override fun calendar(): Flow<BusinessCalendarResult> = flowOf(
            calendar ?: BusinessCalendarResult.Ready(BusinessCalendar(ZONE, DayOfWeek.MONDAY)),
        )

        override fun loyaltyEnabled(): Flow<Boolean> = flowOf(false)

        override fun paymentChoices(): Flow<PaymentChoices> = flowOf(PaymentChoices())

        override fun businessName(): Flow<String?> = flowOf(businessName)

        override fun salesChanges(): Flow<String> = flowOf("0")

        override suspend fun salesPage(
            customerId: Long,
            after: SaleHistoryCursor?,
            limit: Int,
        ): List<SaleHistoryRow> {
            salesPages += after
            val start = after?.let { cursor -> sales.indexOfFirst { it.id == cursor.id } + 1 } ?: 0
            return sales.drop(start).take(limit)
        }

        override suspend fun saveCustomer(
            customerId: Long?,
            form: CustomerForm,
            allowSameName: Boolean,
        ): SaveCustomerResult = SaveCustomerResult.NotFound

        override suspend fun recordPayment(
            request: CustomerPaymentRequest,
        ): RecordCustomerPaymentResult {
            payments += request
            return paymentResult ?: RecordCustomerPaymentResult.Recorded(
                9,
                null,
                Money(7_000),
                PaymentAllocation(emptyList(), Money.ZERO),
            )
        }

        override suspend fun voidPayment(
            customerId: Long,
            entryId: Long,
        ): VoidCustomerPaymentResult {
            voided += entryId
            return VoidCustomerPaymentResult.Voided(10, emptyList(), Money(10_000))
        }

        override suspend fun adjust(
            customerId: Long,
            amount: Money,
            note: String,
        ): AdjustCustomerBalanceResult {
            adjustments += amount to note
            return AdjustCustomerBalanceResult.Recorded(11, Money(8_500))
        }

        override suspend fun archive(customerId: Long) = CustomerLifecycleResult.Done

        override suspend fun reactivate(customerId: Long) = CustomerLifecycleResult.Done

        override suspend fun anonymize(customerId: Long) = AnonymizeCustomerResult.Done

        override suspend fun updateCopies() {
            copiesRequested++
        }

        // Las doce del día de hoy en la zona del negocio.
        override fun now(): Instant = Instant.parse("${today}T12:00:00Z")
    }

    private companion object {
        const val CUSTOMER_ID = 5L
        const val NAME = "Cliente de prueba"
        val ZONE = requireNotNull(BusinessTimeZone.of("Etc/UTC"))
        val DUE_DATE = requireNotNull(BusinessDate.parse("2026-11-20"))
        val AT = Instant.parse("2026-11-01T12:00:00Z")

        fun customer(balance: Long = 7_000) = Customer(
            id = CUSTOMER_ID,
            contact = CustomerContact(NAME, "cliente de prueba", null, null, null),
            priceListId = null,
            balance = Money(balance),
            pointsBalance = 0,
            anonymizedAt = null,
            archivedAt = null,
            createdAt = AT,
            updatedAt = AT,
        )

        fun entry(
            id: Long,
            type: CustomerLedgerEntryType,
            amount: Long,
            balanceAfter: Long,
            source: CustomerEntrySource = CustomerEntrySource.None,
            dueDate: BusinessDate? = null,
        ) = CustomerLedgerEntry(
            id = id,
            customerId = CUSTOMER_ID,
            type = type,
            amount = Money(amount),
            balanceAfter = Money(balanceAfter),
            source = source,
            dueDate = dueDate,
            note = "nota interna".takeIf { type == CustomerLedgerEntryType.PAYMENT },
            occurredAt = AT,
            businessDate = requireNotNull(BusinessDate.parse("2026-11-01")),
        )

        fun sale(id: Long) = SaleHistoryRow(
            id = id,
            occurredAt = Instant.fromEpochSeconds(2_000_000_000L - id),
            businessDate = requireNotNull(BusinessDate.parse("2026-11-01")),
            total = Money(1_000),
            voided = false,
            firstProductName = "Producto de prueba",
            lineCount = 1,
            methodName = null,
            paymentCount = 1,
        )
    }
}

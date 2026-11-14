package io.github.yesidrobayo.shopledger.ui.quote

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.document.DocumentLine
import io.github.yesidrobayo.shopledger.domain.model.document.DocumentTotals
import io.github.yesidrobayo.shopledger.domain.model.document.NewDocumentLine
import io.github.yesidrobayo.shopledger.domain.model.document.Quote
import io.github.yesidrobayo.shopledger.domain.model.document.QuoteStatus
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.usecase.document.CancelQuoteResult
import io.github.yesidrobayo.shopledger.domain.usecase.document.ConvertibleLine
import io.github.yesidrobayo.shopledger.domain.usecase.document.QuoteConversion
import io.github.yesidrobayo.shopledger.domain.usecase.document.QuoteConversionResult
import io.github.yesidrobayo.shopledger.domain.usecase.document.QuoteRow
import io.github.yesidrobayo.shopledger.domain.usecase.document.QuoteView
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * La lista y el detalle de las cotizaciones sin base: los chips, los vacíos, la etiqueta de
 * vencida, y convertir con sus avisos antes de abrir la venta. Nombres y montos inventados.
 */
class QuoteViewModelsTest {
    private val dispatcher = StandardTestDispatcher()
    private val quotes = MutableStateFlow(
        listOf(quote(1, QuoteStatus.OPEN, YESTERDAY), quote(2, QuoteStatus.OPEN, null)),
    )
    private var listFails = false
    private var conversion: QuoteConversionResult = ready(changed = false, expired = false)
    private var cancelled = mutableListOf<Long>()

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    @Test
    fun `the list opens on open quotes and marks the expired one`() = runTest(dispatcher) {
        val model = list()

        val rows = (model.uiState.value.content as QuotesContent.Rows).items
        assertEquals(listOf(1L, 2L), rows.map { it.id })
        assertTrue(rows[0].expired)
        assertFalse(rows[1].expired)
        assertEquals("Customer 1", rows[0].customerName)
        assertEquals(QuoteStatus.OPEN, model.uiState.value.filter)
    }

    @Test
    fun `another status with nothing says so, and no quote at all invites to create one`() =
        runTest(dispatcher) {
            val model = list()
            model.onAction(QuotesAction.SelectFilter(QuoteStatus.CANCELLED))
            advanceUntilIdle()
            assertEquals(QuotesContent.FilterEmpty, model.uiState.value.content)

            quotes.value = emptyList()
            val empty = list(hasAny = false)
            assertEquals(QuotesContent.Empty, empty.uiState.value.content)
        }

    @Test
    fun `a read failure offers to retry and retrying reads again`() = runTest(dispatcher) {
        listFails = true
        val model = list()
        assertEquals(QuotesContent.Failed, model.uiState.value.content)

        listFails = false
        model.onAction(QuotesAction.Retry)
        advanceUntilIdle()
        assertTrue(model.uiState.value.content is QuotesContent.Rows)
    }

    @Test
    fun `a wide window opens the detail beside the list`() = runTest(dispatcher) {
        val model = list()
        model.onAction(QuotesAction.Open(2))
        advanceUntilIdle()
        assertEquals(2L, model.uiState.value.selectedId)
        model.onAction(QuotesAction.CloseDetail)
        advanceUntilIdle()
        assertNull(model.uiState.value.selectedId)
    }

    @Test
    fun `the detail shows the frozen lines without cost`() = runTest(dispatcher) {
        val model = detail()

        val shown = (model.uiState.value.content as QuoteDetailContent.Ready).quote
        assertEquals(Money(5_000), shown.totals.total)
        assertEquals("Product 7", shown.lines.single().name)
        assertEquals(Money(2_500), shown.lines.single().unitPrice)
        assertTrue(shown.expired)
    }

    @Test
    fun `converting an unchanged quote opens the sale with the quoted prices`() =
        runTest(dispatcher) {
            val model = detail()
            model.onAction(QuoteDetailAction.Convert)
            advanceUntilIdle()

            assertEquals(QuoteToSell(1, useCurrentPrices = false), model.uiState.value.toSell)
            model.onAction(QuoteDetailAction.SellShown)
            advanceUntilIdle()
            assertNull(model.uiState.value.toSell)
        }

    @Test
    fun `an expired quote warns first and a changed price asks which one to use`() =
        runTest(dispatcher) {
            conversion = ready(changed = true, expired = true)
            val model = detail()
            model.onAction(QuoteDetailAction.Convert)
            advanceUntilIdle()
            assertEquals(QuoteDetailDialog.Expired(YESTERDAY), model.uiState.value.dialog)

            model.onAction(QuoteDetailAction.ConfirmExpired)
            advanceUntilIdle()
            assertEquals(QuoteDetailDialog.PriceChanged(1), model.uiState.value.dialog)

            model.onAction(QuoteDetailAction.ChoosePrices(useCurrentPrices = true))
            advanceUntilIdle()
            assertNull(model.uiState.value.dialog)
            assertEquals(QuoteToSell(1, useCurrentPrices = true), model.uiState.value.toSell)
        }

    @Test
    fun `a quote that can no longer be converted says so and opens nothing`() =
        runTest(dispatcher) {
            conversion = QuoteConversionResult.NotOpen(QuoteStatus.CONVERTED)
            val model = detail()
            model.onAction(QuoteDetailAction.Convert)
            advanceUntilIdle()

            assertEquals(QuoteDetailNotice.CONVERT_FAILED, model.uiState.value.notice)
            assertNull(model.uiState.value.toSell)
        }

    @Test
    fun `cancelling asks first and then cancels once`() = runTest(dispatcher) {
        val model = detail()
        model.onAction(QuoteDetailAction.AskCancel)
        advanceUntilIdle()
        assertEquals(QuoteDetailDialog.ConfirmCancel, model.uiState.value.dialog)

        model.onAction(QuoteDetailAction.ConfirmCancel)
        advanceUntilIdle()
        assertEquals(listOf(1L), cancelled)
        assertEquals(QuoteDetailNotice.CANCELLED, model.uiState.value.notice)
        assertNull(model.uiState.value.dialog)
    }

    @Test
    fun `a missing quote is said instead of an empty detail`() = runTest(dispatcher) {
        val model = detail(quoteId = 99)
        assertEquals(QuoteDetailContent.Missing, model.uiState.value.content)
    }

    // ---------------------------------------------------------------- dobles

    private fun TestScope.list(hasAny: Boolean = true): QuotesViewModel {
        val model = QuotesViewModel(
            object : QuoteListOperations {
                override fun list(status: QuoteStatus): Flow<List<QuoteRow>> {
                    if (listFails) return flow { error("lectura inventada que falla") }
                    return quotes.map { all ->
                        all.filter { it.status == status }.map { QuoteRow(it, "Customer ${it.id}") }
                    }
                }

                override fun hasAny(): Flow<Boolean> = flowOf(hasAny)

                override fun today(): Flow<BusinessDate?> = flowOf(TODAY)
            },
        )
        backgroundScope.launch { model.uiState.collect {} }
        advanceUntilIdle()
        return model
    }

    private fun TestScope.detail(quoteId: Long = 1): QuoteDetailViewModel {
        val model = QuoteDetailViewModel(
            quoteId,
            object : QuoteDetailOperations {
                override fun quote(quoteId: Long): Flow<QuoteView?> = quotes.map { all ->
                    all.firstOrNull { it.id == quoteId }?.let { QuoteView(it, listOf(LINE), null) }
                }

                override fun units(): Flow<Map<Long, MeasureUnit>> = flowOf(emptyMap())

                override fun today(): Flow<BusinessDate?> = flowOf(TODAY)

                override suspend fun conversion(quoteId: Long) = conversion

                override suspend fun cancel(quoteId: Long): CancelQuoteResult {
                    cancelled += quoteId
                    return CancelQuoteResult.Cancelled
                }
            },
        )
        backgroundScope.launch { model.uiState.collect {} }
        advanceUntilIdle()
        return model
    }

    private fun ready(changed: Boolean, expired: Boolean) = QuoteConversionResult.Ready(
        QuoteConversion(
            quoteId = 1,
            customerId = null,
            priceListId = null,
            customerDropped = false,
            lines = listOf(
                ConvertibleLine(
                    SaleLineRequest(7, QuantityMilli(2_000), Money(2_500)),
                    if (changed) Money(2_800) else Money(2_500),
                ),
            ),
            archivedLines = 0,
            expiredOn = YESTERDAY.takeIf { expired },
        ),
    )

    private companion object {
        val TODAY = BusinessDate(LocalDate(2026, 11, 2))
        val YESTERDAY = BusinessDate(LocalDate(2026, 11, 1))
        val AT: Instant = Instant.fromEpochMilliseconds(1_793_631_600_000L)

        val LINE = DocumentLine(
            id = 1,
            documentId = 1,
            content = NewDocumentLine(
                productId = 7,
                unitId = 1,
                unitFactor = QuantityMilli(1_000),
                enteredQuantity = QuantityMilli(2_000),
                quantity = QuantityMilli(2_000),
                unitPrice = Money(2_500),
                lineDiscount = Money.ZERO,
                netAmount = Money(5_000),
                taxRate = BasisPoints.ZERO,
                taxNameSnapshot = null,
                taxAmount = Money.ZERO,
                lineTotal = Money(5_000),
                productNameSnapshot = "Product 7",
            ),
        )

        fun quote(id: Long, status: QuoteStatus, validUntil: BusinessDate?) = Quote(
            id = id,
            customerId = id,
            status = status,
            validUntil = validUntil,
            totals = DocumentTotals(Money(5_000), Money.ZERO, Money(5_000)),
            convertedSaleId = null,
            note = null,
            occurredAt = AT,
            businessDate = TODAY,
            createdAt = AT,
            updatedAt = AT,
        )
    }
}

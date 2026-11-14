package io.github.yesidrobayo.shopledger.ui.receipt

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.Voiding
import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptAmount
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.sale.LineStockEffect
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSale
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSaleLine
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSalePayment
import io.github.yesidrobayo.shopledger.domain.model.sale.Sale
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleDetail
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLine
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePayment
import io.github.yesidrobayo.shopledger.domain.model.settings.ReceiptSettings
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * El comprobante de una venta sin base ni disco: el documento sale de la venta con la plantilla, el
 * cliente y el logo; se rehace si la venta se anula; compartir es una cosa a la vez y una falla se
 * puede reintentar.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SaleReceiptViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val detail = MutableStateFlow<SaleDetail?>(sale())
    private var failRead = false
    private val source = SaleReceiptSource(
        ReceiptTemplateSource(ReceiptSettings(LOGO_ID, "Calle 8", "Gracias", null), "Tienda"),
        BusinessTimeZone.of("UTC"),
    )

    @Before
    fun useTestMain() = Dispatchers.setMain(dispatcher)

    @After
    fun resetTestMain() = Dispatchers.resetMain()

    private fun TestScope.model(): SaleReceiptViewModel {
        val model = SaleReceiptViewModel(
            saleId = SALE_ID,
            observeDetail = { if (failRead) flow { error("Falla inventada") } else detail },
            observeSource = { flowOf(source) },
            observeUnits = { flowOf(emptyList()) },
            customerName = { id -> "Cliente de prueba".takeIf { id == CUSTOMER_ID } },
            loadImage = { id -> LOGO.takeIf { id == LOGO_ID } },
            computation = dispatcher,
        )
        backgroundScope.launch(dispatcher) { model.uiState.collect {} }
        advanceUntilIdle()
        return model
    }

    private fun SaleReceiptViewModel.ready() = uiState.value as SaleReceiptUiState.Ready

    @Test
    fun `the document comes from the sale with the template, the customer and the logo`() =
        runTest(dispatcher) {
            val ready = model().ready()

            assertEquals(SALE_ID, ready.document.number)
            assertEquals("Tienda", ready.document.header.businessName)
            assertEquals("Calle 8", ready.document.header.businessDetails)
            assertEquals("Gracias", ready.document.footerText)
            assertEquals("Cliente de prueba", ready.document.customerName)
            assertEquals(LOGO, ready.logo)
            assertEquals(source.zone, ready.zone)
            assertEquals(
                ReceiptAmount.Total(Money(5_000L)),
                ready.document.amounts.filterIsInstance<ReceiptAmount.Total>().single(),
            )
        }

    @Test
    fun `voiding the sale redoes the document, which says so`() = runTest(dispatcher) {
        val model = model()
        assertFalse(model.ready().document.voided)

        detail.value = sale(voided = true)
        advanceUntilIdle()

        assertTrue(model.ready().document.voided)
    }

    @Test
    fun `a missing sale and a failed read have their own states`() = runTest(dispatcher) {
        detail.value = null
        assertEquals(SaleReceiptUiState.NotFound, model().uiState.value)

        failRead = true
        val failed = model()
        assertEquals(SaleReceiptUiState.ReadFailed, failed.uiState.value)
        failRead = false
        detail.value = sale()
        failed.onAction(SaleReceiptAction.Retry)
        advanceUntilIdle()
        assertTrue(failed.uiState.value is SaleReceiptUiState.Ready)
    }

    @Test
    fun `sharing is one file at a time, and a failure can be retried`() = runTest(dispatcher) {
        val model = model()

        model.onAction(SaleReceiptAction.Share(ReceiptShare.PDF))
        model.onAction(SaleReceiptAction.Share(ReceiptShare.IMAGE))
        advanceUntilIdle()
        assertEquals(ReceiptShare.PDF, model.ready().sharing)

        model.onAction(SaleReceiptAction.ShareFinished(shared = false))
        advanceUntilIdle()
        assertNull(model.ready().sharing)
        assertEquals(ReceiptShare.PDF, model.ready().shareFailed)

        model.onAction(SaleReceiptAction.Share(ReceiptShare.PDF))
        model.onAction(SaleReceiptAction.ShareFinished(shared = true))
        advanceUntilIdle()
        assertNull(model.ready().sharing)
        assertNull(model.ready().shareFailed)
    }

    private fun sale(voided: Boolean = false): SaleDetail {
        val at = Instant.fromEpochMilliseconds(1_793_198_520_000L)
        val date = BusinessDate(LocalDate(2026, 10, 28))
        return SaleDetail(
            sale = Sale(
                id = SALE_ID,
                content = NewSale(
                    netTotal = Money(5_000L),
                    taxTotal = Money.ZERO,
                    total = Money(5_000L),
                    totalCost = Money(COST),
                    totalReplacementCost = Money(COST),
                    orderAdjustment = Money.ZERO,
                    tipAmount = Money.ZERO,
                    cashRoundingAmount = Money.ZERO,
                    amountDue = Money(5_000L),
                    customerId = CUSTOMER_ID,
                    priceListId = 1L,
                    locationId = 1L,
                    cashSessionId = null,
                    quoteId = null,
                    orderId = null,
                    note = "Nota interna",
                    occurredAt = at,
                    businessDate = date,
                    createdAt = at,
                ),
                voiding = if (voided) Voiding(at, VoidReason.ENTRY_ERROR, null) else null,
            ),
            lines = listOf(
                SaleLine(
                    id = 1L,
                    saleId = SALE_ID,
                    content = NewSaleLine(
                        productId = 1L,
                        unitId = 1L,
                        unitFactor = QuantityMilli(1_000L),
                        enteredQuantity = QuantityMilli(1_000L),
                        quantity = QuantityMilli(1_000L),
                        unitPrice = Money(5_000L),
                        unitCost = Money(COST),
                        lineDiscount = Money.ZERO,
                        orderAdjustmentShare = Money.ZERO,
                        netAmount = Money(5_000L),
                        taxRate = BasisPoints.ZERO,
                        taxNameSnapshot = null,
                        taxAmount = Money.ZERO,
                        lineTotal = Money(5_000L),
                        lineCost = Money(COST),
                        unitReplacementCost = Money(COST),
                        lineReplacementCost = Money(COST),
                        productNameSnapshot = "Arroz",
                        stockEffect = LineStockEffect.DIRECT,
                        parentLineId = null,
                        priceListId = 1L,
                    ),
                ),
            ),
            payments = listOf(
                SalePayment(
                    id = 1L,
                    saleId = SALE_ID,
                    content = NewSalePayment(
                        paymentMethodId = 1L,
                        methodNameSnapshot = "Efectivo",
                        methodKind = PaymentMethodKind.CASH,
                        amount = Money(5_000L),
                        feeAmount = Money.ZERO,
                        settlementDate = date,
                        moneyAccountId = null,
                        createdAt = at,
                    ),
                ),
            ),
            archivedProductIds = emptySet(),
        )
    }

    private companion object {
        const val SALE_ID = 1_204L
        const val CUSTOMER_ID = 3L
        const val LOGO_ID = 9L
        const val COST = 777_777L
        val LOGO = StoredImage("a".repeat(64), "image/webp", 640, 320, 20_000)
    }
}

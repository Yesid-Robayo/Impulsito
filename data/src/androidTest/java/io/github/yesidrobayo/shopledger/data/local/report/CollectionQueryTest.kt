package io.github.yesidrobayo.shopledger.data.local.report

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.LedgerDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.customer.appendCustomerEntry
import io.github.yesidrobayo.shopledger.data.local.customer.customer
import io.github.yesidrobayo.shopledger.data.local.customer.customerEntry
import io.github.yesidrobayo.shopledger.data.local.payment.cardPayment
import io.github.yesidrobayo.shopledger.data.local.sale
import io.github.yesidrobayo.shopledger.data.repository.RoomCollectionReportRepository
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.report.DayCollection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Vendido y cobrado sobre la base real, con el ejemplo de la regla: una venta de noviembre con una
 * parte al contado y otra a crédito, y abonos en diciembre. Vendido es `amount_due`; lo cobrado al
 * vender no cuenta lo que quedó a crédito, y un abono anulado no cuenta. Cada valor está escrito a
 * mano. Montos y nombres inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class CollectionQueryTest : LedgerDatabaseTest() {
    private val reports by lazy { RoomCollectionReportRepository(database) }

    @Test
    fun theRuleExampleKeepsSoldAndCollectedApartInEachMonth() = runBlocking {
        assertFalse(reports.observeOnCredit().first())
        val customerId = database.customerDao().insert(customer("Cliente de prueba"))
        val cardId = requireNotNull(database.paymentMethodDao().findBySystemKey("CARD")).id

        // Noviembre: venta de 10000, 3000 con tarjeta y 7000 a crédito.
        val saleId = saleOf("2026-11-14", 10_000, customerId)
        database.salePaymentDao().insert(cardPayment(saleId, cardId).copy(amount = 3_000))
        database.salePaymentDao().insert(
            cardPayment(saleId, cardId).copy(amount = 7_000, methodKindSnapshot = "ON_ACCOUNT"),
        )
        database.appendCustomerEntry(
            customerEntry(customerId, "CREDIT_SALE", 7_000, 7_000, saleId = saleId)
                .copy(businessDate = "2026-11-14"),
        )
        // Una venta anulada no vende ni cobra.
        val voided = saleOf("2026-11-14", 50_000, customerId)
        database.salePaymentDao().insert(cardPayment(voided, cardId).copy(amount = 50_000))
        database.saleDao().markVoided(voided, CREATED_AT, "ENTRY_ERROR", null)

        // Diciembre: un abono de 4000 y otro de 1000 que se anuló al día siguiente.
        database.appendCustomerEntry(
            customerEntry(customerId, "PAYMENT", -4_000, 3_000).copy(businessDate = "2026-12-03"),
        )
        val wrong = database.appendCustomerEntry(
            customerEntry(customerId, "PAYMENT", -1_000, 2_000).copy(businessDate = "2026-12-03"),
        )
        database.appendCustomerEntry(
            customerEntry(customerId, "ENTRY_VOID", 1_000, 3_000, reversesEntryId = wrong)
                .copy(businessDate = "2026-12-04"),
        )

        assertTrue(reports.observeOnCredit().first())
        assertEquals(
            listOf(DayCollection(date("2026-11-14"), Money(10_000), Money(3_000), Money.ZERO)),
            reports.observeDaily(month(11)).first(),
        )
        assertEquals(
            listOf(DayCollection(date("2026-12-03"), Money.ZERO, Money.ZERO, Money(4_000))),
            reports.observeDaily(month(12)).first(),
        )
    }

    private suspend fun saleOf(date: String, amount: Long, customerId: Long): Long =
        database.saleDao().insert(
            sale(priceListId, businessDate = date).copy(
                netTotal = amount,
                taxTotal = 0,
                total = amount,
                orderAdjustment = 0,
                tipAmount = 0,
                cashRoundingAmount = 0,
                amountDue = amount,
                customerId = customerId,
            ),
        )

    private fun date(text: String) = requireNotNull(BusinessDate.parse(text))

    private fun month(number: Int) = ReportPeriod.Month(YearMonth(2026, number))
}

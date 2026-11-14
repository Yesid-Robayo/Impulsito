package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import io.github.yesidrobayo.shopledger.data.local.customer.customer
import io.github.yesidrobayo.shopledger.data.local.sale
import io.github.yesidrobayo.shopledger.data.local.saleReturn
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.document.FiscalMarkStatus
import io.github.yesidrobayo.shopledger.domain.model.document.FiscalSale
import io.github.yesidrobayo.shopledger.domain.model.document.SaleFiscalMark
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.usecase.document.MarkFiscalResult
import io.github.yesidrobayo.shopledger.domain.usecase.document.MarkSalesFiscalUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.document.ObserveFiscalMonthUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.document.ReferenceChange
import io.github.yesidrobayo.shopledger.domain.usecase.document.SaveFiscalPortalLinkUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.document.SavePortalLinkResult
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El puente fiscal en una base real: la lista del mes por marca con cliente y devolución, marcar
 * una o varias ventas sin tocar la venta y en una sola transacción, y el enlace que solo cambia su
 * columna. Todo local, con datos inventados y direcciones de `example.com`.
 */
@RunWith(AndroidJUnit4::class)
internal class FiscalBridgeRepositoryTest : RepositoryTest() {
    private val marks by lazy { RoomSaleFiscalMarkRepository(database) }
    private val mark by lazy { MarkSalesFiscalUseCase(runner, marks, Clock { AT }) }
    private val month by lazy { ObserveFiscalMonthUseCase(marks) }
    private val settings by lazy { RoomAppSettingsRepository(database, RestoreLock { false }) }
    private val saveLink by lazy { SaveFiscalPortalLinkUseCase(runner, settings, Clock { AT }) }

    @Test
    fun theMonthListsEachMarkWithCustomerAndReturnWithoutVoidedSales() = runBlocking {
        val customerId = database.customerDao().insert(customer("Cliente de prueba"))
        val withCustomer = database.saleDao().insert(
            sale(priceListId, businessDate = "2026-10-28").copy(customerId = customerId),
        )
        val returned = database.saleDao().insert(sale(priceListId, businessDate = "2026-10-27"))
        database.saleReturnDao().insert(saleReturn(returned))
        val recorded = database.saleDao().insert(sale(priceListId, businessDate = "2026-10-25"))
        val voided = database.saleDao().insert(sale(priceListId, businessDate = "2026-10-24"))
        database.saleDao().markVoided(voided, LATER, "ENTRY_ERROR", null)
        database.saleDao().insert(sale(priceListId, businessDate = "2026-11-01"))
        assertFalse(month(OCTOBER, FiscalMarkStatus.PENDING).first().hasMarks)

        assertEquals(
            MarkFiscalResult.Marked(1),
            mark(listOf(recorded), FiscalMarkStatus.RECORDED, ReferenceChange.Write("A-1043")),
        )

        val pending = month(OCTOBER, FiscalMarkStatus.PENDING).first()
        assertEquals(
            listOf(
                FiscalSale(
                    withCustomer,
                    date(28),
                    Money(11_900),
                    "Cliente de prueba",
                    FiscalMarkStatus.PENDING,
                    null,
                    hasReturn = false,
                ),
                FiscalSale(
                    returned,
                    date(27),
                    Money(11_900),
                    null,
                    FiscalMarkStatus.PENDING,
                    null,
                    hasReturn = true,
                ),
            ),
            pending.sales,
        )
        assertEquals(Money(23_800), pending.total)
        assertTrue(pending.hasMarks)
        val recordedList = month(OCTOBER, FiscalMarkStatus.RECORDED).first().sales
        assertEquals(
            listOf(recorded to "A-1043"),
            recordedList.map { it.saleId to it.externalReference },
        )
        assertNull(marks.observeSale(voided).first())
        assertEquals(FiscalMarkStatus.RECORDED, marks.observeSale(recorded).first()?.status)
    }

    @Test
    fun markingWritesOnlyTheMarkAndCountsOneChange() = runBlocking {
        val first = database.saleDao().insert(sale(priceListId))
        val second = database.saleDao().insert(sale(priceListId))
        val saleBefore = database.saleDao().findById(first)
        val before = changeSeq()

        assertEquals(
            MarkFiscalResult.Marked(2),
            mark(listOf(first, second), FiscalMarkStatus.NOT_NEEDED, ReferenceChange.Keep),
        )

        assertEquals(before + 1, changeSeq())
        assertEquals(saleBefore, database.saleDao().findById(first))
        assertEquals(
            SaleFiscalMark(second, FiscalMarkStatus.NOT_NEEDED, null, null),
            marks.observeMark(second).first(),
        )

        mark(listOf(first), FiscalMarkStatus.RECORDED, ReferenceChange.Write("B-7"))
        assertEquals(
            SaleFiscalMark(first, FiscalMarkStatus.RECORDED, "B-7", AT),
            marks.findMark(first),
        )
        assertEquals(DocumentStatus.ACTIVE, marks.findSaleStatus(first))
        assertNull(marks.findSaleStatus(MISSING_SALE))
    }

    @Test
    fun aVoidedSaleInTheSelectionLeavesNothingWritten() = runBlocking {
        val active = database.saleDao().insert(sale(priceListId))
        val voided = database.saleDao().insert(sale(priceListId))
        database.saleDao().markVoided(voided, LATER, "ENTRY_ERROR", null)
        val before = changeSeq()

        assertEquals(
            MarkFiscalResult.SaleUnavailable,
            mark(listOf(active, voided), FiscalMarkStatus.RECORDED, ReferenceChange.Keep),
        )

        assertNull(marks.findMark(active))
        assertEquals(before, changeSeq())
    }

    @Test
    fun theLinkChangesOnlyItsColumnAndTheSameLinkWritesNothing() = runBlocking {
        val row = requireNotNull(database.appSettingsDao().get())
        val before = changeSeq()

        assertEquals(SavePortalLinkResult.Saved(LINK), saveLink(LINK))

        assertEquals(
            row.copy(fiscalPortalUrl = LINK, updatedAt = AT.toEpochMilliseconds()),
            database.appSettingsDao().get(),
        )
        assertEquals(before + 1, changeSeq())
        assertEquals(SavePortalLinkResult.Saved(LINK), saveLink(LINK))
        assertEquals(SavePortalLinkResult.NotHttps, saveLink("http://example.com"))
        assertEquals(before + 1, changeSeq())

        assertEquals(SavePortalLinkResult.Saved(null), saveLink(""))
        assertNull(requireNotNull(database.appSettingsDao().get()).fiscalPortalUrl)
    }

    private fun date(day: Int) = BusinessDate(LocalDate(2026, 10, day))

    private companion object {
        const val LINK = "https://example.com/portal"
        const val MISSING_SALE = 999_999L
        val OCTOBER = YearMonth(2026, 10)
        val AT: Instant = Instant.fromEpochMilliseconds(1_793_000_000_000)
    }
}

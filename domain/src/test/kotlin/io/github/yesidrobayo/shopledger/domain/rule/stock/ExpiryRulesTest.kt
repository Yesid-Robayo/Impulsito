package io.github.yesidrobayo.shopledger.domain.rule.stock

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.DatedLotStock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** El aviso de vencimiento por lote, con el ejemplo de las reglas. Lotes y fechas inventados. */
class ExpiryRulesTest {
    private fun date(text: String) = requireNotNull(BusinessDate.parse(text))

    private fun lot(id: Long, expiry: String, noticeDays: Int? = null) = DatedLotStock(
        lotId = id,
        productId = 1L,
        productName = "Producto inventado",
        code = "L$id",
        expiryDate = date(expiry),
        onHand = QuantityMilli(3_000L),
        baseUnitId = 11L,
        noticeDays = noticeDays,
        expiryNotifiedAt = null,
    )

    @Test
    fun `with 30 days a lot expiring on 2026-11-20 is listed on 2026-11-01 at 19 days`() {
        val expiring = ExpiryRules.expiringOf(lot(1, "2026-11-20"), date("2026-11-01"), 30)

        assertEquals(19, expiring?.daysLeft)
        assertFalse(requireNotNull(expiring).isExpired)
    }

    @Test
    fun `it enters exactly on the last notice day and not the day before`() {
        val lot = lot(1, "2026-11-20")

        assertEquals(30, ExpiryRules.expiringOf(lot, date("2026-10-21"), 30)?.daysLeft)
        assertNull(ExpiryRules.expiringOf(lot, date("2026-10-20"), 30))
    }

    @Test
    fun `the product's own days come before the settings`() {
        assertEquals(3, ExpiryRules.noticeDaysOf(3, 30))
        assertEquals(30, ExpiryRules.noticeDaysOf(null, 30))
        // Con 3 días propios, a 10 días todavía no avisa aunque los ajustes digan 30.
        val ownDays = lot(1, "2026-11-11", noticeDays = 3)
        assertNull(ExpiryRules.expiringOf(ownDays, date("2026-11-01"), 30))
    }

    @Test
    fun `zero days lists it on the day it expires, and an expired lot stays marked`() {
        val today = ExpiryRules.expiringOf(lot(1, "2026-11-01"), date("2026-11-01"), 0)
        val expired = ExpiryRules.expiringOf(lot(1, "2026-10-28"), date("2026-11-01"), 0)

        assertEquals(0, today?.daysLeft)
        assertFalse(requireNotNull(today).isExpired)
        assertEquals(-4, expired?.daysLeft)
        assertTrue(requireNotNull(expired).isExpired)
    }

    @Test
    fun `the list goes from the soonest to the latest, by id on the same day`() {
        val lots = listOf(
            lot(3, "2026-11-05"),
            lot(2, "2026-11-03"),
            lot(1, "2026-11-05"),
            lot(4, "2027-01-01"),
        )

        val listed = ExpiryRules.expiringOf(lots, date("2026-11-01"), 30)

        assertEquals(listOf(2L, 1L, 3L), listed.map { it.lot.lotId })
    }

    @Test
    fun `the days left cross months and years`() {
        assertEquals(61, ExpiryRules.daysLeft(date("2027-01-01"), date("2026-11-01")))
        assertEquals(-1, ExpiryRules.daysLeft(date("2026-10-31"), date("2026-11-01")))
    }
}

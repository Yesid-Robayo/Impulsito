package io.github.yesidrobayo.shopledger.domain.rule.stock

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.LotPart
import io.github.yesidrobayo.shopledger.domain.model.stock.LotStock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Códigos, series y el reparto de una salida entre lotes, con los ejemplos de las reglas. */
class LotRulesTest {
    private fun lot(id: Long, units: Long, expiry: String? = null, unassigned: Boolean = false) =
        LotStock(
            lotId = id,
            code = if (unassigned) null else "L$id",
            expiryDate = expiry?.let { requireNotNull(BusinessDate.parse(it)) },
            isUnassigned = unassigned,
            onHand = QuantityMilli(units),
        )

    private fun part(lotId: Long?, milli: Long) = LotPart(lotId, QuantityMilli(milli))

    @Test
    fun `a code is trimmed, never blank and at most 60 characters`() {
        assertEquals(LotCodeCheck.Valid("A-118"), LotRules.checkCode("  A-118 "))
        assertEquals(LotCodeCheck.Blank, LotRules.checkCode("   "))
        assertEquals(LotCodeCheck.Valid("x".repeat(60)), LotRules.checkCode("x".repeat(60)))
        assertEquals(LotCodeCheck.TooLong(60), LotRules.checkCode("x".repeat(61)))
    }

    @Test
    fun `a serial is a lot of one whole unit`() {
        assertEquals(QuantityMilli(1_000L), LotRules.SERIAL_QUANTITY)
    }

    @Test
    fun `serial codes are checked one by one and never repeat`() {
        assertEquals(
            SerialCodesCheck.Valid(listOf("SN-1", "SN-2")),
            LotRules.checkSerialCodes(listOf(" SN-1", "SN-2 ")),
        )
        assertEquals(
            SerialCodesCheck.Invalid(1, LotCodeCheck.Blank),
            LotRules.checkSerialCodes(listOf("SN-1", " ")),
        )
        assertEquals(
            SerialCodesCheck.Invalid(0, LotCodeCheck.TooLong(60)),
            LotRules.checkSerialCodes(listOf("9".repeat(61))),
        )
        assertEquals(
            SerialCodesCheck.Repeated("SN-1"),
            LotRules.checkSerialCodes(listOf("SN-1", "SN-2", " SN-1")),
        )
        assertEquals(SerialCodesCheck.Valid(emptyList()), LotRules.checkSerialCodes(emptyList()))
    }

    @Test
    fun `the closest expiry leaves first, lots without expiry last and ties by age`() {
        val lots = listOf(
            lot(1, 2_000L),
            lot(2, 2_000L, "2026-11-28"),
            lot(3, 2_000L, "2026-11-04"),
            lot(4, 2_000L, "2026-11-28"),
            lot(5, 0L, "2026-11-01"),
            lot(6, -1_000L, "2026-11-01"),
            lot(7, 5_000L, unassigned = true),
        )

        val order = LotRules.exitOrder(lots).map { it.lotId }

        // Sin stock ni el sin asignar no se ofrecen: el sin asignar absorbe lo que falte.
        assertEquals(listOf(3L, 2L, 4L, 1L), order)
    }

    @Test
    fun `selling 6 from lots of 3 and 5 takes 3 and 3`() {
        val lots = listOf(lot(1, 3_000L, "2026-11-10"), lot(2, 5_000L, "2026-11-20"))

        assertEquals(
            listOf(part(1, 3_000L), part(2, 3_000L)),
            LotRules.splitOutflow(QuantityMilli(6_000L), lots, chosenLotId = null),
        )
    }

    @Test
    fun `selling 9 from lots of 3 and 5 takes 1 from the unassigned lot`() {
        val lots = listOf(
            lot(1, 3_000L, "2026-11-10"),
            lot(2, 5_000L, "2026-11-20"),
            lot(3, 0L, unassigned = true),
        )

        assertEquals(
            listOf(part(1, 3_000L), part(2, 5_000L), part(null, 1_000L)),
            LotRules.splitOutflow(QuantityMilli(9_000L), lots, chosenLotId = null),
        )
    }

    @Test
    fun `a chosen lot leaves first and the rest follows the exit order`() {
        val lots = listOf(lot(1, 3_000L, "2026-11-10"), lot(2, 5_000L, "2026-11-20"))

        assertEquals(
            listOf(part(2, 5_000L), part(1, 1_000L)),
            LotRules.splitOutflow(QuantityMilli(6_000L), lots, chosenLotId = 2L),
        )
        assertEquals(
            listOf(part(2, 2_000L)),
            LotRules.splitOutflow(QuantityMilli(2_000L), lots, chosenLotId = 2L),
        )
    }

    @Test
    fun `a chosen lot without stock is skipped and the unassigned lot takes everything`() {
        val lots = listOf(
            lot(1, 3_000L, "2026-11-10"),
            lot(2, 0L, "2026-11-01"),
            lot(9, -2_000L, unassigned = true),
        )

        assertEquals(
            listOf(part(1, 1_000L)),
            LotRules.splitOutflow(QuantityMilli(1_000L), lots, chosenLotId = 2L),
        )
        assertEquals(
            listOf(part(null, 4_000L)),
            LotRules.splitOutflow(QuantityMilli(4_000L), lots, chosenLotId = 9L),
        )
    }

    @Test
    fun `without lots in stock everything leaves the unassigned lot`() {
        assertEquals(
            listOf(part(null, 2_500L)),
            LotRules.splitOutflow(QuantityMilli(2_500L), emptyList(), chosenLotId = null),
        )
    }

    @Test
    fun `an outflow always takes something`() {
        assertThrows(IllegalArgumentException::class.java) {
            LotRules.splitOutflow(QuantityMilli.ZERO, emptyList(), chosenLotId = null)
        }
    }
}

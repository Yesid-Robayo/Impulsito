package io.github.yesidrobayo.shopledger.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lo que deja el escáner llega a la pantalla que lo abrió, en orden y una sola vez, y nunca a la
 * de otro propósito.
 */
class ScanHandoffTest {
    @Test
    fun codesArriveInOrderAndOnlyOnce() {
        val handoff = ScanHandoff()

        handoff.deliver(ScanDelivery(ScanPurpose.SALE, "2900000123458"))
        handoff.deliver(ScanDelivery(ScanPurpose.SALE, "P00000042"))

        assertEquals(listOf("2900000123458", "P00000042"), codes(handoff.take(ScanPurpose.SALE)))
        assertTrue(handoff.take(ScanPurpose.SALE).isEmpty())
        assertTrue(handoff.toTake.value.isEmpty())
    }

    @Test
    fun anotherPurposeKeepsItsCodes() {
        val handoff = ScanHandoff()

        handoff.deliver(ScanDelivery(ScanPurpose.PRODUCT_BARCODE, "P00000042"))
        handoff.deliver(ScanDelivery(ScanPurpose.SALE, "2900000123458"))

        assertEquals(listOf("2900000123458"), codes(handoff.take(ScanPurpose.SALE)))
        assertEquals(
            listOf(ScanDelivery(ScanPurpose.PRODUCT_BARCODE, "P00000042")),
            handoff.toTake.value,
        )
    }

    @Test
    fun theSameCodeTwiceArrivesTwice() {
        val handoff = ScanHandoff()

        handoff.deliver(ScanDelivery(ScanPurpose.SALE, "P00000042"))
        handoff.deliver(ScanDelivery(ScanPurpose.SALE, "P00000042"))

        assertEquals(listOf("P00000042", "P00000042"), codes(handoff.take(ScanPurpose.SALE)))
    }

    private fun codes(deliveries: List<ScanDelivery>) = deliveries.map { it.code }
}

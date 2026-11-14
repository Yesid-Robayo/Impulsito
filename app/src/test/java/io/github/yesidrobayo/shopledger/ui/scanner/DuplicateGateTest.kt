package io.github.yesidrobayo.shopledger.ui.scanner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La pausa entre lecturas iguales cuenta desde la última vez que se vio el código: un producto
 * quieto delante se suma una vez, y para sumarlo otra hay que sacarlo del cuadro.
 */
class DuplicateGateTest {
    private val ean = ScannedCode(ScanFormat.EAN_13, "2900000123458")
    private val own = ScannedCode(ScanFormat.CODE_128, "P00000042")

    @Test
    fun theFirstReadPasses() {
        assertTrue(DuplicateGate().accept(ean, atNanos = 0))
    }

    @Test
    fun aCodeHeldInFrontCountsOnce() {
        val gate = DuplicateGate()

        assertTrue(gate.accept(ean, 0))
        // Un cuadro cada 100 ms durante 5 s, con el código siempre a la vista.
        for (frame in 1..50) {
            assertFalse("cuadro $frame", gate.accept(ean, frame * MS_100))
        }
    }

    @Test
    fun theSameCodeCountsAgainAfterLeavingTheFrame() {
        val gate = DuplicateGate()

        assertTrue(gate.accept(ean, 0))
        assertFalse(gate.accept(ean, MS_100))
        // Fuera del cuadro un segundo y medio justo: vuelve a contar.
        assertTrue(gate.accept(ean, MS_100 + DuplicateGate.DEFAULT_WINDOW_NANOS))
    }

    @Test
    fun aGapShorterThanThePauseDoesNotCount() {
        val gate = DuplicateGate()

        assertTrue(gate.accept(ean, 0))
        assertFalse(gate.accept(ean, DuplicateGate.DEFAULT_WINDOW_NANOS - 1))
    }

    @Test
    fun anotherCodePassesAtOnceAndResetsThePause() {
        val gate = DuplicateGate()

        assertTrue(gate.accept(ean, 0))
        assertTrue(gate.accept(own, MS_100))
        assertTrue(gate.accept(ean, 2 * MS_100))
    }

    @Test
    fun theSameTextInAnotherFormatIsAnotherCode() {
        val gate = DuplicateGate()
        val asQr = ScannedCode(ScanFormat.QR_CODE, own.text)

        assertTrue(gate.accept(own, 0))
        assertTrue(gate.accept(asQr, MS_100))
    }

    private companion object {
        const val MS_100 = 100_000_000L
    }
}

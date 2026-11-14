package io.github.yesidrobayo.shopledger.data.print

import io.github.yesidrobayo.shopledger.domain.model.print.PrintFailure
import io.github.yesidrobayo.shopledger.domain.model.print.PrintResult
import io.github.yesidrobayo.shopledger.domain.testing.EscPosReader
import io.github.yesidrobayo.shopledger.domain.testing.FakeThermalPrinter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EscPosEncoderTest {
    @Test
    fun aSmallImageBecomesTheExactBytesByHand() {
        // Dos filas de 10 puntos: 2 bytes por fila, y el segundo lleva los dos últimos puntos.
        val raster = MonochromeRaster.fromRows(listOf("#.......##", ".#........"))

        val bytes = EscPosEncoder(feedLinesBeforeCut = 3).encode(listOf(raster))

        val expected = byteArrayOf(
            0x1B, 0x40,
            0x1D, 0x76, 0x30, 0x00, 0x02, 0x00, 0x02, 0x00,
            0x80.toByte(), 0xC0.toByte(),
            0x40, 0x00,
            0x1B, 0x64, 0x03,
            0x1D, 0x56, 0x42, 0x00,
        )
        assertArrayEquals(expected, bytes)
    }

    @Test
    fun bothPaperWidthsComeBackWithTheirExactWidthAndEveryDot() {
        for (dots in listOf(384, 576)) {
            val rows = List(40) { y ->
                String(CharArray(dots) { x -> if ((x + y) % 7 == 0) '#' else '.' })
            }
            val raster = MonochromeRaster.fromRows(rows)

            val printed = EscPosReader.read(EscPosEncoder().encode(listOf(raster)))

            assertEquals(1, printed.size)
            assertEquals(dots, printed.single().width)
            assertEquals(rows, printed.single().rows())
        }
    }

    @Test
    fun aTallImageGoesInBandsAndComesBackWhole() {
        val rows = List(600) { y -> if (y % 2 == 0) "#.#.#.#." else ".#.#.#.#" }

        val bytes = EscPosEncoder(bandHeight = 255).encode(listOf(MonochromeRaster.fromRows(rows)))

        // 255, 255 y 90 filas: tres cabeceras de ráster.
        val headers = bytes.toList().windowed(3).count { it == listOf<Byte>(0x1D, 0x76, 0x30) }
        assertEquals(3, headers)
        assertEquals(rows, EscPosReader.read(bytes).single().rows())
    }

    @Test
    fun severalLabelsGoInOneJobWithOneCutEach() {
        val first = MonochromeRaster.fromRows(listOf("##..", "..##"))
        val second = MonochromeRaster.fromRows(listOf("#.#.", ".#.#", "####"))

        val bytes = EscPosEncoder().encode(listOf(first, second))

        val printed = EscPosReader.read(bytes)
        assertEquals(2, printed.size)
        // El ancho se redondea a bytes enteros: los puntos de relleno salen en blanco.
        assertEquals(listOf("##......", "..##...."), printed[0].rows())
        assertEquals(listOf("#.#.....", ".#.#....", "####...."), printed[1].rows())
        assertEquals(1, bytes.toList().windowed(2).count { it == listOf<Byte>(0x1B, 0x40) })
    }

    @Test
    fun copiesRepeatTheSameImageWithOneCutEach() {
        val label = MonochromeRaster.fromRows(listOf("#.#.#.#.", "########"))
        val job = EscPosEncoder().Job()

        job.add(label, copies = 3)

        val printed = EscPosReader.read(job.bytes())
        assertEquals(3, printed.size)
        printed.forEach { assertEquals(listOf("#.#.#.#.", "########"), it.rows()) }
        assertThrows(IllegalArgumentException::class.java) { job.add(label, copies = 0) }
        assertThrows(IllegalStateException::class.java) { EscPosEncoder().Job().bytes() }
    }

    @Test
    fun anEmptyJobAndOutOfRangeSettingsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { EscPosEncoder().encode(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { EscPosEncoder(bandHeight = 0) }
        assertThrows(IllegalArgumentException::class.java) {
            EscPosEncoder(feedLinesBeforeCut = 256)
        }
    }

    @Test
    fun theFakePrinterRecordsEachJobAndFailsWithoutRecording() = runBlocking {
        val printer = FakeThermalPrinter()
        val bytes = EscPosEncoder().encode(listOf(MonochromeRaster.fromRows(listOf("#"))))

        assertEquals(PrintResult.Sent(bytes.size), printer.send(bytes))
        printer.failure = PrintFailure.WRITE_FAILED
        assertEquals(PrintResult.Failed(PrintFailure.WRITE_FAILED), printer.send(bytes))

        assertEquals(1, printer.jobs.size)
        assertEquals(listOf("#......."), printer.printedImages().single().rows())
    }
}

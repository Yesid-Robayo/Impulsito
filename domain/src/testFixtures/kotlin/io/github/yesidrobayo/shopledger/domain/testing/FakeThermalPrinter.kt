package io.github.yesidrobayo.shopledger.domain.testing

import io.github.yesidrobayo.shopledger.domain.model.print.PrintFailure
import io.github.yesidrobayo.shopledger.domain.model.print.PrintResult
import io.github.yesidrobayo.shopledger.domain.port.PrinterPort

/**
 * Una impresora térmica falsa: implementa [PrinterPort] y graba los bytes ESC/POS de cada trabajo.
 * Ninguna prueba necesita una impresora ni Bluetooth. Con [failure], cada envío falla con ese
 * motivo, como una impresora sin papel o que se desconecta a mitad, y no graba nada.
 */
class FakeThermalPrinter(var failure: PrintFailure? = null) : PrinterPort {
    private val recorded = mutableListOf<ByteArray>()

    /** Los trabajos recibidos, en orden. */
    val jobs: List<ByteArray> get() = recorded.map { it.copyOf() }

    override suspend fun send(payload: ByteArray): PrintResult {
        failure?.let { return PrintResult.Failed(it) }
        recorded += payload.copyOf()
        return PrintResult.Sent(payload.size)
    }

    /** Las imágenes que saldrían en papel con el último trabajo, una por corte. */
    fun printedImages(): List<PrintedImage> = EscPosReader.read(recorded.last())
}

/** Una imagen impresa, tal como la reconstruyen los bytes: cada punto es negro o blanco. */
class PrintedImage(val width: Int, val height: Int, private val black: BooleanArray) {
    fun isBlack(x: Int, y: Int): Boolean = black[y * width + x]

    /** Las filas como texto, `#` para negro y `.` para blanco: sirve para comparar a mano. */
    fun rows(): List<String> = List(height) { y ->
        String(CharArray(width) { x -> if (isBlack(x, y)) '#' else '.' })
    }
}

/**
 * Lee un trabajo ESC/POS como lo haría la impresora: `ESC @` al comienzo, franjas `GS v 0` que se
 * apilan en una imagen, `ESC d n` que avanza y `GS V 66 0` que corta y cierra la imagen. Cualquier
 * otro byte es un error: así una prueba atrapa un comando de más, como uno de página de códigos.
 */
object EscPosReader {
    fun read(job: ByteArray): List<PrintedImage> {
        check(job.size >= 2 && job[0] == ESC && job[1] == AT) { "El trabajo no empieza con ESC @" }
        val images = mutableListOf<PrintedImage>()
        var width = -1
        val rows = mutableListOf<BooleanArray>()
        var index = 2
        while (index < job.size) {
            val command = job[index]
            val next = job.getOrNull(index + 1)
            when {
                command == GS && next == RASTER -> {
                    check(job[index + 2] == '0'.code.toByte()) { "Ráster que no es GS v 0" }
                    check(job[index + 3] == 0.toByte()) { "Ráster que no está en modo normal" }
                    val bytesPerRow = unsigned(job[index + 4]) or (unsigned(job[index + 5]) shl 8)
                    val height = unsigned(job[index + 6]) or (unsigned(job[index + 7]) shl 8)
                    index += RASTER_HEADER
                    check(width == -1 || width == bytesPerRow * 8) { "Franjas de distinto ancho" }
                    width = bytesPerRow * 8
                    repeat(height) {
                        val row = BooleanArray(width) { x ->
                            val bits = unsigned(job[index + x / 8])
                            bits and (0x80 ushr (x % 8)) != 0
                        }
                        rows += row
                        index += bytesPerRow
                    }
                }

                command == ESC && next == FEED -> index += 3

                command == GS && next == CUT -> {
                    check(job[index + 2] == PARTIAL_CUT && job[index + 3] == 0.toByte()) {
                        "Corte desconocido"
                    }
                    check(rows.isNotEmpty()) { "Corte sin imagen" }
                    val black = BooleanArray(width * rows.size)
                    rows.forEachIndexed { y, row -> row.copyInto(black, y * width) }
                    images += PrintedImage(width, rows.size, black)
                    rows.clear()
                    width = -1
                    index += 4
                }

                else -> error("Comando ESC/POS inesperado en la posición $index")
            }
        }
        check(rows.isEmpty()) { "Una imagen quedó sin corte" }
        return images
    }

    private fun unsigned(byte: Byte): Int = byte.toInt() and 0xFF

    private const val ESC: Byte = 0x1B
    private const val GS: Byte = 0x1D
    private const val AT: Byte = 0x40
    private const val RASTER: Byte = 0x76
    private const val FEED: Byte = 0x64
    private const val CUT: Byte = 0x56
    private const val PARTIAL_CUT: Byte = 66
    private const val RASTER_HEADER = 8
}

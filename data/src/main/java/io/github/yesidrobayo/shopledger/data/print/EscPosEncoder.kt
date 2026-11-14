package io.github.yesidrobayo.shopledger.data.print

import java.io.ByteArrayOutputStream

/**
 * Convierte imágenes de un bit en bytes ESC/POS: inicializa, manda cada imagen como ráster con
 * `GS v 0`, avanza el papel y corta. No usa páginas de códigos: todo el texto ya va dibujado en la
 * imagen, así que la eñe, la cedilla o cualquier alfabeto salen igual en cualquier impresora.
 *
 * Cada imagen sale en franjas de [bandHeight] filas como máximo: algunas impresoras baratas tienen
 * un búfer chico y deforman una imagen alta enviada en un solo comando. Varias imágenes van en un
 * solo trabajo, cada una con su avance y su corte, para conectar una sola vez.
 */
class EscPosEncoder(
    private val bandHeight: Int = DEFAULT_BAND_HEIGHT,
    private val feedLinesBeforeCut: Int = DEFAULT_FEED_LINES,
) {
    init {
        require(bandHeight in 1..MAX_RASTER_HEIGHT) { "Alto de franja fuera de rango" }
        require(feedLinesBeforeCut in 0..MAX_FEED_LINES) { "Avance fuera de rango" }
    }

    /** Un trabajo entero con [rasters], cada una con su corte. */
    fun encode(rasters: List<MonochromeRaster>): ByteArray {
        require(rasters.isNotEmpty()) { "Un trabajo sin imágenes no imprime nada" }
        val job = Job()
        rasters.forEach { job.add(it) }
        return job.bytes()
    }

    /**
     * Un trabajo que se arma de a una imagen: así cada etiqueta se dibuja, se agrega y se suelta,
     * y nunca están todas en memoria a la vez.
     */
    inner class Job {
        private val out = ByteArrayOutputStream().apply { write(INITIALIZE) }
        private var images = 0

        /** Agrega [raster] [copies] veces, cada una con su avance y su corte. */
        fun add(raster: MonochromeRaster, copies: Int = 1) {
            require(raster.bytesPerRow <= MAX_BYTES_PER_ROW) { "Imagen demasiado ancha" }
            require(copies > 0) { "Al menos una copia" }
            repeat(copies) {
                var top = 0
                while (top < raster.height) {
                    val rows = minOf(bandHeight, raster.height - top)
                    writeBand(out, raster, top, rows)
                    top += rows
                }
                // Sin cortadora, la impresora ignora el corte, y el avance deja el final fuera.
                out.write(byteArrayOf(ESC, 'd'.code.toByte(), feedLinesBeforeCut.toByte()))
                out.write(PARTIAL_CUT_AFTER_FEED)
            }
            images += copies
        }

        fun bytes(): ByteArray {
            check(images > 0) { "Un trabajo sin imágenes no imprime nada" }
            return out.toByteArray()
        }
    }

    private fun writeBand(
        out: ByteArrayOutputStream,
        raster: MonochromeRaster,
        top: Int,
        rows: Int,
    ) {
        val bytesPerRow = raster.bytesPerRow
        out.write(
            byteArrayOf(
                GS,
                'v'.code.toByte(),
                '0'.code.toByte(),
                // Modo normal: un punto de la imagen es un punto del papel.
                0,
                (bytesPerRow and 0xFF).toByte(),
                (bytesPerRow shr 8).toByte(),
                (rows and 0xFF).toByte(),
                (rows shr 8).toByte(),
            ),
        )
        val row = ByteArray(bytesPerRow)
        for (y in top until top + rows) {
            row.fill(0)
            for (x in 0 until raster.width) {
                if (raster.isBlack(x, y)) {
                    // El bit más alto es el punto de la izquierda; 1 imprime.
                    val index = x / 8
                    row[index] = (row[index].toInt() or (0x80 ushr (x % 8))).toByte()
                }
            }
            out.write(row)
        }
    }

    companion object {
        /** Franjas de menos de 256 filas: el alto cabe en un byte y lo aceptan todas. */
        const val DEFAULT_BAND_HEIGHT = 255
        const val DEFAULT_FEED_LINES = 4

        /** Máximo de filas por comando que admite `GS v 0` en la mayoría de los modelos. */
        const val MAX_RASTER_HEIGHT = 2303

        private const val MAX_FEED_LINES = 255
        private const val MAX_BYTES_PER_ROW = 0xFFFF
        private const val ESC: Byte = 0x1B
        private const val GS: Byte = 0x1D

        /** `ESC @`: borra el estado de la impresora antes de cada trabajo. */
        val INITIALIZE = byteArrayOf(ESC, '@'.code.toByte())

        /** `GS V 66 0`: avanza hasta la cuchilla y corta dejando una pestaña. */
        val PARTIAL_CUT_AFTER_FEED = byteArrayOf(GS, 'V'.code.toByte(), 66, 0)
    }
}

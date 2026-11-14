package io.github.yesidrobayo.shopledger.data.print

import android.graphics.Bitmap
import io.github.yesidrobayo.shopledger.domain.model.print.PrintResult
import io.github.yesidrobayo.shopledger.domain.port.PrinterPort
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext

/**
 * Imprime imágenes dibujadas por la app en una térmica: las pasa a un bit, las codifica en ESC/POS
 * como un solo trabajo y las envía por [PrinterPort], con una sola conexión.
 *
 * Cada imagen se dibuja, se pasa a un bit y se suelta antes de la siguiente: con cientos de
 * etiquetas, nunca están todas en memoria. Dibujar y codificar ocupa la CPU, así que corre en
 * [cpuContext], fuera del hilo principal.
 */
class ThermalImagePrinter(
    private val cpuContext: CoroutineContext,
    private val encoder: EscPosEncoder = EscPosEncoder(),
) {
    /**
     * Imprime [copies] de la imagen que [render] dibuja para cada uno de [items]. [render]
     * devuelve un `Bitmap` nuevo, que esta clase recicla al terminar con él.
     */
    suspend fun <T> printImages(
        printer: PrinterPort,
        items: List<T>,
        copies: (T) -> Int,
        render: (T) -> Bitmap,
    ): PrintResult {
        require(items.isNotEmpty()) { "Un trabajo sin imágenes no imprime nada" }
        val payload = withContext(cpuContext) {
            val job = encoder.Job()
            for (item in items) {
                val bitmap = render(item)
                val raster =
                    try {
                        bitmap.toMonochromeRaster()
                    } finally {
                        bitmap.recycle()
                    }
                job.add(raster, copies(item))
            }
            job.bytes()
        }
        return printer.send(payload)
    }
}

package io.github.yesidrobayo.shopledger.ui.scanner

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import zxingcpp.BarcodeReader

/** Un código leído: su formato y su texto. Es lo único que sale del escáner, nunca el cuadro. */
data class ScannedCode(val format: ScanFormat, val text: String)

/**
 * Lo que dejó un cuadro: cuándo llegó, cuánto tardó la lectura y el código, si trajo uno. Los
 * tiempos son de un reloj monótono, en nanosegundos.
 */
data class FrameOutcome(val arrivedAtNanos: Long, val decodeNanos: Long, val code: ScannedCode?)

/**
 * Lee cada cuadro de la cámara en memoria con zxing-cpp.
 *
 * El cuadro no se copia, no se convierte en imagen y no se escribe en disco: zxing-cpp lee el
 * plano de luminancia directamente del búfer de la cámara. Lo cierra siempre, también si la
 * lectura falla, para que la cámara entregue el siguiente.
 *
 * @param onFrame recibe cada cuadro leído, en el hilo del análisis.
 * @param nanoTime el reloj monótono de los tiempos; las pruebas lo cambian.
 */
class BarcodeFrameAnalyzer(
    private val onFrame: (FrameOutcome) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
) : ImageAnalysis.Analyzer {
    // Se crea con el primer cuadro, en el hilo del análisis: cargar la biblioteca nativa lee
    // disco.
    private var reader: BarcodeReader? = null

    override fun analyze(image: ImageProxy) {
        val arrivedAt = nanoTime()
        try {
            val activeReader =
                reader ?: BarcodeReader(scannerReaderOptions()).also { reader = it }
            val readStart = nanoTime()
            val results = activeReader.read(image)
            val decodeNanos = nanoTime() - readStart
            val code =
                results.firstNotNullOfOrNull { result ->
                    val format = ScanFormat.fromNative(result.format)
                    val text = result.text
                    if (format == null || text.isNullOrBlank()) null else ScannedCode(format, text)
                }
            onFrame(FrameOutcome(arrivedAt, decodeNanos, code))
        } finally {
            image.close()
        }
    }
}

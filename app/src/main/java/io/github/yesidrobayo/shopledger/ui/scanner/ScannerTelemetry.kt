package io.github.yesidrobayo.shopledger.ui.scanner

/**
 * Lo que el escáner cuenta de cada sesión para el registro: tiempos, conteos y estados. Los tipos
 * no dejan pasar el texto de un código ni un cuadro.
 */
interface ScannerTelemetry {
    fun permissionAnswered(granted: Boolean)

    /** La cámara entregó cuadros [millis] después de pedirla. */
    fun cameraOpened(millis: Long)

    /** La cámara no abrió; de [failure] solo se registra la clase. */
    fun cameraFailed(failure: Throwable)

    /** El teléfono no tiene cámara trasera. */
    fun cameraMissing()

    /** El primer código de la sesión, [millis] después de pedir la cámara, y en qué cuadro. */
    fun firstRead(millis: Long, frame: Int, format: ScanFormat)

    fun sessionClosed(frames: Int, reads: Int, maxDecodeMillis: Long)
}

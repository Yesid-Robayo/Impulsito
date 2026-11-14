package io.github.yesidrobayo.shopledger.di

import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.LogEvent
import io.github.yesidrobayo.shopledger.domain.port.LogField
import io.github.yesidrobayo.shopledger.domain.port.info
import io.github.yesidrobayo.shopledger.domain.port.warn
import io.github.yesidrobayo.shopledger.ui.scanner.ScanFormat
import io.github.yesidrobayo.shopledger.ui.scanner.ScannerTelemetry

/**
 * Los tiempos del escáner en el registro de la app. Con ellos se mide en un teléfono cuánto tarda
 * en leer, sin mirar la pantalla. Solo números y estados: nunca el texto de un código.
 */
class ScannerTelemetryLog(private val log: EventLog) : ScannerTelemetry {
    override fun permissionAnswered(granted: Boolean) = log.info(
        ScannerEvent.PERMISSION_ANSWERED,
        LogField.count("concedido", if (granted) 1 else 0),
    )

    override fun cameraOpened(millis: Long) =
        log.info(ScannerEvent.CAMERA_OPENED, LogField.durationMillis("desde_pedir", millis))

    override fun cameraFailed(failure: Throwable) = log.warn(ScannerEvent.CAMERA_FAILED, failure)

    override fun cameraMissing() = log.warn(ScannerEvent.CAMERA_MISSING)

    override fun firstRead(millis: Long, frame: Int, format: ScanFormat) = log.info(
        ScannerEvent.FIRST_READ,
        LogField.durationMillis("desde_pedir", millis),
        LogField.count("cuadro", frame.toLong()),
        LogField.state("formato", format),
    )

    override fun sessionClosed(frames: Int, reads: Int, maxDecodeMillis: Long) = log.info(
        ScannerEvent.SESSION_CLOSED,
        LogField.count("cuadros", frames.toLong()),
        LogField.count("lecturas", reads.toLong()),
        LogField.durationMillis("lectura_maxima", maxDecodeMillis),
    )
}

/** Lo que registra el escáner. Cada texto es fijo, y ninguna línea lleva el texto de un código. */
internal enum class ScannerEvent(override val text: String) : LogEvent {
    PERMISSION_ANSWERED("Respuesta al permiso de cámara"),
    CAMERA_OPENED("Cámara del escáner abierta"),
    CAMERA_FAILED("La cámara del escáner no abrió"),
    CAMERA_MISSING("El teléfono no tiene cámara trasera"),
    FIRST_READ("Primera lectura del escáner"),
    SESSION_CLOSED("Sesión del escáner cerrada"),
}

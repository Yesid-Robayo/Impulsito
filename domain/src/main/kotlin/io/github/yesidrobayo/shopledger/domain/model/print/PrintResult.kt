package io.github.yesidrobayo.shopledger.domain.model.print

/**
 * El ancho del papel térmico y los puntos que imprime una cabeza de 203 ppp, 8 puntos por
 * milímetro. Son los anchos habituales de 58 y 80 mm; cada modelo real se confirma en el piloto.
 */
enum class PaperWidth(val millimeters: Int, val dots: Int) {
    MM_58(millimeters = 58, dots = 384),
    MM_80(millimeters = 80, dots = 576),
}

/** Por qué no salió un trabajo de impresión. Ninguno toca lo que ya se guardó. */
enum class PrintFailure {
    /** El teléfono no tiene Bluetooth. */
    NO_ADAPTER,

    /** Falta el permiso para usar dispositivos emparejados. */
    PERMISSION_MISSING,

    /** Bluetooth está apagado. */
    BLUETOOTH_OFF,

    /** La impresora ya no figura entre los dispositivos emparejados. */
    NOT_BONDED,

    /** No se pudo abrir el canal: apagada, lejos o sin el servicio de puerto serie. */
    CONNECT_FAILED,

    /** El canal se cortó a mitad del envío, por ejemplo sin papel o al apagarse. */
    WRITE_FAILED,
}

/** Cómo termina un envío a la impresora. */
sealed interface PrintResult {
    /** Los [bytes] salieron del teléfono. No confirma que el papel salió bien. */
    data class Sent(val bytes: Int) : PrintResult

    data class Failed(val reason: PrintFailure) : PrintResult
}

/**
 * Una impresora que la persona ya emparejó en los ajustes del teléfono. [name] puede faltar: el
 * sistema no siempre lo conoce. [address] identifica al dispositivo y nunca se registra.
 */
data class PairedPrinter(val name: String?, val address: String)

/** Las impresoras emparejadas, o por qué no se pueden leer. */
sealed interface PairedPrintersResult {
    data class Listed(val printers: List<PairedPrinter>) : PairedPrintersResult

    data class Unavailable(val reason: PrintFailure) : PairedPrintersResult
}

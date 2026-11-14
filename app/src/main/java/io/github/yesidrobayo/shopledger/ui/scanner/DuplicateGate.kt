package io.github.yesidrobayo.shopledger.ui.scanner

/**
 * Deja pasar un código y descarta el mismo código mientras siga a la vista.
 *
 * La pausa cuenta desde la última vez que se vio el código, no desde que se aceptó: un producto
 * quieto delante de la cámara se suma una sola vez, por mucho que dure ahí. Para sumarlo otra vez
 * hay que sacarlo del cuadro al menos [windowNanos]. Un código distinto pasa enseguida.
 *
 * La pausa de un segundo y medio es de diseño. Los tiempos son de un reloj monótono.
 */
class DuplicateGate(private val windowNanos: Long = DEFAULT_WINDOW_NANOS) {
    private var last: ScannedCode? = null
    private var lastSeenAt = 0L

    /** Si [code], visto en [atNanos], cuenta como una lectura nueva. */
    fun accept(code: ScannedCode, atNanos: Long): Boolean {
        val repeated = code == last && atNanos - lastSeenAt < windowNanos
        last = code
        lastSeenAt = atNanos
        return !repeated
    }

    companion object {
        /** Un segundo y medio. */
        const val DEFAULT_WINDOW_NANOS = 1_500_000_000L
    }
}

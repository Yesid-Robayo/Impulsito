package io.github.yesidrobayo.shopledger.data.safety

import java.util.Collections
import java.util.IdentityHashMap

/**
 * Convierte una excepción en texto con la forma de una pila de Java, para que `retrace` la lea con
 * `mapping.txt`.
 *
 * Solo usa la clase de cada excepción y sus marcos. Nunca lee `message`, `localizedMessage` ni
 * `toString()`: el mensaje puede traer la URL del destino, un nombre o un monto, y Android copia el
 * mensaje de la causa en el de la excepción que la envuelve al fallar `Application.onCreate` o una
 * actividad.
 */
internal object CrashReportFormatter {
    /** Marcos por excepción. Lo de más abajo casi siempre es código del sistema. */
    const val MAX_FRAMES = 128

    /** Profundidad máxima de causas y suprimidas. */
    const val MAX_DEPTH = 8

    /** Excepciones suprimidas por cada excepción. */
    const val MAX_SUPPRESSED = 4

    /** Línea final cuando el texto no cupo en el tamaño pedido. */
    const val TRUNCATED_MARK = "truncated=true"

    fun format(error: Throwable, maxBytes: Int): String {
        val out = BoundedText(maxBytes)
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        append(out, error, prefix = "", indent = "", depth = 0, seen = seen)
        return out.finish()
    }

    private fun append(
        out: BoundedText,
        error: Throwable,
        prefix: String,
        indent: String,
        depth: Int,
        seen: MutableSet<Throwable>,
    ) {
        val className = error.javaClass.name
        if (!seen.add(error)) {
            // Una causa que vuelve a una excepción ya escrita: se corta, como hace Java.
            out.line("$indent$prefix[CIRCULAR REFERENCE: $className]")
            return
        }
        out.line("$indent$prefix$className")
        val frames = error.stackTrace
        frames.take(MAX_FRAMES).forEach { out.line("$indent\tat ${frameText(it)}") }
        if (frames.size > MAX_FRAMES) out.line("$indent\t... ${frames.size - MAX_FRAMES} more")
        if (depth >= MAX_DEPTH) return
        val nestedIndent = "$indent\t"
        error.suppressed.take(MAX_SUPPRESSED).forEach {
            append(
                out,
                it,
                prefix = "Suppressed: ",
                indent = nestedIndent,
                depth = depth + 1,
                seen = seen,
            )
        }
        error.cause?.let {
            append(out, it, prefix = "Caused by: ", indent = indent, depth = depth + 1, seen = seen)
        }
    }

    private fun frameText(frame: StackTraceElement): String {
        val fileName = frame.fileName
        val location =
            when {
                frame.isNativeMethod -> "Native Method"
                fileName == null -> "Unknown Source"
                frame.lineNumber >= 0 -> "$fileName:${frame.lineNumber}"
                else -> fileName
            }
        return "${frame.className}.${frame.methodName}($location)"
    }

    /** Acumula líneas hasta un tamaño en bytes, y marca el corte si no cupo todo. */
    private class BoundedText(private val maxBytes: Int) {
        private val text = StringBuilder()
        private var bytes = 0
        private var truncated = false

        fun line(value: String) {
            if (truncated) return
            val size = value.toByteArray(Charsets.UTF_8).size + 1
            if (bytes + size > maxBytes - TRUNCATED_MARK.length - 1) {
                truncated = true
                return
            }
            text.append(value).append('\n')
            bytes += size
        }

        fun finish(): String {
            if (truncated) text.append(TRUNCATED_MARK).append('\n')
            return text.toString()
        }
    }
}

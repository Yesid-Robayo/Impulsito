package io.github.yesidrobayo.shopledger.domain.port

/**
 * Una línea lista para el registro: el texto del evento, sus datos y, si hubo una excepción, solo
 * su clase.
 *
 * Se arma solo dentro de este módulo, con las funciones de [EventLog]: quien escribe el registro la
 * recibe hecha y no puede agregarle nada.
 *
 * - **De una excepción, solo la clase.** Nunca su mensaje, su causa ni su traza: el mensaje de
 *   SQLite trae la sentencia y sus valores, y el de una conexión, la dirección del servidor.
 * - **El código de una falla de la base.** De [StorageFailureException] se agrega su tipo, que ya es
 *   un código sin datos: así se distingue un disco lleno de una base dañada.
 */
class LogLine internal constructor(event: LogEvent, fields: List<LogField>, failure: Throwable?) {
    /** El texto de la línea: `Base abierta: duración=12 ms`. */
    val text: String = buildText(event, fields, failure)

    override fun toString(): String = text

    private companion object {
        fun buildText(event: LogEvent, fields: List<LogField>, failure: Throwable?): String {
            val all = if (failure == null) fields else fields + failureFields(failure)
            if (all.isEmpty()) return event.text
            return all.joinToString(", ", prefix = "${event.text}: ") { it.render() }
        }

        fun failureFields(failure: Throwable): List<LogField> {
            val errorClass = LogField.failureClass(failure)
            return if (failure is StorageFailureException) {
                listOf(errorClass, LogField.state("tipo", failure.failure))
            } else {
                listOf(errorClass)
            }
        }
    }
}

package io.github.yesidrobayo.shopledger.domain.port

/**
 * Un dato de una línea del registro: un nombre y un valor que nunca es texto libre.
 *
 * Solo se crea con las funciones de [Companion], y cada una admite un tipo que no puede llevar datos
 * del negocio: conteos, duraciones, identificadores numéricos internos, códigos y estados. Ninguna
 * admite un nombre, una nota ni el mensaje de una excepción. Un monto o una cantidad también son
 * números, pero son datos del negocio: nunca se pasan a [count] ni a [id].
 *
 * El nombre del dato se escribe en el código, como `intentos` o `duración`: solo letras minúsculas
 * y guion bajo, hasta [MAX_NAME_LENGTH] caracteres. Un nombre con otra cosa, que podría venir de un
 * dato, se registra como [INVALID_NAME], sin su texto, en vez de detener la app.
 */
class LogField private constructor(name: String, private val value: String) {
    /** El nombre que se registra. */
    val name: String = if (isSafeName(name)) name else INVALID_NAME

    /** El dato tal como sale en la línea: `intentos=3`. */
    internal fun render(): String = "$name=$value"

    companion object {
        /** El nombre que reemplaza a uno que no cumple la forma. */
        const val INVALID_NAME = "campo"

        /** El largo máximo del nombre de un dato. */
        const val MAX_NAME_LENGTH = 32

        /** El nombre que se registra para una excepción de una clase sin nombre. */
        const val UNNAMED_FAILURE = "Throwable"

        /** Cuántas veces pasó algo, o cuántos elementos hay. Nunca un monto ni una cantidad. */
        fun count(name: String, value: Long): LogField = LogField(name, value.toString())

        /** Cuánto tardó algo, en milisegundos. */
        fun durationMillis(name: String, millis: Long): LogField = LogField(name, "$millis ms")

        /** El identificador numérico interno de una fila: nunca dice nada de su contenido. */
        fun id(name: String, value: Long): LogField = LogField(name, value.toString())

        /** Un código numérico, como el resultado de SQLite. */
        fun code(name: String, value: Int): LogField = LogField(name, value.toString())

        /** El código de una respuesta HTTP. Nunca la URL ni el cuerpo. */
        fun httpStatus(status: Int): LogField = LogField("http", status.toString())

        /** Un estado, por el nombre de su valor en el código, como `PERMISSION_LOST`. */
        fun state(name: String, value: Enum<*>): LogField = LogField(name, value.name)

        /**
         * La clase de [failure], con su paquete, como dato `error`. Nunca el mensaje ni la causa.
         * Una clase sin nombre, como un objeto anónimo, sale como [UNNAMED_FAILURE].
         */
        internal fun failureClass(failure: Throwable): LogField =
            LogField("error", failureClassName(failure))

        /** El nombre de la clase de [failure], o [UNNAMED_FAILURE] si no tiene. */
        internal fun failureClassName(failure: Throwable): String =
            failure::class.qualifiedName ?: UNNAMED_FAILURE

        private fun isSafeName(name: String): Boolean = name.length in 1..MAX_NAME_LENGTH &&
            name.all { it == '_' || (it.isLetter() && it.isLowerCase()) }
    }
}

package io.github.yesidrobayo.shopledger.data.local.guard

/**
 * Un lector de JSON mínimo, para el esquema que exporta Room. Las pruebas en la JVM no tienen el
 * `org.json` de Android, que ahí es un esqueleto vacío, y una biblioteca de JSON sería una
 * dependencia más para leer un solo archivo.
 *
 * Devuelve `Map<String, Any?>` para un objeto, `List<Any?>` para una lista, `String`, `Long` o
 * `Double` para un número, `Boolean` y `null`. Un texto que no es JSON lanza
 * [IllegalArgumentException] con la posición.
 */
internal object JsonText {
    fun parse(text: String): Any? {
        val reader = Reader(text)
        val value = reader.value()
        reader.skipSpaces()
        require(reader.atEnd()) { "Sobra texto después del JSON en ${reader.position}" }
        return value
    }

    private class Reader(private val text: String) {
        var position = 0
            private set

        fun atEnd(): Boolean = position == text.length

        fun skipSpaces() {
            while (!atEnd() && text[position] in " \t\r\n") position++
        }

        fun value(): Any? {
            skipSpaces()
            require(!atEnd()) { "El JSON termina antes de tiempo" }
            return when (text[position]) {
                '{' -> objectValue()
                '[' -> listValue()
                '"' -> stringValue()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> numberValue()
            }
        }

        private fun objectValue(): Map<String, Any?> {
            expect('{')
            val result = LinkedHashMap<String, Any?>()
            skipSpaces()
            if (peek() == '}') {
                position++
                return result
            }
            while (true) {
                skipSpaces()
                val key = stringValue()
                require(key !in result) { "La clave $key se repite en $position" }
                skipSpaces()
                expect(':')
                result[key] = value()
                skipSpaces()
                if (peek() == ',') {
                    position++
                } else {
                    expect('}')
                    return result
                }
            }
        }

        private fun listValue(): List<Any?> {
            expect('[')
            val result = mutableListOf<Any?>()
            skipSpaces()
            if (peek() == ']') {
                position++
                return result
            }
            while (true) {
                result += value()
                skipSpaces()
                if (peek() == ',') {
                    position++
                } else {
                    expect(']')
                    return result
                }
            }
        }

        private fun stringValue(): String {
            expect('"')
            val result = StringBuilder()
            while (true) {
                require(!atEnd()) { "Un texto del JSON no se cierra" }
                val char = text[position++]
                when {
                    char == '"' -> return result.toString()
                    char == '\\' -> result.append(escaped())
                    char < ' ' -> throw IllegalArgumentException("Carácter de control en $position")
                    else -> result.append(char)
                }
            }
        }

        private fun escaped(): Char {
            require(!atEnd()) { "Un escape del JSON no termina" }
            return when (val char = text[position++]) {
                '"', '\\', '/' -> char

                'b' -> '\b'

                'f' -> '\u000C'

                'n' -> '\n'

                'r' -> '\r'

                't' -> '\t'

                'u' -> {
                    require(position + 4 <= text.length) { "Un escape \\u no termina" }
                    val code = text.substring(position, position + 4).toInt(16)
                    position += 4
                    code.toChar()
                }

                else -> throw IllegalArgumentException("Escape desconocido en $position")
            }
        }

        private fun numberValue(): Any {
            val start = position
            while (!atEnd() && text[position] in "+-0123456789.eE") position++
            val number = text.substring(start, position)
            require(number.isNotEmpty()) { "Valor desconocido en $start" }
            return number.toLongOrNull()
                ?: requireNotNull(number.toDoubleOrNull()) { "Número inválido en $start" }
        }

        private fun literal(word: String, value: Boolean?): Boolean? {
            require(text.startsWith(word, position)) { "Valor desconocido en $position" }
            position += word.length
            return value
        }

        private fun peek(): Char {
            require(!atEnd()) { "El JSON termina antes de tiempo" }
            return text[position]
        }

        private fun expect(char: Char) {
            require(peek() == char) { "Se esperaba $char en $position" }
            position++
        }
    }
}

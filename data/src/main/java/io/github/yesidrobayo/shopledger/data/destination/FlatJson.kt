package io.github.yesidrobayo.shopledger.data.destination

/**
 * Lee y escribe un objeto JSON plano: un solo objeto, con valores de texto, número o booleano, sin
 * objetos ni listas adentro y sin claves repetidas.
 *
 * Es estricto a propósito, porque lo que lee viene de un servicio no confiable: sigue la gramática
 * de JSON al pie de la letra, sin comentarios, comillas simples, comas sobrantes, `NaN` ni textos
 * sin comillas. Una clave repetida hace inválido el objeto entero, porque dos lectores podrían
 * quedarse con valores distintos. Cada valor guarda su texto tal como venía, para que quien lo usa
 * lo revise contra su forma exacta.
 */
internal object FlatJson {
    /** Un valor del objeto, con su texto tal como venía en el archivo. */
    sealed interface Value {
        /** El texto del valor: el contenido ya sin escapes, si es texto, o el número tal cual. */
        val text: String

        data class Text(override val text: String) : Value

        data class Number(override val text: String) : Value

        data class Bool(override val text: String) : Value
    }

    /** Lee [json] como un objeto plano, o devuelve `null` si no lo es exactamente. */
    fun parse(json: String): Map<String, Value>? = Reader(json).readObject()

    /**
     * Escribe [members] como un objeto compacto, en su orden. El texto se escapa; los números y los
     * booleanos van tal cual, así que quien llama los da ya en su forma exacta.
     */
    fun write(members: List<Pair<String, Value>>): String = buildString {
        append('{')
        members.forEachIndexed { index, (name, value) ->
            if (index > 0) append(',')
            appendQuoted(name)
            append(':')
            when (value) {
                is Value.Text -> appendQuoted(value.text)
                is Value.Number -> append(value.text)
                is Value.Bool -> append(value.text)
            }
        }
        append('}')
    }

    private fun StringBuilder.appendQuoted(text: String) {
        append('"')
        for (char in text) {
            when {
                char == '"' -> append("\\\"")

                char == '\\' -> append("\\\\")

                char < ' ' -> {
                    append("\\u")
                    append(char.code.toString(HEX_RADIX).padStart(UNICODE_ESCAPE_DIGITS, '0'))
                }

                else -> append(char)
            }
        }
        append('"')
    }

    private class Reader(private val json: String) {
        private var position = 0

        fun readObject(): Map<String, Value>? {
            skipWhitespace()
            if (!take('{')) return null
            val members = LinkedHashMap<String, Value>()
            skipWhitespace()
            if (!take('}')) {
                do {
                    skipWhitespace()
                    val name = readString() ?: return null
                    skipWhitespace()
                    if (!take(':')) return null
                    skipWhitespace()
                    val value = readValue() ?: return null
                    if (members.put(name, value) != null) return null
                    skipWhitespace()
                } while (take(','))
                if (!take('}')) return null
            }
            skipWhitespace()
            return members.takeIf { position == json.length }
        }

        private fun readValue(): Value? = when (json.getOrNull(position)) {
            '"' -> readString()?.let(Value::Text)
            't' -> readLiteral(TRUE)?.let(Value::Bool)
            'f' -> readLiteral(FALSE)?.let(Value::Bool)
            else -> readNumber()?.let(Value::Number)
        }

        private fun readLiteral(literal: String): String? {
            if (!json.startsWith(literal, position)) return null
            position += literal.length
            return literal
        }

        /** `-? (0 | [1-9][0-9]*) (. [0-9]+)? ([eE] [+-]? [0-9]+)?`, tal cual lo pide JSON. */
        private fun readNumber(): String? {
            val start = position
            take('-')
            when {
                take('0') -> Unit
                peekIn('1'..'9') -> skipDigits()
                else -> return null
            }
            if (take('.') && skipDigits() == 0) return null
            if ((take('e') || take('E'))) {
                if (!take('+')) take('-')
                if (skipDigits() == 0) return null
            }
            return json.substring(start, position)
        }

        private fun readString(): String? {
            if (!take('"')) return null
            val text = StringBuilder()
            while (true) {
                val char = json.getOrNull(position++) ?: return null
                when {
                    char == '"' -> return text.toString()

                    char == '\\' -> text.append(readEscape() ?: return null)

                    // JSON no admite un carácter de control sin escapar dentro de un texto.
                    char < ' ' -> return null

                    else -> text.append(char)
                }
            }
        }

        private fun readEscape(): Char? = when (json.getOrNull(position++)) {
            '"' -> '"'
            '\\' -> '\\'
            '/' -> '/'
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> readUnicodeEscape()
            else -> null
        }

        private fun readUnicodeEscape(): Char? {
            if (position + UNICODE_ESCAPE_DIGITS > json.length) return null
            val digits = json.substring(position, position + UNICODE_ESCAPE_DIGITS)
            if (!digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
            position += UNICODE_ESCAPE_DIGITS
            return digits.toInt(HEX_RADIX).toChar()
        }

        private fun skipDigits(): Int {
            val start = position
            while (peekIn('0'..'9')) position++
            return position - start
        }

        private fun skipWhitespace() {
            while (json.getOrNull(position) in WHITESPACE) position++
        }

        private fun peekIn(range: CharRange): Boolean =
            json.getOrNull(position)?.let { it in range } == true

        private fun take(char: Char): Boolean {
            if (json.getOrNull(position) != char) return false
            position++
            return true
        }
    }

    private const val TRUE = "true"
    private const val FALSE = "false"
    private const val HEX_RADIX = 16
    private const val UNICODE_ESCAPE_DIGITS = 4

    /** Los únicos espacios que admite JSON entre elementos. */
    private val WHITESPACE = setOf(' ', '\t', '\n', '\r')
}

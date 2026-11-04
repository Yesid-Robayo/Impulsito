package io.github.yesidrobayo.shopledger.domain.model.backup

/**
 * La forma que debe tener, como texto, el valor de una clave de los metadatos que acompañan a cada
 * copia en el destino.
 *
 * Esos metadatos son entrada no confiable, también si los escribió la app, así que cada valor se
 * revisa contra una forma exacta antes de usarlo. La lista de formas es cerrada a propósito:
 * ninguna admite letras fuera del hexadecimal, de unos pocos literales fijos o del sufijo de una
 * versión, salvo [DeviceLabel]. Por eso una clave no puede llevar una moneda, una región o una zona
 * sin cambiar de forma, y una prueba lo comprueba con los identificadores reales de monedas, países,
 * idiomas y zonas.
 *
 * Solo se aceptan cifras y letras ASCII, comparadas una por una: una cifra de otro sistema de
 * numeración, como las arábigas orientales o las de ancho completo, no es un número de la copia, y
 * el resultado no depende del idioma ni de la región del teléfono.
 */
sealed interface MetaValueForm {
    /** Si [value] tiene exactamente esta forma. */
    fun accepts(value: String): Boolean

    /**
     * Un entero decimal no negativo que cabe en `Long`: generación, versión de esquema, tamaños y
     * conteos.
     *
     * Sin signo, sin ceros a la izquierda, sin separadores de miles y sin espacios: un mismo número
     * tiene un solo texto, y ninguna forma regional de escribirlo pasa.
     */
    data object Count : MetaValueForm {
        /** El mayor `Long`, escrito como texto, para comparar sin desbordar. */
        private val MAX_TEXT = Long.MAX_VALUE.toString()

        override fun accepts(value: String): Boolean = parse(value) != null

        /** El número que escribe [value], o `null` si no tiene esta forma. */
        fun parse(value: String): Long? {
            val canonical = value == "0" || value.firstOrNull() in '1'..'9'
            if (!canonical || value.length > MAX_TEXT.length || !value.all(::isAsciiDigit)) {
                return null
            }
            // Con el mismo largo, el orden del texto es el orden del número.
            if (value.length == MAX_TEXT.length && value > MAX_TEXT) return null
            return value.toLong()
        }
    }

    /** Exactamente [length] cifras hexadecimales en minúscula: una suma o un identificador de clave. */
    data class LowerHex(val length: Int) : MetaValueForm {
        init {
            require(length > 0) { "El largo de un valor hexadecimal debe ser positivo" }
        }

        override fun accepts(value: String): Boolean =
            value.length == length && value.all(::isLowerHexDigit)
    }

    /**
     * Un identificador UUID en su texto canónico de 36 caracteres, en minúscula, como el que
     * identifica al teléfono que hizo la copia.
     */
    data object Uuid : MetaValueForm {
        private const val LENGTH = 36
        private val HYPHENS = setOf(8, 13, 18, 23)

        override fun accepts(value: String): Boolean = value.length == LENGTH &&
            value.withIndex().all { (index, char) ->
                if (index in HYPHENS) char == '-' else isLowerHexDigit(char)
            }
    }

    /**
     * Uno de unos pocos literales fijos, como el tipo de archivo o una marca que solo puede valer
     * `1`. La lista completa vive aquí, en el código: un valor fuera de ella no tiene significado.
     */
    data class OneOf(val values: List<String>) : MetaValueForm {
        init {
            require(values.isNotEmpty()) { "Una lista de literales no puede estar vacía" }
        }

        override fun accepts(value: String): Boolean = value in values
    }

    /**
     * El nombre de versión de la app: `MAYOR.MENOR.PARCHE`, con un sufijo opcional en minúscula,
     * como el de una compilación de desarrollo. Solo sirve para diagnosticar.
     */
    data object AppVersion : MetaValueForm {
        private const val PARTS = 3

        override fun accepts(value: String): Boolean {
            val core = value.substringBefore('-')
            val suffix = value.substringAfter('-', missingDelimiterValue = "")
            val numbers = core.split('.')
            val suffixIsValid = value.length == core.length ||
                (suffix.isNotEmpty() && suffix.all(::isVersionSuffixChar))
            return numbers.size == PARTS && numbers.all { Count.parse(it) != null } && suffixIsValid
        }
    }

    /**
     * Fabricante y modelo del teléfono, para que la persona distinga entre sus teléfonos al elegir
     * una copia. Es el **único texto libre** de los metadatos, y lo escribe el sistema del teléfono,
     * no la persona ni los datos del negocio. Hasta [maxUtf8Bytes] bytes en UTF-8, sin caracteres de
     * control.
     */
    data class DeviceLabel(val maxUtf8Bytes: Int) : MetaValueForm {
        init {
            require(maxUtf8Bytes > 0) { "El tope de bytes debe ser positivo" }
        }

        override fun accepts(value: String): Boolean {
            if (value.isEmpty() || value.any(Char::isISOControl)) return false
            val bytes =
                try {
                    value.encodeToByteArray(throwOnInvalidSequence = true)
                } catch (_: CharacterCodingException) {
                    // Una mitad suelta de un par sustituto no es texto: no se puede contar ni mostrar.
                    return false
                }
            return bytes.size <= maxUtf8Bytes
        }
    }
}

private fun isAsciiDigit(char: Char): Boolean = char in '0'..'9'

private fun isLowerHexDigit(char: Char): Boolean = isAsciiDigit(char) || char in 'a'..'f'

private fun isVersionSuffixChar(char: Char): Boolean =
    isAsciiDigit(char) || char in 'a'..'z' || char == '-' || char == '.'

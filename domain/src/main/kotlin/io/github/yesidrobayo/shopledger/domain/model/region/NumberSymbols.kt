package io.github.yesidrobayo.shopledger.domain.model.region

/**
 * Los símbolos con que el formato regional del negocio escribe un número: el separador decimal, el
 * signo menos, el de porcentaje y la agrupación de cifras.
 *
 * El dominio no conoce las regiones: sería suponer un país, y además necesitaría la base de datos
 * de regiones de la plataforma, que no es Kotlin puro. Por eso estos símbolos llegan como datos
 * desde los formateadores, que los sacan del mismo formato con que escriben las cifras. Así lo que
 * la app muestra y lo que lee de la persona no pueden separarse.
 *
 * Son caracteres sueltos, sin espacios finos ni marcas de dirección: los formateadores ya los
 * normalizan para la fuente de la app.
 */
data class NumberSymbols(
    /** Separa la parte entera de los decimales, por ejemplo `,` o `.`. */
    val decimalSeparator: Char,
    /**
     * El signo menos de la región. El guion del teclado y el signo menos tipográfico, U+2212, se
     * aceptan siempre, además de este.
     */
    val minusSign: Char,
    /** Cómo se agrupan las cifras enteras, o `null` si la región no las agrupa. */
    val grouping: DigitGrouping?,
    /**
     * El signo de porcentaje de la región, que en algunas no es `%` aunque las cifras sean latinas.
     * El `%` del teclado se acepta siempre, además de este.
     */
    val percentSign: Char = PERCENT_SIGN,
) {
    init {
        // Un carácter que se pudiera leer de dos formas haría ambiguo un número escrito.
        require(!decimalSeparator.isWhitespace() && !isReserved(decimalSeparator)) {
            "El separador decimal no puede ser una cifra, un signo ni un espacio"
        }
        require(
            !minusSign.isWhitespace() &&
                !minusSign.isDigit() &&
                minusSign != PLUS_SIGN &&
                !isPercent(minusSign),
        ) { "El signo menos no puede ser una cifra, un espacio, el signo más ni el de porcentaje" }
        require(
            !percentSign.isWhitespace() &&
                !percentSign.isDigit() &&
                percentSign != PLUS_SIGN &&
                !isMinus(percentSign),
        ) { "El signo de porcentaje no puede ser una cifra, un espacio ni otro signo" }
        require(grouping == null || !grouping.reads(decimalSeparator)) {
            "El separador decimal no puede leerse como separador de grupos"
        }
        require(
            grouping == null ||
                (
                    !isReserved(grouping.separator) &&
                        !grouping.reads(minusSign) &&
                        !grouping.reads(percentSign)
                    ),
        ) { "El separador de grupos no puede ser una cifra ni un signo" }
    }

    /** Si [char] es un signo menos: el de la región, el guion del teclado o el tipográfico. */
    fun isMinus(char: Char): Boolean =
        char == minusSign || char == HYPHEN_MINUS || char == TYPOGRAPHIC_MINUS

    /** Si [char] es un signo de porcentaje: el de la región o el del teclado. */
    fun isPercent(char: Char): Boolean = char == percentSign || char == PERCENT_SIGN

    /**
     * Si [char] ya tiene otro papel en un número escrito. Como cuenta los signos de la región,
     * también impide que el separador decimal sea uno de ellos.
     */
    private fun isReserved(char: Char): Boolean =
        char.isDigit() || char == PLUS_SIGN || isPercent(char) || isMinus(char)

    companion object {
        /** El guion del teclado, que la persona escribe como signo menos. */
        const val HYPHEN_MINUS: Char = '-'

        /** El signo menos tipográfico que escriben los formateadores. */
        const val TYPOGRAPHIC_MINUS: Char = '\u2212'

        /** El signo más, que se admite delante o detrás del número sin cambiar su valor. */
        const val PLUS_SIGN: Char = '+'

        /** El signo de porcentaje del teclado. Solo se admite al leer un porcentaje. */
        const val PERCENT_SIGN: Char = '%'
    }
}

/**
 * La agrupación de las cifras enteras: el separador, el tamaño del grupo de la derecha y el de los
 * demás.
 *
 * Casi todas las regiones agrupan de a tres, pero no todas: en algunas el primer grupo es de tres y
 * los siguientes de dos, como en `12,34,567`. Por eso el tamaño es un dato y no una constante.
 */
data class DigitGrouping(
    /** El separador que escribe el formato, por ejemplo `.`, `,`, `'` o un espacio duro. */
    val separator: Char,
    /** Cifras del grupo que queda justo a la izquierda de los decimales. */
    val primarySize: Int,
    /**
     * Cifras de cada grupo que sigue hacia la izquierda. Es igual a [primarySize] si la región no
     * los distingue.
     */
    val secondarySize: Int,
) {
    init {
        require(primarySize >= 1 && secondarySize >= 1) {
            "Un grupo de cifras tiene al menos una cifra"
        }
    }

    /**
     * Si [char] se lee como este separador.
     *
     * Los formatos agrupan a veces con caracteres que un teclado no tiene, como el espacio fino o
     * el apóstrofo tipográfico. Si el separador es un espacio, cualquier espacio vale; si es un
     * apóstrofo, vale también el del teclado. Lo demás tiene que coincidir.
     */
    fun reads(char: Char): Boolean = when {
        separator.isWhitespace() -> char.isWhitespace()
        separator in APOSTROPHES -> char in APOSTROPHES
        else -> char == separator
    }

    private companion object {
        /** El apóstrofo del teclado y el tipográfico, que algunos teclados ponen en su lugar. */
        val APOSTROPHES: Set<Char> = setOf('\'', '\u2019')
    }
}

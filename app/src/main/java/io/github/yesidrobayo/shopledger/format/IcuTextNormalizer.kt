package io.github.yesidrobayo.shopledger.format

/**
 * Deja el texto que devuelve ICU listo para dibujarse con la fuente de la app.
 *
 * ICU puede devolver caracteres que la fuente no tiene, o que rompen una cifra. Todo número, monto,
 * porcentaje y fecha pasa por aquí antes de llegar a la pantalla, a un documento o a TalkBack.
 */
object IcuTextNormalizer {
    /** Signo menos tipográfico: tiene el mismo ancho que las cifras tabulares. */
    const val MINUS_SIGN: Char = '\u2212'

    /** Espacio duro: une cifra y unidad, o grupos de cifras, sin que la línea se parta ahí. */
    const val NO_BREAK_SPACE: Char = '\u00A0'

    private const val HYPHEN_MINUS = '-'
    private const val NARROW_NO_BREAK_SPACE = '\u202F'
    private const val THIN_SPACE = '\u2009'
    private const val LEFT_TO_RIGHT_MARK = '\u200E'
    private const val RIGHT_TO_LEFT_MARK = '\u200F'
    private const val ARABIC_LETTER_MARK = '\u061C'

    /**
     * Cambia los espacios finos por espacios duros y quita las marcas de dirección.
     *
     * Algunas regiones agrupan cifras con espacios finos, y ICU también los pone antes de AM y PM.
     * La fuente no los trae, y con un respaldo del sistema la cifra cambiaría de ancho. Las marcas
     * de dirección tampoco están en la fuente, y sobran: la interfaz va de izquierda a derecha en
     * todos sus idiomas. Sirve para fechas, donde un guion no es un signo.
     */
    fun normalizeText(text: String): String = buildString(text.length) {
        for (char in text) {
            when (char) {
                NARROW_NO_BREAK_SPACE, THIN_SPACE -> append(NO_BREAK_SPACE)
                LEFT_TO_RIGHT_MARK, RIGHT_TO_LEFT_MARK, ARABIC_LETTER_MARK -> Unit
                else -> append(char)
            }
        }
    }

    /**
     * Igual que [normalizeText], y además cambia el guion por el signo menos tipográfico.
     *
     * Solo para números: en un número formateado, el único guion posible es el signo. El guion del
     * teclado es más angosto que una cifra, así que un negativo dejaría de alinear en una columna.
     */
    fun normalizeNumber(text: String): String =
        normalizeText(text).replace(HYPHEN_MINUS, MINUS_SIGN)
}

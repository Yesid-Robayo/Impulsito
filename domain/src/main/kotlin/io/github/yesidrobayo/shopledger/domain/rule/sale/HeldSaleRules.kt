package io.github.yesidrobayo.shopledger.domain.rule.sale

/** Las reglas del nombre de una venta en espera. */
object HeldSaleRules {
    /** Largo máximo del nombre, en caracteres como los cuenta la base. */
    const val MAX_LABEL_LENGTH = 40

    /**
     * El nombre que se guarda para lo escrito en [label]: sin espacios a los lados, y `null` si
     * queda vacío, porque una venta sin nombre se muestra como `Sin nombre`.
     */
    fun labelOf(label: String?): String? = label?.let { it.trim().takeIf(String::isNotEmpty) }

    /**
     * Si [label], ya recortado, cabe: hasta [MAX_LABEL_LENGTH] caracteres. Un emoji o una letra
     * fuera del plano básico cuentan uno, como en la base, aunque ocupen dos unidades de `String`.
     */
    fun fits(label: String?): Boolean = label == null || codePointCount(label) <= MAX_LABEL_LENGTH

    private fun codePointCount(text: String): Int {
        var count = 0
        var index = 0
        while (index < text.length) {
            val pair = text[index].isHighSurrogate() &&
                index + 1 < text.length &&
                text[index + 1].isLowSurrogate()
            index += if (pair) 2 else 1
            count++
        }
        return count
    }
}

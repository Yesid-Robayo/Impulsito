package io.github.yesidrobayo.shopledger.domain.rule.label

/**
 * Un código Code 128 listo para dibujar: [modules] dice, de izquierda a derecha, si cada módulo es
 * barra (`true`) o espacio. No incluye la zona de silencio, que pone quien lo dibuja.
 */
class Code128Symbol(val modules: List<Boolean>) {
    /** Cuántos módulos mide, sin la zona de silencio. */
    val width: Int get() = modules.size
}

/**
 * Codifica un texto en Code 128, el código de las etiquetas propias, sin bibliotecas.
 *
 * Usa el juego B, que cubre los caracteres visibles de ASCII, y pasa al juego C, de dos cifras por
 * símbolo, en una tira de cifras al final de 4 o más, o en medio de 6 o más: así un código propio
 * como `P00000123` mide menos y se lee mejor en una etiqueta angosta. Una tira impar deja su
 * primera cifra en el juego B.
 */
object Code128 {
    /** El código de [text], o `null` si está vacío o tiene un carácter fuera de ASCII visible. */
    fun encode(text: String): Code128Symbol? {
        if (text.isEmpty() || text.any { it.code !in FIRST_B..LAST_B }) return null
        val values = symbolValues(text)
        // El de inicio pesa uno, y cada símbolo siguiente, su posición.
        val weighted = values.withIndex().sumOf { (index, value) -> maxOf(index, 1) * value }
        val check = weighted % CHECK_MODULUS
        val modules = mutableListOf<Boolean>()
        for (value in values + check + STOP) {
            PATTERNS[value].forEachIndexed { element, width ->
                repeat(width.digitToInt()) { modules += element % 2 == 0 }
            }
        }
        return Code128Symbol(modules)
    }

    /** Los valores de los símbolos, desde el de inicio y sin el de control. */
    internal fun symbolValues(text: String): List<Int> {
        val values = mutableListOf<Int>()
        var index = 0
        var inC = digitRun(text, 0).let { it == text.length && it >= 2 && it % 2 == 0 }
        values += if (inC) START_C else START_B
        while (index < text.length) {
            val run = digitRun(text, index)
            val atEnd = index + run == text.length
            if (!inC && ((run >= MIN_RUN_END && atEnd) || run >= MIN_RUN_MIDDLE)) {
                if (run % 2 == 1) {
                    values += text[index].code - FIRST_B
                    index++
                }
                values += CODE_C
                inC = true
            }
            if (inC) {
                if (digitRun(text, index) >= 2) {
                    values += text.substring(index, index + 2).toInt()
                    index += 2
                    continue
                }
                values += CODE_B
                inC = false
            }
            values += text[index].code - FIRST_B
            index++
        }
        return values
    }

    private fun digitRun(text: String, from: Int): Int {
        var end = from
        while (end < text.length && text[end] in '0'..'9') end++
        return end - from
    }

    private const val FIRST_B = 32
    private const val LAST_B = 126
    private const val CODE_C = 99
    private const val CODE_B = 100
    private const val START_B = 104
    private const val START_C = 105
    private const val STOP = 106
    private const val CHECK_MODULUS = 103
    private const val MIN_RUN_END = 4
    private const val MIN_RUN_MIDDLE = 6

    /** El ancho de cada barra y espacio de los 107 símbolos, empezando por una barra. */
    private val PATTERNS =
        listOf(
            "212222", "222122", "222221", "121223", "121322", "131222", "122213", "122312",
            "132212", "221213", "221312", "231212", "112232", "122132", "122231", "113222",
            "123122", "123221", "223211", "221132", "221231", "213212", "223112", "312131",
            "311222", "321122", "321221", "312212", "322112", "322211", "212123", "212321",
            "232121", "111323", "131123", "131321", "112313", "132113", "132311", "211313",
            "231113", "231311", "112133", "112331", "132131", "113123", "113321", "133121",
            "313121", "211331", "231131", "213113", "213311", "213131", "311123", "311321",
            "331121", "312113", "312311", "332111", "314111", "221411", "431111", "111224",
            "111422", "121124", "121421", "141122", "141221", "112214", "112412", "122114",
            "122411", "142112", "142211", "241211", "221114", "413111", "241112", "134111",
            "111242", "121142", "121241", "114212", "124112", "124211", "411212", "421112",
            "421211", "212141", "214121", "412121", "111143", "111341", "131141", "114113",
            "114311", "411113", "411311", "113141", "114131", "311141", "411131", "211412",
            "211214", "211232", "2331112",
        )
}

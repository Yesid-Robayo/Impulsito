package io.github.yesidrobayo.shopledger.domain.rule.phrase

/**
 * Pasa una palabra a singular con reglas de sufijo, en orden: aplica la primera que coincide. Las
 * reglas son expresiones regulares con su reemplazo, y cada idioma trae las suyas. Se aplican igual
 * a lo que se dice y a los nombres del catálogo, así que basta con que las dos formas den lo mismo,
 * aunque no sea el singular de diccionario.
 */
internal class Singularizer(rules: List<Pair<String, String>>) {
    private val compiled = rules.map { (pattern, replacement) -> Regex(pattern) to replacement }

    fun singular(word: String): String {
        val (pattern, replacement) = compiled.firstOrNull { (pattern, _) ->
            pattern.containsMatchIn(word)
        }
            ?: return word
        return pattern.replace(word, replacement)
    }
}

/**
 * Cuántas palabras dichas encontraron pareja en un nombre, a qué distancia en total, y qué palabras
 * del nombre sobran.
 */
internal data class Cover(val matched: Int, val distance: Int, val free: List<String>)

/**
 * Comparación de palabras con errores de dictado: "hariana" por "harina" o "asucar" por "azucar".
 *
 * Usa la distancia de Damerau-Levenshtein (con transposición de letras vecinas) y una tolerancia
 * que crece con el largo: una palabra de tres letras debe ser exacta, una de cuatro a siete admite
 * un error y una más larga, dos. Las palabras canónicas, como un número o una unidad dentro de un
 * nombre, empiezan con [CANONICAL_PREFIX] y siempre son exactas: 400 ml no es 40 ml.
 */
internal object PhraseWords {
    const val CANONICAL_PREFIX: Char = '#'

    /**
     * Errores admitidos según el largo de la palabra más corta; desde el último, la tolerancia ya
     * no crece.
     */
    private val ALLOWANCE = intArrayOf(0, 0, 0, 0, 0, 1, 1, 1, 2)

    /** Largo mínimo de una abreviatura: "refri" por "refrigerante", "cami" por "camiseta". */
    private const val ABBREVIATION = 4

    /** Lo que debe sobrar del nombre para que una palabra dicha sea su abreviatura. */
    private const val ABBREVIATED = 3

    /**
     * La distancia entre las dos palabras si están lo bastante cerca, o `null`. Con [abbreviated],
     * el comienzo de una palabra larga también vale: sirve para productos, no para nombres de
     * categorías o personas, donde "esta" no es "estacionamento".
     */
    fun closeness(a: String, b: String, abbreviated: Boolean = true, tolerance: Int = 2): Int? {
        if (abbreviated && abbreviates(a, b)) return 1
        val distance = distance(a, b)
        return if (distance <= minOf(allowance(a, b), tolerance)) distance else null
    }

    /** Si [a] es el comienzo de [b] y a [b] le sobra bastante: "refri" de "refrigerante". */
    private fun abbreviates(a: String, b: String): Boolean = a.length >= ABBREVIATION &&
        b.length >= a.length + ABBREVIATED &&
        a[0] != CANONICAL_PREFIX &&
        b.startsWith(a)

    private fun allowance(a: String, b: String): Int =
        if (a[0] == CANONICAL_PREFIX || b[0] == CANONICAL_PREFIX) {
            0
        } else {
            ALLOWANCE[minOf(a.length, b.length, ALLOWANCE.size - 1)]
        }

    /**
     * Distancia de edición con transposición de dos letras vecinas, en su versión de alineación
     * óptima.
     */
    fun distance(a: String, b: String): Int {
        val table = Array(a.length + 1) { row -> IntArray(b.length + 1) { column -> row + column } }
        for (row in 1..a.length) {
            for (column in 1..b.length) {
                val cost = if (a[row - 1] == b[column - 1]) 0 else 1
                var best = minOf(
                    table[row - 1][column] + 1,
                    table[row][column - 1] + 1,
                    table[row - 1][column - 1] + cost,
                )
                if (isTransposition(a, b, row, column)) {
                    best = minOf(best, table[row - 2][column - 2] + 1)
                }
                table[row][column] = best
            }
        }
        return table[a.length][b.length]
    }

    private fun isTransposition(a: String, b: String, row: Int, column: Int): Boolean =
        row > 1 && column > 1 && a[row - 1] == b[column - 2] && a[row - 2] == b[column - 1]

    /**
     * Empareja cada palabra dicha con la palabra libre del nombre que tenga más cerca. Cada palabra
     * del nombre se usa una sola vez, así que "pan pan" no cubre dos veces el mismo "pan".
     */
    fun cover(
        spoken: List<String>,
        name: List<String>,
        abbreviated: Boolean = true,
        tolerance: Int = 2,
    ): Cover {
        val free = name.toMutableList()
        var matched = 0
        var distance = 0
        for (word in spoken) {
            val partner = closestPartner(word, free, abbreviated, tolerance) ?: continue
            free.removeAt(partner.first)
            matched++
            distance += partner.second
        }
        return Cover(matched, distance, free)
    }

    private fun closestPartner(
        word: String,
        free: List<String>,
        abbreviated: Boolean,
        tolerance: Int,
    ): Pair<Int, Int>? = free.indices
        .mapNotNull { index ->
            closeness(word, free[index], abbreviated, tolerance)?.let { index to it }
        }
        .minByOrNull { it.second }
}

package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.local.guard.CatalogTrigger
import io.github.yesidrobayo.shopledger.data.local.guard.SchemaVersionCatalog

/** Una columna de una tabla del esquema. */
internal data class ColumnRef(val table: String, val column: String)

/**
 * Las reglas por fila que imponen las guardas de una versión del esquema, leídas de su texto: la
 * lista cerrada de cada columna de tipo, el rango de cada número y el largo máximo de cada texto.
 *
 * Una guarda solo revisa lo que se escribe. Una copia hostil, o una base que se escribió sin ellas,
 * puede guardar filas que nunca las pasaron. El validador vuelve a pedir estas reglas a las filas
 * guardadas, y las saca del mismo texto que el catálogo exige letra por letra: así no hay una
 * segunda lista que mantener, y una guarda nueva o más estricta llega sola al validador.
 *
 * Solo toma una regla cuando vale para toda fila de la tabla:
 * - en el primer nivel de la condición de la guarda, que es una conjunción;
 * - o dentro de un grupo `(NEW.columna IS NULL OR ...)` de esa misma columna, que solo la libera
 *   cuando es nula.
 *
 * Una regla dentro de cualquier otro grupo depende de otra columna, como el tipo de un movimiento, y
 * no vale para toda fila. Se deja: un hallazgo sobre datos sanos sería peor que una regla de menos.
 * Lo que una columna admite es la unión de lo que admiten todas las guardas de su tabla, porque una
 * fila pudo entrar por cualquiera.
 *
 * @property enums los valores admitidos de cada columna de tipo, como el tipo de un movimiento.
 * @property ranges el menor y el mayor valor admitidos de cada columna numérica.
 * @property maxLengths el mayor largo admitido de cada columna de texto.
 */
internal class GuardRules private constructor(
    val enums: Map<ColumnRef, Set<String>>,
    val ranges: Map<ColumnRef, LongRange>,
    val maxLengths: Map<ColumnRef, Long>,
) {
    companion object {
        /** Las reglas de las guardas de [catalog]. */
        fun of(catalog: SchemaVersionCatalog): GuardRules {
            val listed = mutableMapOf<ColumnRef, MutableSet<String>>()
            val literals = mutableMapOf<ColumnRef, MutableMap<String, MutableSet<String>>>()
            val ranges = mutableMapOf<ColumnRef, LongRange>()
            val lengths = mutableMapOf<ColumnRef, Long>()
            for (trigger in catalog.triggers) {
                val body = GuardBody.of(trigger) ?: continue
                for (match in IN_LIST.findAll(body.text)) {
                    val column = match.groupValues[1]
                    if (!body.appliesToEveryRow(match.range.first, column)) continue
                    val values = STRING_LITERAL.findAll(match.groupValues[2])
                        .map { it.groupValues[1].replace("''", "'") }
                    listed.getOrPut(ColumnRef(trigger.table, column)) { mutableSetOf() } += values
                }
                for (match in EQUALS_LITERAL.findAll(body.text)) {
                    if (body.depthAt(match.range.first) != 0) continue
                    val ref = ColumnRef(trigger.table, match.groupValues[1])
                    literals.getOrPut(ref) { mutableMapOf() }
                        .getOrPut(trigger.name) { mutableSetOf() } += match.groupValues[2]
                }
                for (match in BETWEEN.findAll(body.text)) {
                    val column = match.groupValues[1]
                    if (!body.appliesToEveryRow(match.range.first, column)) continue
                    val range = match.groupValues[2].toLong()..match.groupValues[3].toLong()
                    ranges.merge(ColumnRef(trigger.table, column), range) { old, new ->
                        minOf(old.first, new.first)..maxOf(old.last, new.last)
                    }
                }
                for (match in LENGTH.findAll(body.text)) {
                    val column = match.groupValues[1]
                    if (!body.appliesToEveryRow(match.range.first, column)) continue
                    val max = (match.groupValues[2].ifEmpty { match.groupValues[3] }).toLong()
                    lengths.merge(ColumnRef(trigger.table, column), max, ::maxOf)
                }
            }
            val enums = mutableMapOf<ColumnRef, Set<String>>()
            for ((ref, values) in listed) {
                enums[ref] = values + literals[ref].orEmpty().values.flatten()
            }
            // Una columna sin lista, como el estado de un documento, se reconoce por los valores
            // que fijan a la vez la guarda de inserción y otra que la cambia: nace en uno y solo
            // puede pasar a los otros.
            for ((ref, byGuard) in literals) {
                if (ref in enums || byGuard.size < 2) continue
                if (byGuard.keys.none { it == "guard_${ref.table}_insert" }) continue
                enums[ref] = byGuard.values.flatten().toSet()
            }
            return GuardRules(enums, ranges, lengths)
        }

        /** `NEW.columna IN ('A', 'B')`, solo con textos: las de `0` y `1` son booleanos. */
        private val IN_LIST =
            Regex("""(?<![\w.])NEW\.(\w+) IN \(('(?:[^']|'')*'(?:, '(?:[^']|'')*')*)\)""")

        private val STRING_LITERAL = Regex("""'((?:[^']|'')*)'""")

        /** `NEW.columna = 'VALOR'`, un valor de tipo fijado por la guarda. */
        private val EQUALS_LITERAL = Regex("""(?<![\w.])NEW\.(\w+) = '([A-Z][A-Z0-9_]*)'""")

        /** `NEW.columna BETWEEN a AND b`, fuera de un `length(...)`. */
        private val BETWEEN =
            Regex("""(?<![\w.(])NEW\.(\w+) BETWEEN (-?\d+) AND (-?\d+)""")

        /** `length(NEW.columna) BETWEEN a AND b` o `length(NEW.columna) <= b`. */
        private val LENGTH =
            Regex("""length\(NEW\.(\w+)\) (?:BETWEEN \d+ AND (\d+)|<= (\d+))""")
    }
}

/**
 * La condición de una guarda: lo que va dentro de `WHEN NOT IFNULL((...), 0)`, con la profundidad de
 * paréntesis de cada posición. Los textos entre comillas no cuentan como paréntesis.
 */
private class GuardBody private constructor(val text: String) {
    private val depth = IntArray(text.length)
    private val outerGroup = IntArray(text.length) { -1 }

    init {
        var level = 0
        var group = -1
        var quoted = false
        for (index in text.indices) {
            val char = text[index]
            if (quoted) {
                if (char == '\'') quoted = false
            } else {
                when (char) {
                    '\'' -> quoted = true

                    '(' -> {
                        if (level == 0) group = index
                        level++
                    }

                    ')' -> level--

                    else -> Unit
                }
            }
            depth[index] = if (char == '(' && !quoted) level - 1 else level
            outerGroup[index] = if (depth[index] == 0) -1 else group
        }
    }

    fun depthAt(index: Int): Int = depth[index]

    /**
     * Si una regla que empieza en [index] vale para toda fila: en el primer nivel, o dentro del grupo
     * que solo libera a [column] cuando es nula.
     */
    fun appliesToEveryRow(index: Int, column: String): Boolean {
        if (depth[index] == 0) return true
        val group = outerGroup[index]
        return text.startsWith("(NEW.$column IS NULL OR ", group)
    }

    companion object {
        private const val START = "WHEN NOT IFNULL(("

        /** La condición de [trigger], o `null` si la guarda no tiene condición. */
        fun of(trigger: CatalogTrigger): GuardBody? {
            val start = trigger.sql.indexOf(START)
            if (start < 0) return null
            val from = start + START.length
            var level = 1
            var quoted = false
            for (index in from until trigger.sql.length) {
                val char = trigger.sql[index]
                when {
                    quoted -> if (char == '\'') quoted = false

                    char == '\'' -> quoted = true

                    char == '(' -> level++

                    char == ')' -> {
                        level--
                        if (level == 0) return GuardBody(trigger.sql.substring(from, index))
                    }
                }
            }
            return null
        }
    }
}

package io.github.yesidrobayo.shopledger.domain.rule.sale

/**
 * Los enlaces de los extras de una venta. Un extra apunta a su línea principal por la posición de
 * esa línea en la misma venta, contada desde cero. Solo hay un nivel: la principal va antes que sus
 * extras y no es a su vez un extra. Así la venta guarda la principal primero y le da su id al extra.
 */
object ExtraLines {
    /**
     * La primera línea cuyo enlace no vale, contada desde cero, o `null` si todas valen. [parents]
     * es el enlace de cada línea, `null` en una principal.
     */
    fun firstInvalid(parents: List<Int?>): Int? {
        parents.forEachIndexed { index, parent ->
            if (parent != null && !linksTo(parents, index, parent)) return index
        }
        return null
    }

    /**
     * Las líneas que quedan de [lines] al quitar las que no pasan [keep], en el mismo orden y con el
     * enlace de cada extra hacia la nueva posición de su principal. Un extra cuya principal se quita
     * se va con ella, y uno con un enlace que no vale también se quita: nunca queda un extra suelto.
     */
    fun <T> retain(
        lines: List<T>,
        parentOf: (T) -> Int?,
        keep: (T) -> Boolean,
    ): List<Retained<T>> {
        val parents = lines.map(parentOf)
        val moved = IntArray(lines.size) { DROPPED }
        val kept = ArrayList<Retained<T>>(lines.size)
        lines.forEachIndexed { index, line ->
            val parent = parents[index]
            // Un extra sin una principal anterior que siga aquí no tiene a quién seguir.
            val orphan = parent != null &&
                (!linksTo(parents, index, parent) || moved[parent] == DROPPED)
            if (orphan || !keep(line)) return@forEachIndexed
            moved[index] = kept.size
            kept += Retained(line, parent?.let { moved[it] })
        }
        return kept
    }

    /** Si la línea [index] puede ser extra de [parent]: una principal que va antes. */
    private fun linksTo(parents: List<Int?>, index: Int, parent: Int): Boolean =
        parent in 0 until index && parents[parent] == null

    private const val DROPPED = -1
}

/** Una línea que quedó, con el enlace de su principal ya movido, o `null` si es principal. */
data class Retained<T>(val line: T, val parentIndex: Int?)

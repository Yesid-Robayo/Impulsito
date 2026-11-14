package io.github.yesidrobayo.shopledger.domain.rule.catalog

import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeComponent
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeIssue
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeLine
import io.github.yesidrobayo.shopledger.domain.model.catalog.RecipeLineProblem

/** El resultado de revisar una receta antes de guardarla. */
sealed interface RecipeCheck {
    /** Se puede guardar tal como está. */
    data object Valid : RecipeCheck

    /** No se puede guardar: [issues] dice qué corregir, en el orden de las filas. */
    data class Invalid(val issues: List<RecipeIssue>) : RecipeCheck
}

/**
 * Qué receta puede guardar un combo.
 *
 * Un combo puede contener otro, pero no hay más de dos niveles ni ciclos: un kit dentro de un kit
 * se entiende, y un tercer nivel solo multiplica los casos de costo y de ciclos sin una necesidad
 * real. La base tiene la misma regla en la guarda de la tabla, como última defensa, pero solo mira
 * un nivel a cada lado de la fila que se escribe. Esta regla recorre toda la receta, hacia arriba
 * y hacia abajo del combo, así que también atrapa un tercer nivel que la guarda dejaría pasar.
 *
 * Los niveles se cuentan en saltos: combo, componente y componente del componente son dos.
 */
object RecipeRules {
    /** Los saltos que admite un combo desde el de más arriba hasta la última hoja. */
    const val MAX_LEVELS: Int = 2

    /**
     * Revisa la receta [lines] del combo [parentId].
     *
     * [products] trae los productos de las filas, los que existen. [stored] son todas las filas de
     * recetas guardadas, de todos los combos: dicen qué contiene cada componente y en qué combos
     * entra este. Las filas guardadas del propio combo se reemplazan por [lines], así que no
     * cuentan. Un componente archivado solo se acepta si ya estaba en la receta.
     */
    fun check(
        parentId: Long,
        lines: List<RecipeLine>,
        products: Map<Long, Product>,
        stored: List<RecipeComponent>,
    ): RecipeCheck {
        val issues = ArrayList<RecipeIssue>()
        if (!Limit.DOCUMENT_LINES.allows(lines.size.toLong())) {
            issues += RecipeIssue.TooManyComponents
        }
        val graph = RecipeGraph(stored.filter { it.parentProductId != parentId })
        val alreadyIn = stored.filter { it.parentProductId == parentId }
            .mapTo(HashSet()) { it.componentProductId }
        val levelsAbove = graph.levelsAbove(parentId)
        val seen = HashSet<Long>()
        lines.forEachIndexed { index, line ->
            val componentId = line.componentProductId
            val product = products[componentId]
            val problem = when {
                componentId == parentId -> RecipeLineProblem.SAME_PRODUCT

                !seen.add(componentId) -> RecipeLineProblem.REPEATED

                product == null || (product.isArchived && componentId !in alreadyIn) ->
                    RecipeLineProblem.UNAVAILABLE

                graph.reaches(componentId, parentId) -> RecipeLineProblem.CYCLE

                levelsAbove + 1 + graph.levelsBelow(componentId) > MAX_LEVELS ->
                    RecipeLineProblem.TOO_DEEP

                else -> null
            }
            if (problem != null) issues += RecipeIssue.Line(index, problem)
            val quantity = line.quantity.milli
            if (quantity <= 0L || !Limit.LINE_QUANTITY_MILLI.allows(quantity)) {
                issues += RecipeIssue.Line(index, RecipeLineProblem.QUANTITY_OUT_OF_RANGE)
            }
        }
        return if (issues.isEmpty()) RecipeCheck.Valid else RecipeCheck.Invalid(issues)
    }
}

/**
 * Las recetas guardadas como un grafo: de cada combo a sus componentes y de cada componente a los
 * combos en que entra.
 *
 * Cada recorrido lleva el camino que va siguiendo y no vuelve a entrar en él: una base dañada con
 * un ciclo no deja la cuenta dando vueltas para siempre.
 */
private class RecipeGraph(rows: List<RecipeComponent>) {
    private val children: Map<Long, List<Long>> =
        rows.groupBy({ it.parentProductId }, { it.componentProductId })
    private val parents: Map<Long, List<Long>> =
        rows.groupBy({ it.componentProductId }, { it.parentProductId })

    /** Si desde [from], bajando por las recetas, se llega a [target]. */
    fun reaches(from: Long, target: Long): Boolean = reaches(from, target, HashSet())

    /** Los saltos del camino más largo desde [productId] hasta su hoja más honda. */
    fun levelsBelow(productId: Long): Int = longest(productId, children, HashSet())

    /** Los saltos del camino más largo desde un combo de más arriba hasta [productId]. */
    fun levelsAbove(productId: Long): Int = longest(productId, parents, HashSet())

    private fun reaches(from: Long, target: Long, path: MutableSet<Long>): Boolean {
        if (from == target) return true
        if (!path.add(from)) return false
        val found = children[from].orEmpty().any { reaches(it, target, path) }
        path.remove(from)
        return found
    }

    private fun longest(
        productId: Long,
        edges: Map<Long, List<Long>>,
        path: MutableSet<Long>,
    ): Int {
        if (!path.add(productId)) return 0
        val next = edges[productId].orEmpty()
        val levels = next.maxOfOrNull { 1 + longest(it, edges, path) } ?: 0
        path.remove(productId)
        return levels
    }
}

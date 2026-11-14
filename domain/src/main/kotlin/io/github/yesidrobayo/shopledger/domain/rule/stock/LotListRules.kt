package io.github.yesidrobayo.shopledger.domain.rule.stock

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.stock.CostState
import io.github.yesidrobayo.shopledger.domain.model.stock.LotEntry
import io.github.yesidrobayo.shopledger.domain.model.stock.LotFilter
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.rule.WeightedAverageCost

/** Los lotes de un producto, ya filtrados y en el orden de salida. */
data class LotGroup(val product: Product, val entries: List<LotEntry>)

/**
 * Qué lotes muestra cada filtro de la pantalla de lotes, en qué orden y cuánto valen.
 *
 * La lista se agrupa por producto y se ordena como sale el stock: primero el que vence antes, los
 * que no vencen al final y, entre dos iguales, el más antiguo. Un grupo va antes que otro si su
 * primer lote vence antes; entre dos iguales, por nombre.
 */
object LotListRules {
    /** Si [entry] entra en [filter]. */
    fun matches(entry: LotEntry, filter: LotFilter): Boolean {
        val coded = !entry.lot.isUnassigned
        val due = entry.due
        return when (filter) {
            LotFilter.EXPIRING ->
                coded && entry.hasStock && due != null && due.soon && !due.isExpired

            LotFilter.EXPIRED -> coded && entry.hasStock && due != null && due.isExpired

            LotFilter.ALL -> coded

            // Sin asignar solo importa si tiene algo distinto de cero: negativo dice que se vendió
            // más de lo recibido con lote.
            LotFilter.UNASSIGNED -> !coded && entry.onHand.milli != 0L
        }
    }

    /** Cuántos lotes entran en [filter]. */
    fun count(entries: List<LotEntry>, filter: LotFilter): Int =
        entries.count { matches(it, filter) }

    /** Los lotes de [filter], agrupados por producto y en el orden de salida. */
    fun grouped(entries: List<LotEntry>, filter: LotFilter): List<LotGroup> = entries
        .filter { matches(it, filter) }
        .groupBy { it.product.id }
        .map { (_, lots) -> LotGroup(lots.first().product, lots.sortedWith(ENTRY_ORDER)) }
        .sortedWith(
            compareBy<LotGroup>(
                { exitKey(it.entries.first()) },
                { it.product.details.name },
                { it.product.id },
            ),
        )

    /**
     * Lo que vale lo que está por vencer: cada lote al costo con que saldría hoy, la parte
     * proporcional del valor de su producto. `null` si la suma no cabe.
     */
    fun expiringValue(entries: List<LotEntry>): Money? {
        var total = Money.ZERO
        for (entry in entries.filter { matches(it, LotFilter.EXPIRING) }) {
            val product = entry.product
            val state = CostState(product.onHand, product.averageCost, product.stockValue)
            val value = WeightedAverageCost.exitCost(state, entry.onHand).valueOrNull()
                ?: return null
            // El costo de salida nunca es negativo: solo puede pasarse por arriba.
            if (value.minor > Long.MAX_VALUE - total.minor) return null
            total += value
        }
        return total
    }

    /** La fecha `AAAA-MM-DD`, que ordena igual que el texto, o una después de todas sin fecha. */
    private fun exitKey(entry: LotEntry): String = when (val due = entry.due) {
        null -> AFTER_EVERY_DATE
        else -> due.date.toString()
    }

    private val ENTRY_ORDER: Comparator<LotEntry> = compareBy({ exitKey(it) }, { it.lot.id })

    /** Ordena después de cualquier fecha `AAAA-MM-DD`: `~` va después de las cifras. */
    private const val AFTER_EVERY_DATE = "~"
}

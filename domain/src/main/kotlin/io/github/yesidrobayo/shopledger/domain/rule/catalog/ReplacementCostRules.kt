package io.github.yesidrobayo.shopledger.domain.rule.catalog

import io.github.yesidrobayo.shopledger.domain.model.Money
import kotlin.time.Instant

/**
 * El costo de reposición: lo que costaría comprar hoy una unidad base. Es una vista adicional, que
 * nunca reemplaza al costo promedio: la ganancia se sigue midiendo con el promedio, y la reposición
 * solo dice cuánto quedaría si hubiera que volver a comprar. Cero es sin dato, y entonces ninguna
 * pantalla inventa una cifra.
 *
 * Lo actualiza la última compra, con lo que costó una unidad base de lo que entró al stock, o la
 * persona a mano. Una compra con fecha anterior a la reposición que ya se conoce no la pisa: un
 * documento atrasado no dice lo que cuesta comprar hoy.
 */
object ReplacementCostRules {
    /**
     * Lo que una compra deja como reposición de cada producto, a partir de [lines], cada una con su
     * producto y el costo de una unidad base, en el orden de la compra. Un producto que se repite
     * queda con su última línea con costo: una bonificación, que vale cero, no dice cuánto cuesta
     * comprarlo, así que no borra la reposición.
     */
    fun fromPurchase(lines: List<Pair<Long, Money>>): Map<Long, Money> {
        val costs = LinkedHashMap<Long, Money>()
        for ((productId, unitCost) in lines) {
            if (unitCost > Money.ZERO) costs[productId] = unitCost
        }
        return costs
    }

    /**
     * Si una compra del instante [purchasedAt] reemplaza la reposición fechada en [knownAt]: sí si
     * no había fecha, o si la compra no es anterior a ella.
     */
    fun purchaseReplaces(knownAt: Instant?, purchasedAt: Instant): Boolean =
        knownAt == null || purchasedAt >= knownAt

    /** La fecha que acompaña a [cost] escrito a mano en [now]: sin dato no lleva fecha. */
    fun manualStamp(cost: Money, now: Instant): Instant? = if (cost > Money.ZERO) now else null
}

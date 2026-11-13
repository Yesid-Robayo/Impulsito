package io.github.yesidrobayo.shopledger.domain.rule.catalog

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkUndoPlan
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkUndoRevert
import io.github.yesidrobayo.shopledger.domain.model.catalog.PriceChange
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import kotlin.uuid.Uuid

/**
 * Qué hace deshacer la última actualización masiva. Es puro: lo usan la pantalla, para ofrecerlo y
 * contar, y el caso de uso, dentro de su transacción.
 *
 * Un precio vuelve a su valor anterior solo si sigue siendo el que dejó el lote: el de venta en la
 * lista inicial, o el propio en una fija, que sin precio propio es el de venta. Si se cambió
 * después, se archivó o ya no existe, se deja como está y se cuenta: deshacer nunca pisa un cambio
 * posterior.
 */
object BulkUndoRules {
    /**
     * El plan para deshacer [batchId] con sus filas [rows] y los productos vigentes [products]. Un
     * producto que no está en [products] se cuenta como dejado. [defaultListId] es la lista inicial,
     * o `null` si todas las filas son de ella, como en una masiva de solo el precio de venta;
     * [ownPrices] son los precios propios de cada producto, por producto y lista.
     */
    fun plan(
        batchId: Uuid,
        rows: List<PriceChange>,
        products: List<Product>,
        defaultListId: Long? = null,
        ownPrices: Map<Long, Map<Long, Money>> = emptyMap(),
    ): BulkUndoPlan {
        val byId = products.associateBy { it.id }
        val reverts = mutableListOf<BulkUndoRevert>()
        var kept = 0
        for (row in rows) {
            val change = row.content
            val product = byId[change.productId]
            val previous = change.oldPrice
            if (product == null || previous == null) {
                kept++
                continue
            }
            val salePrice = defaultListId == null || change.priceListId == defaultListId
            // Sin precio propio en esa lista, la lista fija vende al precio de venta.
            val current = if (salePrice) {
                product.salePrice
            } else {
                ownPrices[product.id]?.get(change.priceListId) ?: product.salePrice
            }
            if (current != change.newPrice) {
                kept++
            } else {
                val listId = change.priceListId.takeUnless { salePrice }
                reverts +=
                    BulkUndoRevert(product.id, from = current, to = previous, priceListId = listId)
            }
        }
        return BulkUndoPlan(batchId, reverts, kept)
    }
}

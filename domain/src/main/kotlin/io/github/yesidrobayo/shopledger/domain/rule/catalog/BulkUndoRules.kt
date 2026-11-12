package io.github.yesidrobayo.shopledger.domain.rule.catalog

import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkUndoPlan
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkUndoRevert
import io.github.yesidrobayo.shopledger.domain.model.catalog.PriceChange
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import kotlin.uuid.Uuid

/**
 * Qué hace deshacer la última actualización masiva. Es puro: lo usan la pantalla, para ofrecerlo y
 * contar, y el caso de uso, dentro de su transacción.
 *
 * Un producto vuelve a su precio anterior solo si su precio de ahora sigue siendo el que dejó el
 * lote. Si se cambió después, se archivó o ya no existe, se deja como está y se cuenta: deshacer
 * nunca pisa un cambio posterior.
 */
object BulkUndoRules {
    /**
     * El plan para deshacer [batchId] con sus filas [rows] y los productos vigentes [products]. Un
     * producto que no está en [products] se cuenta como dejado.
     */
    fun plan(batchId: Uuid, rows: List<PriceChange>, products: List<Product>): BulkUndoPlan {
        val byId = products.associateBy { it.id }
        val reverts = mutableListOf<BulkUndoRevert>()
        var kept = 0
        for (row in rows) {
            val change = row.content
            val product = byId[change.productId]
            val previous = change.oldPrice
            if (product == null || previous == null || product.salePrice != change.newPrice) {
                kept++
            } else {
                reverts += BulkUndoRevert(product.id, from = product.salePrice, to = previous)
            }
        }
        return BulkUndoPlan(batchId, reverts, kept)
    }
}

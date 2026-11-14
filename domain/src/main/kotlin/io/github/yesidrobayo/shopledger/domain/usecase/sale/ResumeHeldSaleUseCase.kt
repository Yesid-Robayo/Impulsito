package io.github.yesidrobayo.shopledger.domain.usecase.sale

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.sale.HeldSale
import io.github.yesidrobayo.shopledger.domain.model.sale.LineDiscount
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.port.HeldSaleRepository
import io.github.yesidrobayo.shopledger.domain.port.PriceHistoryRepository
import io.github.yesidrobayo.shopledger.domain.port.PriceListRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.rule.sale.ExtraLines

/**
 * Una venta en espera lista para seguir en la venta nueva.
 *
 * [lines] son las que todavía se pueden vender. [removedProducts] cuenta las que quedaron fuera
 * porque su producto ya no existe o está archivado. [changedPrices] cuenta las que toman el precio
 * vigente y cuyo precio cambió desde que se guardó la espera. [priceListId] es la lista con que se
 * sigue, `null` para la inicial.
 */
data class ResumedSale(
    val heldSaleId: Long,
    val label: String?,
    val lines: List<SaleLineRequest>,
    val removedProducts: Int,
    val changedPrices: Int,
    val priceListId: Long? = null,
)

/** Resultado de [ResumeHeldSaleUseCase]. */
sealed interface ResumeHeldSaleResult {
    data class Resumed(val sale: ResumedSale) : ResumeHeldSaleResult

    /** La venta en espera ya no existe: alguien la confirmó o la descartó. */
    data object NotFound : ResumeHeldSaleResult
}

/**
 * Retoma una venta en espera. Solo lee: la espera sigue guardada hasta que la venta se confirme o
 * se descarte, así que retomarla y salir sin confirmar no pierde nada.
 *
 * - **Precios.** Un precio escrito a mano en la línea se respeta. Las demás líneas toman el precio
 *   vigente, y se cuenta cuántas cambiaron desde que se guardó, para decirlo al retomar.
 * - **Productos.** Un producto archivado no se puede vender, así que su línea queda fuera y se
 *   cuenta, con los extras que llevaba. Uno eliminado ya se llevó su línea en cascada, y la base se
 *   llevó con ella sus extras.
 * - **Lista de precios.** Sigue la de la espera. Si se archivó desde entonces, la venta sigue con la
 *   inicial, y cada línea sin precio escrito cuenta como un precio que cambió.
 */
class ResumeHeldSaleUseCase(
    private val transactions: TransactionRunner,
    private val heldSales: HeldSaleRepository,
    private val products: ProductRepository,
    private val priceHistory: PriceHistoryRepository,
    private val priceLists: PriceListRepository? = null,
) {
    suspend operator fun invoke(heldSaleId: Long): ResumeHeldSaleResult = transactions.read {
        val held = heldSales.findById(heldSaleId)
        if (held ==
            null
        ) {
            ResumeHeldSaleResult.NotFound
        } else {
            ResumeHeldSaleResult.Resumed(resume(held))
        }
    }

    private suspend fun resume(held: HeldSale): ResumedSale {
        val byId = products.findByIds(held.lines.map { it.productId }.toSet())
            .associateBy { it.id }
        val listId = held.priceListId?.takeIf { priceLists?.findById(it)?.isArchived == false }
        val listDropped = held.priceListId != null && listId == null
        // Un extra cuya línea principal queda fuera se va con ella, y cuenta como quitado.
        val kept = ExtraLines.retain(held.lines, { it.parentIndex }) { line ->
            byId[line.productId]?.isArchived == false
        }
        val lines = ArrayList<SaleLineRequest>(kept.size)
        var changed = 0
        for ((line, parentIndex) in kept) {
            val product = byId.getValue(line.productId)
            if (line.unitPrice == null && (listDropped || priceChangedSince(product.id, held))) {
                changed++
            }
            lines += SaleLineRequest(
                productId = product.id,
                quantity = line.quantity,
                unitPrice = line.unitPrice,
                discount = if (line.discount == Money.ZERO) {
                    LineDiscount.None
                } else {
                    LineDiscount.Amount(line.discount)
                },
                // La unidad base va sin unidad, como la escribe la venta.
                unitId = line.unitId.takeIf { it != product.details.baseUnitId },
                parentIndex = parentIndex,
            )
        }
        val removed = held.lines.size - kept.size
        return ResumedSale(held.id, held.label, lines, removed, changed, listId)
    }

    /** Si el precio de [productId] cambió después de la última vez que se guardó [held]. */
    private suspend fun priceChangedSince(productId: Long, held: HeldSale): Boolean =
        priceHistory.findByProduct(productId).any { change ->
            change.content.occurredAt > held.updatedAt &&
                change.content.oldPrice != change.content.newPrice
        }
}

package io.github.yesidrobayo.shopledger.domain.model.catalog

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Una fila del historial de precios que el caso de uso va a agregar, todavía sin id.
 *
 * El historial solo crece: cambiar un precio nunca altera una venta pasada, y cada cambio queda
 * explicado. [oldPrice] solo falta en el precio con que nace el producto. [batchId] agrupa las
 * filas de una misma actualización masiva, o las de su deshacer, para poder deshacerla entera.
 * [bulkRule] solo existe en una actualización masiva, y dice cómo se calculó.
 *
 * Lanza [IllegalArgumentException] si los datos no corresponden a [source]: la base no aceptaría
 * esa fila, y llegar aquí con ella es un error de programación.
 */
data class NewPriceChange(
    val productId: Long,
    val priceListId: Long,
    val oldPrice: Money?,
    val newPrice: Money,
    val source: PriceChangeSource,
    val batchId: Uuid?,
    val bulkRule: BulkPriceRule?,
    val occurredAt: Instant,
    val businessDate: BusinessDate,
) {
    init {
        require(oldPrice != null || source == PriceChangeSource.INITIAL) {
            "Solo el precio inicial puede no tener precio anterior"
        }
        require((batchId != null) == source.isBatch) {
            "Solo una actualización masiva o su deshacer llevan lote"
        }
        require((bulkRule != null) == (source == PriceChangeSource.BULK)) {
            "Solo una actualización masiva lleva su cálculo"
        }
    }

    /** La fila que queda en el historial cuando la base la guarda con [id]. */
    fun recordedAs(id: Long): PriceChange = PriceChange(id = id, content = this)
}

package io.github.yesidrobayo.shopledger.domain.model.stock

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import kotlin.time.Instant

/**
 * Un lote de un producto que se controla por lotes: lo que entró junto, con su código y su
 * vencimiento. Una serie es un lote de una sola unidad, con la serie como código.
 *
 * El lote no tiene costo propio: ordena la salida, pero el costo del producto sigue siendo uno
 * solo, promedio. [isUnassigned] marca el lote sin asignar, sin código ni vencimiento, que absorbe
 * lo que se mueve sin lote y es el único que puede quedar negativo: así vender nunca se bloquea
 * por lotes. Cada producto tiene uno como máximo.
 *
 * [code] es único dentro del producto. [purchaseLineId] es la línea de compra con que entró, si
 * entró por una compra. [expiryNotifiedAt] evita repetir el aviso de vencimiento, y [closedAt]
 * cierra un lote agotado sin borrarlo, porque sus filas del libro lo siguen nombrando.
 */
data class StockLot(
    val id: Long,
    val productId: Long,
    val code: String?,
    val isUnassigned: Boolean,
    val expiryDate: BusinessDate?,
    val purchaseLineId: Long?,
    val expiryNotifiedAt: Instant?,
    val createdAt: Instant,
    val closedAt: Instant?,
)

/**
 * Un lote con código que todavía no tiene id. El lote sin asignar no se crea así: lo crea el libro
 * de stock la primera vez que hace falta, porque nace sin código ni vencimiento.
 */
data class NewStockLot(
    val productId: Long,
    val code: String,
    val expiryDate: BusinessDate?,
    val purchaseLineId: Long?,
    val createdAt: Instant,
)

/**
 * Cuánto hay de un lote en una ubicación: lo que lee la salida por lote para saber de dónde sacar.
 * [expiryDate] ordena la salida, y [isUnassigned] marca el lote que absorbe lo que falte.
 */
data class LotStock(
    val lotId: Long,
    val code: String?,
    val expiryDate: BusinessDate?,
    val isUnassigned: Boolean,
    val onHand: QuantityMilli,
)

/**
 * Una parte de una salida: [quantity] milésimas, siempre positivas, que salen del lote [lotId], o
 * del lote sin asignar si es `null`. Cada parte es una fila del libro.
 */
data class LotPart(val lotId: Long?, val quantity: QuantityMilli)

/**
 * Un tramo de lo devuelto de una línea que vuelve al lote [lotId], o sin lote si es `null`: de
 * [from] a [to] milésimas acumuladas de la línea.
 */
data class LotSpan(val lotId: Long?, val from: QuantityMilli, val to: QuantityMilli) {
    val quantity: QuantityMilli get() = to - from
}

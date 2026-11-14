package io.github.yesidrobayo.shopledger.domain.model.stock

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.Voiding
import kotlin.time.Instant

/**
 * Lo que la persona pide al mover stock de [fromLocationId] a [toLocationId]: una línea por
 * producto, en la unidad base. [note] es lo que escribió, tal cual: el caso de uso la recorta, y en
 * blanco es ninguna.
 */
data class StockTransferRequest(
    val fromLocationId: Long,
    val toLocationId: Long,
    val lines: List<StockTransferLineRequest>,
    val note: String? = null,
)

/** [quantity] milésimas de la unidad base de [productId] que pasan del origen al destino. */
data class StockTransferLineRequest(val productId: Long, val quantity: QuantityMilli)

/** Por qué una línea de una transferencia no se puede registrar. */
enum class StockTransferLineRejection {
    /** El producto ya no existe. */
    PRODUCT_NOT_FOUND,

    /** El producto está archivado: no se ofrece para registrar nada nuevo. */
    PRODUCT_ARCHIVED,

    /** El producto no lleva stock propio: un servicio o uno hecho de componentes no tiene libro. */
    NOT_TRACKED,

    /** El producto lleva lotes, y esta versión todavía no transfiere por lote. */
    LOTS_NOT_SUPPORTED,

    /** La cantidad es cero o negativa, o tiene decimales en una unidad que no los admite. */
    INVALID_QUANTITY,

    /** La cantidad pasa del tope de una línea. */
    QUANTITY_TOO_LARGE,
}

/** Una transferencia por guardar, activa. [businessDate] es la fecha de negocio de [occurredAt]. */
data class NewStockTransfer(
    val fromLocationId: Long,
    val toLocationId: Long,
    val note: String?,
    val occurredAt: Instant,
    val businessDate: BusinessDate,
    val createdAt: Instant,
)

/** Una línea por guardar: [quantity] de [productId], del lote [lotId] si el producto lleva lotes. */
data class NewStockTransferLine(val productId: Long, val lotId: Long?, val quantity: QuantityMilli)

/**
 * Una transferencia guardada. No se edita ni se borra: solo se anula, una vez, con [voiding].
 * Mover stock no cambia el saldo total, el costo ni el valor de ningún producto.
 */
data class StockTransfer(val id: Long, val content: NewStockTransfer, val voiding: Voiding?) {
    /** Activa o anulada, según [voiding]. */
    val status: DocumentStatus get() = DocumentStatus.of(voiding)
}

/** Una línea guardada de la transferencia [transferId]. Nunca cambia, ni al anular. */
data class StockTransferLine(val id: Long, val transferId: Long, val line: NewStockTransferLine)

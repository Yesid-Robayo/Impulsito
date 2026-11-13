package io.github.yesidrobayo.shopledger.domain.model.returns

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.Voiding
import kotlin.time.Instant

/**
 * La cabecera de una devolución a un proveedor que el caso de uso va a confirmar, todavía sin id.
 *
 * [purchaseId] es la compra de la que sale la mercancía, si la persona la eligió, y [supplierId] el
 * proveedor, obligatorio con [PurchaseReturnResolution.SUPPLIER_CREDIT]. [total] es lo que el
 * proveedor devuelve o deja a favor: la suma de los totales de las líneas. [moneyAccountId] es la
 * cuenta a la que entra un reembolso, solo si la hubo y el total no es cero.
 */
data class NewPurchaseReturn(
    val purchaseId: Long?,
    val supplierId: Long?,
    val resolution: PurchaseReturnResolution,
    val total: Money,
    val moneyAccountId: Long?,
    val note: String?,
    val occurredAt: Instant,
    val businessDate: BusinessDate,
    val createdAt: Instant,
)

/**
 * Una devolución a un proveedor guardada. Nunca se edita: solo se anula, y entonces [voiding]
 * guarda cuándo y por qué.
 */
data class PurchaseReturn(val id: Long, val content: NewPurchaseReturn, val voiding: Voiding?) {
    /** Activa o anulada, según [voiding]. */
    val status: DocumentStatus get() = DocumentStatus.of(voiding)
}

/**
 * Una línea de una devolución a un proveedor que el caso de uso va a guardar, todavía sin id.
 *
 * [quantity] va en milésimas de la unidad base del producto, que es la del libro de stock.
 * [unitCost] es el costo por unidad base con que sale, congelado: el de la línea de compra, o el
 * costo promedio del momento sin línea. [lineTotal] es lo que esta línea suma al total de la
 * devolución: con línea de compra, su parte de lo pagado por esa línea, impuesto incluido; sin
 * línea, el costo con que sale del stock. [purchaseLineId] es la línea de compra de la que sale, si
 * la devolución tiene compra.
 */
data class NewPurchaseReturnLine(
    val purchaseLineId: Long?,
    val productId: Long,
    val lotId: Long?,
    val quantity: QuantityMilli,
    val unitCost: Money,
    val lineTotal: Money,
)

/** Una línea guardada. Nunca se edita ni se borra, ni siquiera al anular la devolución. */
data class PurchaseReturnLine(
    val id: Long,
    val purchaseReturnId: Long,
    val content: NewPurchaseReturnLine,
)

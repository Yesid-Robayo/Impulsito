package io.github.yesidrobayo.shopledger.domain.model.returns

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli

/**
 * Lo que la persona pide registrar como devolución a un proveedor.
 *
 * Con [purchaseId], las líneas salen de esa compra, cada una con su línea de compra. Sin compra,
 * cada línea es un producto suelto. [supplierId] es el proveedor; con compra, si se escribe, tiene
 * que ser el de la compra, y si la compra no tiene proveedor, se puede elegir uno. [moneyAccountId]
 * es la cuenta a la que entra un reembolso, o `null` si la persona no lleva cuentas; con crédito
 * se ignora. [note] es opcional.
 */
data class SupplierReturnRequest(
    val lines: List<SupplierReturnLineRequest>,
    val resolution: PurchaseReturnResolution,
    val purchaseId: Long? = null,
    val supplierId: Long? = null,
    val moneyAccountId: Long? = null,
    val note: String? = null,
)

/** Una línea pedida: cuánto se devuelve, en milésimas de la unidad base, y de dónde sale. */
sealed interface SupplierReturnLineRequest {
    val quantity: QuantityMilli

    /** Sale de la línea de compra [purchaseLineId], al costo de esa línea. */
    data class FromPurchaseLine(val purchaseLineId: Long, override val quantity: QuantityMilli) :
        SupplierReturnLineRequest

    /** Un producto sin compra de referencia, que sale al costo de salida del momento. */
    data class Loose(val productId: Long, override val quantity: QuantityMilli) :
        SupplierReturnLineRequest
}

/** Por qué no se registra una línea de una devolución a proveedor. */
enum class SupplierReturnLineRejection {
    /** El producto ya no existe. */
    PRODUCT_NOT_FOUND,

    /** La línea de compra no es de la compra elegida, o una línea suelta llegó con compra. */
    NOT_IN_PURCHASE,

    /** Una línea de compra llegó sin compra elegida. */
    PURCHASE_REQUIRED,

    /** El producto no lleva stock propio, como un servicio o un combo: no hay libro que restar. */
    NOT_STOCKED,

    /** Lo devuelto pasa de lo que queda en el lote con código de su línea de compra. */
    ABOVE_LOT_STOCK,

    /** La cantidad no es positiva o pasa del máximo de una línea. */
    INVALID_QUANTITY,

    /** La unidad base del producto no admite fracciones, y la cantidad las tiene. */
    FRACTION_NOT_ALLOWED,

    /** El costo o el total de la línea no caben en sus topes. Solo sale con cifras absurdas. */
    AMOUNT_TOO_LARGE,
}

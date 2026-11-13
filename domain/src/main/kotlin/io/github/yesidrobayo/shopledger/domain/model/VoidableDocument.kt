package io.github.yesidrobayo.shopledger.domain.model

/**
 * Los documentos que se anulan en vez de borrarse, con los motivos que admite cada uno.
 *
 * Una devolución de cliente solo tiene sentido en una venta, y una devolución al proveedor solo en
 * una compra. Un gasto no se devuelve: se anula por error, por deshacer o por otro motivo, y una
 * devolución a proveedor tampoco: devolver lo devuelto es anularla. Las
 * guardas de la base repiten esta lista; aquí se revisa antes, para que el caso de uso devuelva un
 * motivo en vez de chocar con la base.
 */
enum class VoidableDocument {
    SALE,
    PURCHASE,
    EXPENSE,
    PURCHASE_RETURN,
    ;

    /** Si un documento de este tipo se puede anular con [reason]. */
    fun allows(reason: VoidReason): Boolean = when (reason) {
        VoidReason.ENTRY_ERROR, VoidReason.UNDO, VoidReason.OTHER -> true
        VoidReason.CUSTOMER_RETURN -> this == SALE
        VoidReason.SUPPLIER_RETURN -> this == PURCHASE
    }
}

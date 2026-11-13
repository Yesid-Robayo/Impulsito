package io.github.yesidrobayo.shopledger.domain.model.payable

/**
 * El tipo de una fila del libro de un proveedor, que fija su signo y su origen. Positivo aumenta lo
 * que el negocio le debe.
 */
enum class SupplierLedgerEntryType {
    /** Deuda que ya existía al empezar a usar el libro. Puede ser de cualquier signo. */
    OPENING_BALANCE,

    /** La parte no pagada de una compra: siempre suma, y lleva la compra. */
    CREDIT_PURCHASE,

    /** Un pago posterior a la compra: siempre resta. */
    PAYMENT,

    /** El crédito que deja una devolución al proveedor: siempre resta, y lleva la devolución. */
    RETURN_CREDIT,

    /** Una corrección, de cualquier signo, con nota obligatoria. */
    ADJUSTMENT,

    /** Compensa otra fila con el monto opuesto. Una fila se compensa una sola vez. */
    ENTRY_VOID,
}

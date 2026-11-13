package io.github.yesidrobayo.shopledger.domain.model.customer

/**
 * Por qué cambia lo que debe un cliente. El signo de cada tipo es fijo, salvo los que corrigen:
 * positivo aumenta la deuda, negativo la baja o deja saldo a favor.
 */
enum class CustomerLedgerEntryType {
    /** Lo que ya debía, o tenía a favor, al empezar a llevar su cuenta aquí. Cualquier signo. */
    OPENING_BALANCE,

    /** Lo que quedó a crédito en una venta. Siempre suma, y lleva la venta. */
    CREDIT_SALE,

    /** Un abono o un anticipo. Siempre resta. */
    PAYMENT,

    /** Lo que una devolución deja a favor. Siempre resta, y lleva la devolución. */
    RETURN_CREDIT,

    /** Una corrección hecha a mano, con su nota obligatoria. Cualquier signo. */
    ADJUSTMENT,

    /** Compensa otra fila con el monto opuesto. Una fila se compensa una sola vez. */
    ENTRY_VOID,
    ;

    /** Si el monto puede ser [positive]: los tipos de signo fijo solo admiten el suyo. */
    fun admits(positive: Boolean): Boolean = when (this) {
        CREDIT_SALE -> positive
        PAYMENT, RETURN_CREDIT -> !positive
        OPENING_BALANCE, ADJUSTMENT, ENTRY_VOID -> true
    }
}

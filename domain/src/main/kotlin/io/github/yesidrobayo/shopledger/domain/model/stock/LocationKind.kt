package io.github.yesidrobayo.shopledger.domain.model.stock

/**
 * Qué clase de lugar es una ubicación del stock. Es un valor guardado en la base: su nombre no
 * cambia nunca.
 *
 * Solo describe el lugar: el stock, el costo y las ventas funcionan igual en cualquiera.
 */
enum class LocationKind {
    /** Un local de venta. */
    STORE,

    /** Un depósito o una bodega, de donde sale la mercancía hacia los puntos de venta. */
    WAREHOUSE,

    /** Un puesto, como el de una feria o un mercado. */
    STALL,

    /** Cualquier otro lugar, como la casa de la persona. */
    OTHER,
}

package io.github.yesidrobayo.shopledger.domain.model.settings

/**
 * Cómo trata el negocio el impuesto de sus ventas. Es un valor guardado en la base: su nombre no
 * cambia nunca.
 *
 * No nombra ningún impuesto ni ninguna ley: la tasa y su nombre los escribe la persona.
 */
enum class TaxMode {
    /** Sin impuesto: el precio es el monto neto. El inicial. */
    NONE,

    /** El precio ya incluye el impuesto, que se separa del neto. */
    INCLUDED,

    /** El impuesto se suma al precio. */
    ADDED,
}

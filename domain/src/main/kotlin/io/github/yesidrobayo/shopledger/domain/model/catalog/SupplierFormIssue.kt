package io.github.yesidrobayo.shopledger.domain.model.catalog

/** Lo que impide guardar un proveedor tal como se escribió. */
enum class SupplierFormIssue {
    /** El nombre queda vacío tras recortar espacios, o hecho solo de marcas sin letra. */
    NAME_EMPTY,

    /** El nombre pasa de 80 caracteres. */
    NAME_TOO_LONG,

    /** El teléfono pasa de 30 caracteres. */
    PHONE_TOO_LONG,

    /** El teléfono lleva algo distinto de dígitos, espacios y los signos de marcar. */
    PHONE_FORMAT,

    /** El correo pasa de 120 caracteres. */
    EMAIL_TOO_LONG,

    /** El correo no tiene una sola arroba con texto a cada lado. */
    EMAIL_FORMAT,

    /** Las notas pasan de 500 caracteres. */
    NOTES_TOO_LONG,

    /** El plazo de pago es negativo o pasa de 3650 días. */
    PAYMENT_TERMS_OUT_OF_RANGE,
}

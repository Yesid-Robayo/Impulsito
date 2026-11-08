package io.github.yesidrobayo.shopledger.domain.model.payment

/**
 * Por qué no se guarda lo escrito en un método de pago. Cada uno es un error de su campo, y la
 * pantalla los muestra todos a la vez, sin borrar nada de lo escrito.
 */
enum class PaymentMethodInputError {
    /** El nombre está vacío, o solo tiene espacios o marcas. */
    NAME_BLANK,

    /** El nombre pasa de 40 caracteres. */
    NAME_TOO_LONG,

    /** Otro método vigente ya tiene ese nombre, sin contar mayúsculas ni tildes. */
    NAME_TAKEN,

    /** La comisión en porcentaje no está entre 0 % y 100 %. */
    FEE_RATE_OUT_OF_RANGE,

    /** La comisión fija es negativa o pasa del tope de un precio. */
    FEE_FIXED_OUT_OF_RANGE,

    /** Los días para acreditar no están entre 0 y 3650. */
    SETTLEMENT_DAYS_OUT_OF_RANGE,

    /** El recargo o descuento no está entre −100 % y +100 %. */
    PRICE_ADJUSTMENT_OUT_OF_RANGE,

    /** Las instrucciones de cobro pasan de 500 caracteres. */
    INSTRUCTIONS_TOO_LONG,

    /** La cuenta de dinero elegida no existe o está archivada. */
    MONEY_ACCOUNT_UNAVAILABLE,

    /**
     * Se pidió cambiar el tipo de un método con ventas. Sus ventas se registraron con ese tipo, y
     * cambiarlo mezclaría en los reportes lo que se cobró de dos formas distintas.
     */
    KIND_LOCKED,
}

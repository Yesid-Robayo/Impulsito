package io.github.yesidrobayo.shopledger.domain.model.sale

/** Por qué un pago escrito al cobrar con varios métodos no se puede guardar. */
enum class PaymentRejection {
    /**
     * El método ya tiene otro pago en esta venta. Cada método va una sola vez, con todo lo que
     * cubre, para que el reporte por método no lo cuente partido.
     */
    DUPLICATE_METHOD,

    /** El monto es negativo o pasa del tope de un documento. */
    INVALID_AMOUNT,

    /**
     * La comisión escrita es negativa o pasa del tope de un documento, o el pago es de cero y la
     * comisión no: por ese medio no se cobró nada.
     */
    INVALID_FEE,

    /**
     * El descuento del método, con el impuesto que deja de cobrarse, baja el total más que lo que
     * el pago cubría: el pago quedaría negativo.
     */
    BELOW_ZERO_AFTER_ADJUSTMENT,

    /** Lo recibido es negativo o pasa del tope de un documento. */
    INVALID_TENDERED,

    /** Lo recibido solo existe en un pago en efectivo: los demás métodos cobran el monto exacto. */
    TENDER_NOT_CASH,

    /** Lo recibido no alcanza para lo que cubre este pago en efectivo, ya redondeado. */
    TENDERED_BELOW_AMOUNT,

    /** Lo recibido en otra moneda tiene decimales, monto o cotización fuera de rango. */
    INVALID_FOREIGN,

    /** La otra moneda es la del negocio: no hay nada que convertir. */
    FOREIGN_SAME_CURRENCY,

    /** Lo recibido en otra moneda, pasado a la del negocio, no es lo que dice el pago. */
    FOREIGN_MISMATCH,
}

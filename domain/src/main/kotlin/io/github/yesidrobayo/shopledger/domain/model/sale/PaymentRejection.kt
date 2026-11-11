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
}

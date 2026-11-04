package io.github.yesidrobayo.shopledger.domain.model.catalog

/**
 * Cómo calcula los precios nuevos una actualización masiva. Es un valor guardado en la base: su
 * nombre no cambia nunca.
 */
enum class BulkPriceMode {
    /** Sube o baja cada precio un porcentaje. */
    PERCENT,

    /** Suma o resta un monto fijo a cada precio. */
    AMOUNT,

    /** Fija cada precio para lograr un margen objetivo sobre su costo. */
    TARGET_MARGIN,

    /** Recalcula cada precio desde su precio de referencia y una cotización. */
    REFERENCE_RATE,
}

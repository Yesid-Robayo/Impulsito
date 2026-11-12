package io.github.yesidrobayo.shopledger.domain.model.stock

/**
 * El motivo de un ajuste de stock. Es un valor guardado en la base: su nombre no cambia nunca.
 *
 * Todo ajuste lleva motivo, porque una merma sin explicación no se puede revisar después. Con
 * [OTHER], la nota es obligatoria. Todos cuentan como merma, con su signo: lo que sale del
 * inventario sin venderse deja de ser un activo del negocio, también lo que se regala o se consume.
 */
enum class StockAdjustmentReason {
    /** Un conteo físico encontró otra cantidad. */
    COUNT_CORRECTION,

    /** Mercancía dañada. */
    DAMAGED,

    /** Mercancía vencida. */
    EXPIRED,

    /** Mercancía perdida o robada. */
    LOST_OR_STOLEN,

    /** Consumo propio del negocio o de la persona. */
    OWN_USE,

    /** Regalo o muestra. */
    GIFT_OR_SAMPLE,

    /** Otro motivo, explicado en la nota. */
    OTHER,
    ;

    /**
     * Si un ajuste con este motivo puede sumar unidades. Un conteo encuentra de más o de menos, y
     * [OTHER] admite los dos sentidos. Los demás solo restan: lo dañado o lo regalado nunca entra.
     */
    val allowsIncrease: Boolean
        get() = when (this) {
            COUNT_CORRECTION, OTHER -> true
            DAMAGED, EXPIRED, LOST_OR_STOLEN, OWN_USE, GIFT_OR_SAMPLE -> false
        }

    /** Si el ajuste exige una nota: sin ella, [OTHER] no dice nada de lo que pasó. */
    val requiresNote: Boolean
        get() = this == OTHER
}

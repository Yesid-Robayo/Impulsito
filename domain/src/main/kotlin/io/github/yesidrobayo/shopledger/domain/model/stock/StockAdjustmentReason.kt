package io.github.yesidrobayo.shopledger.domain.model.stock

/**
 * El motivo de un ajuste de stock. Es un valor guardado en la base: su nombre no cambia nunca.
 *
 * Todo ajuste lleva motivo, porque una merma sin explicación no se puede revisar después. Con
 * [OTHER], la nota es obligatoria.
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
}

package io.github.yesidrobayo.shopledger.domain.model.sale

/**
 * Qué hace una línea de venta con el stock. Es un valor guardado en la base: su nombre no cambia
 * nunca.
 *
 * Se copia en la línea al vender, desde el modo de stock del producto, para que una venta pasada
 * se anule igual aunque el producto cambie después.
 */
enum class LineStockEffect {
    /** Descuenta el stock del propio producto. */
    DIRECT,

    /** No mueve stock: un servicio o algo que no se cuenta. */
    NONE,

    /** Descuenta el stock de cada componente del combo, no el propio. */
    COMPONENTS,
}

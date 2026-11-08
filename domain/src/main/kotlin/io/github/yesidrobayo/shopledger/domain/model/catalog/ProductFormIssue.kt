package io.github.yesidrobayo.shopledger.domain.model.catalog

/**
 * Lo que impide guardar un producto o su inventario inicial tal como se escribió. El formulario
 * marca cada campo con su motivo antes de confirmar, todos a la vez.
 *
 * Los topes no son cifras de una moneda: protegen de desbordar las cuentas que vienen después, y
 * casi siempre una cifra fuera de ellos es un error al escribir.
 */
enum class ProductFormIssue {
    /** El nombre queda vacío tras recortar espacios, o hecho solo de marcas sin letra. */
    NAME_EMPTY,

    /** El nombre pasa de 80 caracteres. */
    NAME_TOO_LONG,

    /** El precio de venta es negativo o pasa del tope de precio por unidad. */
    SALE_PRICE_OUT_OF_RANGE,

    /** El costo es negativo o pasa del tope de costo por unidad. */
    COST_OUT_OF_RANGE,

    /** Un producto hecho de componentes no lleva costo propio: sale de sus componentes. */
    COST_NOT_APPLICABLE,

    /** El stock inicial es negativo, o cero donde debe mover algo, o pasa del tope de una línea. */
    INITIAL_STOCK_OUT_OF_RANGE,

    /** El stock inicial tiene decimales y la unidad no los admite. */
    INITIAL_STOCK_FRACTION_NOT_ALLOWED,

    /** El mínimo es negativo o pasa del tope de stock. */
    MIN_STOCK_OUT_OF_RANGE,

    /** El mínimo tiene decimales y la unidad no los admite. */
    MIN_STOCK_FRACTION_NOT_ALLOWED,

    /** Un producto sin stock propio no lleva stock inicial ni mínimo. */
    STOCK_NOT_APPLICABLE,

    /** El código de barras pasa de 64 caracteres. */
    BARCODE_TOO_LONG,
}

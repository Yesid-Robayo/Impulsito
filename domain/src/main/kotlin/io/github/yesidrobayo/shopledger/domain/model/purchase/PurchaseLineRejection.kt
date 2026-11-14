package io.github.yesidrobayo.shopledger.domain.model.purchase

/** Por qué no se registra una línea de compra. */
enum class PurchaseLineRejection {
    /** El producto ya no existe. */
    PRODUCT_NOT_FOUND,

    /** El producto está archivado: se reactiva antes de comprarlo. */
    PRODUCT_ARCHIVED,

    /** El producto no lleva stock propio, como un servicio o un combo: no hay libro que sumar. */
    NOT_STOCKED,

    /** La línea trae lote, y el producto no se controla por lotes. */
    LOT_NOT_TRACKED,

    /** El lote elegido no existe o es de otro producto. */
    LOT_NOT_FOUND,

    /** El código del lote nuevo está en blanco o pasa del largo máximo. */
    INVALID_LOT_CODE,

    /** El producto ya tiene un lote con ese código, o la compra lo crea dos veces. */
    LOT_CODE_REPEATED,

    /** La unidad elegida no es la base ni una conversión de compra del producto. */
    UNIT_UNAVAILABLE,

    /**
     * La cantidad no es positiva, pasa del máximo de una línea, o en la unidad base redondea a cero.
     */
    INVALID_QUANTITY,

    /** La unidad del producto no admite fracciones, y la cantidad las tiene. */
    FRACTION_NOT_ALLOWED,

    /** El costo o el total escritos son negativos. Una bonificación vale cero. */
    NEGATIVE_AMOUNT,

    /** El costo de una unidad entera pasa del máximo de un costo unitario. */
    UNIT_COST_TOO_LARGE,

    /** El total de la línea pasa del máximo de un documento. */
    LINE_TOTAL_TOO_LARGE,

    /** El stock del producto, o su valor, quedarían por encima de su tope. */
    STOCK_ABOVE_LIMIT,
}

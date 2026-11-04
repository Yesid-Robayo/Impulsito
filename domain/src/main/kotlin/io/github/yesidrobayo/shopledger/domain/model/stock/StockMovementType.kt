package io.github.yesidrobayo.shopledger.domain.model.stock

/**
 * Tipo de una fila del libro de stock.
 *
 * El libro es la verdad del stock y sus filas nunca se editan ni se borran: un error se corrige con
 * una fila compensatoria que apunta a la que anula. Por eso cada tipo que nace de un documento
 * tiene su anulación. El tipo decide qué hace la fila con el costo promedio, y el signo de su
 * cantidad.
 *
 * El nombre de cada valor se guarda tal cual en la base y viaja en las copias: renombrarlo dejaría
 * ilegibles los libros ya escritos.
 */
enum class StockMovementType {
    /** Mercancía que ya existía antes de usar la app. Suma y entra al costo como una compra. */
    OPENING_BALANCE,

    /** Línea de una compra. Suma, con el total de la línea como valor. */
    PURCHASE,

    /** Línea de una venta, o un componente de ella. Resta y sale al costo del momento. */
    SALE,

    /** Ajuste por conteo, daño, vencimiento, pérdida u otro motivo. Suma o resta. */
    ADJUSTMENT,

    /** Corrección manual del costo. No mueve unidades: lleva el costo nuevo. */
    COST_CORRECTION,

    /** Anula una compra. */
    PURCHASE_VOID,

    /** Anula una venta. */
    SALE_VOID,

    /** Anula un ajuste o un inventario inicial. */
    ADJUSTMENT_VOID,

    /** Lo que un cliente devuelve y vuelve al stock, con el costo congelado de su venta. */
    SALE_RETURN,

    /** Anula una devolución de cliente. */
    SALE_RETURN_VOID,

    /** Lo que se devuelve al proveedor. Resta, con el costo de su línea de compra. */
    PURCHASE_RETURN,

    /** Anula una devolución al proveedor. */
    PURCHASE_RETURN_VOID,

    /** Insumo que consume una producción. Resta como una venta. */
    PRODUCTION_OUT,

    /** Lo que sale de una producción. Suma, con el costo de sus insumos como valor. */
    PRODUCTION_IN,

    /** Anula una salida o una entrada de producción. */
    PRODUCTION_VOID,

    /** Sale de una ubicación hacia otra del mismo negocio. No cambia el costo. */
    TRANSFER_OUT,

    /** Llega a una ubicación desde otra del mismo negocio. No cambia el costo. */
    TRANSFER_IN,

    /** Anula una salida o una llegada de transferencia. */
    TRANSFER_VOID,
}

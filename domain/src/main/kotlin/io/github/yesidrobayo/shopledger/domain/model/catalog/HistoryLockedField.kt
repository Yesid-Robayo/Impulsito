package io.github.yesidrobayo.shopledger.domain.model.catalog

/**
 * Lo que ya no cambia en un producto con historia: filas en el libro de stock, líneas de venta o
 * ventas como componente de otro producto, aunque estén anuladas.
 *
 * Las filas ya escritas quedaron en esa unidad, en ese modo y con o sin lotes. Cambiar cualquiera
 * de los tres dejaría un libro con filas que dicen cosas distintas, y el saldo y el costo dejarían
 * de cuadrar con él.
 */
enum class HistoryLockedField {
    /** La unidad base, la del libro de stock. */
    BASE_UNIT,

    /** El modo de stock: con libro propio, sin stock o por componentes. */
    STOCK_MODE,

    /** Si el producto lleva lotes: un libro no mezcla filas con lote y sin lote. */
    TRACKS_LOTS,
}

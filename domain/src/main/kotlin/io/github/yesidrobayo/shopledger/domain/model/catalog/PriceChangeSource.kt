package io.github.yesidrobayo.shopledger.domain.model.catalog

/**
 * De dónde salió un cambio de precio. Es un valor guardado en la base: su nombre no cambia nunca.
 */
enum class PriceChangeSource {
    /** El precio con que nació el producto. */
    INITIAL,

    /** La persona cambió el precio de un producto. */
    MANUAL,

    /** Una actualización masiva de varios productos a la vez. */
    BULK,

    /** Deshacer una actualización masiva. */
    BULK_UNDO,

    /** Una importación de datos. */
    IMPORT,
    ;

    /**
     * Si sus filas pertenecen a un lote: una actualización masiva y su deshacer se agrupan, para
     * deshacer la actualización entera y no producto por producto.
     */
    val isBatch: Boolean
        get() = when (this) {
            BULK, BULK_UNDO -> true
            INITIAL, MANUAL, IMPORT -> false
        }
}

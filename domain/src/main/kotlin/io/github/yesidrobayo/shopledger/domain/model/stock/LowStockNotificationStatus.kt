package io.github.yesidrobayo.shopledger.domain.model.stock

/**
 * Qué pasó con el aviso de una alerta de stock bajo. Es un valor guardado en la base: su nombre no
 * cambia nunca.
 *
 * La tabla de alertas también es la bandeja de salida de los avisos: la alerta se crea dentro de la
 * transacción, y el aviso se lanza después de confirmarla. Si el proceso muere en medio, la alerta
 * sigue pendiente y se avisa en el siguiente arranque.
 */
enum class LowStockNotificationStatus {
    /** Falta lanzar el aviso. */
    PENDING,

    /** El aviso se lanzó. */
    SHOWN,

    /**
     * No se avisa: el estado bajo llegó sin una venta o compra que lo cruzara, como un mínimo que
     * sube o un producto que nace bajo, o la persona apagó los avisos de stock.
     */
    SUPPRESSED,

    /** Se quiso avisar, pero el sistema no lo deja: el permiso o el canal están apagados. */
    BLOCKED,
}

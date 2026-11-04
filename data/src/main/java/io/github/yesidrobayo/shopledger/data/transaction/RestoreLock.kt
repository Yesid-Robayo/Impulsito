package io.github.yesidrobayo.shopledger.data.transaction

/**
 * Dice si hay una restauración en curso, para que ninguna escritura entre mientras la base se
 * reemplaza.
 *
 * Una venta escrita entre la copia de seguridad y el reemplazo se perdería sin aviso, aunque la
 * pantalla de progreso lo impida: una notificación puede abrir otra pantalla. Por eso
 * [RoomTransactionRunner] pregunta antes de abrir cada transacción de escritura y, si la respuesta
 * es sí, no empieza.
 *
 * Lo implementa la restauración con su archivo de cerrojo, que ella misma crea antes de reemplazar
 * la base y abandona al arrancar un proceso nuevo sin diario: un cerrojo huérfano no debe bloquear
 * las ventas para siempre. Mientras la restauración no existe, nadie lo tiene tomado.
 */
internal fun interface RestoreLock {
    /** Si el cerrojo de la restauración está tomado. Puede leer el disco: es `suspend`. */
    suspend fun isHeld(): Boolean
}

package io.github.yesidrobayo.shopledger.data.transaction

/**
 * Cuenta un cambio sin respaldar: es el único punto donde una escritura confirmada se vuelve
 * pendiente de copia.
 *
 * Solo lo llama [RoomTransactionRunner], una vez por escritura que termina en confirmación, dentro
 * de la misma transacción y justo antes de confirmarla. Así una venta y su marca de cambio se
 * guardan juntas o no se guarda ninguna: nunca queda una venta sin contar, ni un cambio contado que
 * no ocurrió. Una regla que rechaza, o una falla, no llegan aquí.
 *
 * Si no puede contar, lanza: la transacción entera se deshace, porque un dato guardado sin su marca
 * podría no llegar nunca a una copia.
 *
 * Lo implementa [BackupStateChangeCounter], sobre la fila del estado del respaldo. Las pruebas lo
 * envuelven para ver cada llamada.
 */
internal fun interface ChangeCounter {
    /** Suma un cambio, con [nowEpochMillis] como instante del primero que aún no tiene copia. */
    suspend fun markChanged(nowEpochMillis: Long)
}

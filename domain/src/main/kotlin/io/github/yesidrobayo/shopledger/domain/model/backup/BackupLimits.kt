package io.github.yesidrobayo.shopledger.domain.model.backup

/**
 * Los topes de tamaño de una copia, iguales en el teléfono que la crea y en el que la restaura.
 *
 * Son el mismo número en los dos caminos a propósito: si una copia pudiera crearse más grande de lo
 * que la restauración acepta leer, el tope de lectura se volvería pérdida de datos. Por eso una
 * copia que no cabe no se sube, y la persona recibe un aviso antes de llegar ahí.
 */
object BackupLimits {
    /**
     * El mayor archivo de copia, comprimido y tal como queda en el destino, que se sube y que se
     * acepta bajar: 512 MiB. La compresión deja la base en cerca de un tercio, así que alcanza para
     * bases mucho más grandes que el volumen de referencia.
     */
    const val MAX_BACKUP_DOWNLOAD_BYTES: Long = 512L * 1024 * 1024

    /**
     * La mayor base, sin comprimir, que se acepta restaurar: 1 GiB, más de siete veces el volumen
     * de referencia. Una copia que diga ser más grande no cuenta como copia.
     */
    const val MAX_RESTORE_DECOMPRESSED_BYTES: Long = 1024L * 1024 * 1024
}

package io.github.yesidrobayo.shopledger.domain.rule.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupLimits
import io.github.yesidrobayo.shopledger.domain.model.backup.DeviceStorage

/**
 * Los avisos de espacio, que llegan antes de que algo falle. Ninguno detiene una copia, deja un
 * fallo ni cambia el estado del respaldo: lo que viene después, cuando la copia ya no se puede
 * hacer, son los fallos de poco espacio, copia demasiado grande y servicio lleno.
 *
 * Los umbrales son valores de diseño, iguales en cualquier teléfono.
 */
object StorageNoticeRule {
    /** Lo menos que debe quedar libre, sea cual sea el tamaño de la base: 200 MiB. */
    const val MIN_FREE_BYTES: Long = 200L * 1024 * 1024

    /**
     * Cuántas veces la base debe caber en lo libre: la instantánea, su archivo comprimido y el
     * margen de una restauración ocupan cerca de eso.
     */
    const val FREE_DATABASE_MULTIPLE: Long = 3

    /**
     * Queda poco espacio en [storage]: lo libre es menor que el mayor entre [MIN_FREE_BYTES] y
     * [FREE_DATABASE_MULTIPLE] veces la base. Una base tan grande que el triple no cabe en un
     * número siempre avisa.
     */
    fun lowDeviceStorage(storage: DeviceStorage): Boolean {
        val database = storage.databaseBytes.coerceAtLeast(0)
        if (database > Long.MAX_VALUE / FREE_DATABASE_MULTIPLE) return true
        val needed = maxOf(MIN_FREE_BYTES, database * FREE_DATABASE_MULTIPLE)
        return storage.usableBytes < needed
    }

    /**
     * La copia se acerca al límite: el archivo de la cabeza, [headFileBytes], llega al 80 % del
     * mayor que se sube y se baja, o la base, [databaseBytes], al 80 % de la mayor que se
     * restaura. Mira los dos porque la compresión deja la base en cerca de un tercio: solo con la
     * cabeza, el límite de la base llegaría antes que el aviso. Sin uno de los dos tamaños, decide
     * con el otro.
     */
    fun nearCopyLimit(headFileBytes: Long?, databaseBytes: Long?): Boolean {
        val headNear = headFileBytes != null && headFileBytes >= HEAD_NEAR_LIMIT_BYTES
        val databaseNear = databaseBytes != null && databaseBytes >= DATABASE_NEAR_LIMIT_BYTES
        return headNear || databaseNear
    }

    /** El 80 % del mayor archivo de copia que se sube y se baja. */
    private val HEAD_NEAR_LIMIT_BYTES = eightyPercent(BackupLimits.MAX_BACKUP_DOWNLOAD_BYTES)

    /** El 80 % de la mayor base que se restaura. */
    private val DATABASE_NEAR_LIMIT_BYTES =
        eightyPercent(BackupLimits.MAX_RESTORE_DECOMPRESSED_BYTES)

    /** El 80 % de [limit], redondeado hacia arriba: llegar a ese número es llegar al 80 %. */
    private fun eightyPercent(limit: Long): Long = (limit * 4 + 4) / 5
}

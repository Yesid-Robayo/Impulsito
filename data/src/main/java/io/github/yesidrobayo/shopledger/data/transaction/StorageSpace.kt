package io.github.yesidrobayo.shopledger.data.transaction

import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext

/**
 * Dice si queda poco espacio donde vive la base.
 *
 * Con el disco casi lleno, SQLite no siempre responde que está lleno: también falla con errores de
 * lectura o escritura. Sin esta pregunta, la persona vería un error genérico cuando lo que puede
 * hacer es liberar espacio.
 */
internal fun interface StorageSpace {
    /** Si queda poco espacio. Lee el disco: es `suspend`. */
    suspend fun isLow(): Boolean
}

/**
 * El espacio de la carpeta de [databaseFile], con el mismo margen del aviso de poco almacenamiento:
 * el mayor entre 200 MiB y tres veces el tamaño de la base, que cubre lo que puede coincidir en
 * disco al respaldar y al migrar.
 *
 * Una carpeta que no existe o no se puede leer da cero bytes libres, y cuenta como poco espacio.
 */
internal class DatabaseStorageSpace(
    private val databaseFile: File,
    private val ioContext: CoroutineContext,
) : StorageSpace {
    override suspend fun isLow(): Boolean = withContext(ioContext) {
        val usable = databaseFile.absoluteFile.parentFile?.usableSpace ?: 0L
        val margin = maxOf(MIN_FREE_BYTES, Math.multiplyExact(databaseFile.length(), SIZE_FACTOR))
        usable < margin
    }

    private companion object {
        const val MIN_FREE_BYTES = 200L * 1024L * 1024L
        const val SIZE_FACTOR = 3L
    }
}

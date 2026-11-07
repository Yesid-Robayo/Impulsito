package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.domain.port.Clock
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Las bases originales que una restauración apartó sin copia de seguridad, en
 * `restore/damaged-original/<instante>/`, una carpeta por restauración. Se conservan 30 días, nunca
 * se suben, y son lo único que queda para un rescate manual.
 *
 * - **Nunca pisa una anterior.** Cada restauración aparta en una carpeta nueva, con el instante del
 *   reloj del teléfono como nombre. Si ya existe una con ese nombre, usa el siguiente milisegundo:
 *   una segunda restauración dentro de los 30 días no puede borrar la base de la primera.
 * - **Un solo renombrado.** Se mueve la carpeta `previous/` entera, con la base, su `-wal` y su
 *   `-shm`: una muerte del proceso deja todo en `previous/` o todo en su carpeta nueva, nunca la
 *   base separada de su `-wal`.
 * - **30 días, sin un reloj atrasado.** La limpieza diaria borra las carpetas de 30 días o más. Una
 *   carpeta del futuro, con el reloj atrasado, no está vencida. Una carpeta que no tiene la forma
 *   de un instante no es de la app, y no se toca.
 *
 * Lee y escribe el disco: se llama fuera del hilo principal.
 */
internal class DamagedOriginals(
    private val files: RestoreFiles,
    private val ops: DurableFileOps,
    private val clock: Clock,
) {
    /**
     * Aparta la base que espera en `previous/`, con sus archivos de al lado, en una carpeta nueva.
     * Devuelve esa carpeta, o `null` si en `previous/` no había base.
     */
    fun setAsidePrevious(): File? {
        if (!files.previous.exists()) return null
        val target = freshFolder()
        ops.move(files.previousDirectory, target)
        return target
    }

    /** Las carpetas apartadas, de la más vieja a la más nueva según su nombre. */
    fun folders(): List<File> = files.damagedDirectory.listFiles().orEmpty()
        .filter { it.isDirectory && takenAt(it) != null }
        .sortedBy { takenAt(it) }

    /** Borra las carpetas de [RETENTION] o más en [now]. La llama la limpieza diaria. */
    fun pruneExpired(now: Instant) {
        for (folder in folders()) {
            val age = now.toEpochMilliseconds() - (takenAt(folder) ?: continue)
            if (age >= 0 && age >= RETENTION.inWholeMilliseconds) delete(folder)
        }
    }

    private fun freshFolder(): File {
        var millis = clock.nowEpochMillis()
        while (File(files.damagedDirectory, millis.toString()).exists()) millis++
        return File(files.damagedDirectory, millis.toString())
    }

    /** Borra la carpeta con lo que tenga. Sus archivos son planos: la app no crea subcarpetas. */
    private fun delete(folder: File) {
        for (file in folder.listFiles().orEmpty()) ops.delete(file)
        ops.delete(folder)
    }

    private fun takenAt(folder: File): Long? =
        folder.name.takeIf { DECIMAL.matches(it) }?.toLongOrNull()

    companion object {
        /** Cuánto se conserva una base apartada. */
        val RETENTION: Duration = 30.days

        /** El nombre del diario ilegible dentro de la carpeta apartada, para soporte. */
        const val UNREADABLE_JOURNAL_NAME = "journal.bad"

        private val DECIMAL = Regex("0|[1-9][0-9]*")
    }
}

package io.github.yesidrobayo.shopledger.data.local

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import androidx.sqlite.execSQL
import io.github.yesidrobayo.shopledger.data.log.DataEvent
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.LogField
import io.github.yesidrobayo.shopledger.domain.port.info
import io.github.yesidrobayo.shopledger.domain.port.warn
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException

/** Un paso de la copia previa ya terminado en disco. Solo las pruebas lo escuchan. */
internal enum class PreMigrationStep {
    /** Se borró el temporal que hubiera dejado una copia anterior. */
    TEMPORARY_CLEARED,

    /** `VACUUM INTO` escribió el temporal. */
    COPIED,

    /** El temporal pasó a modo de diario `DELETE` y su `integrity_check` dio `ok`. */
    CHECKED,

    /** El temporal está sincronizado con el disco, todavía con su nombre de temporal. */
    SYNCED,

    /** El temporal ya tiene el nombre final, y la carpeta está sincronizada. */
    RENAMED,
}

/**
 * La copia previa a migrar: toda migración de la base va precedida por una copia local, para
 * volver a ella si un salto pierde filas sin fallar, algo que la transacción de Room no ve.
 *
 * Vive en `pre-migration/` de `noBackupFilesDir`, nunca se sube y se conserva solo la última. En
 * orden:
 * 1. **Se conserva la buena.** Si ya hay una copia y su `user_version` no es menor que el de la
 *    base, se deja como está y no se copia otra vez. Sin esta regla, una migración a medias dejaría
 *    una "copia previa" ya migrada, y como solo se conserva la última, se perdería la buena. La
 *    versión de la copia se lee de sus bytes, porque es un archivo en modo `DELETE` que nadie
 *    tiene abierto. Una copia ilegible cuenta como versión 0, y se reemplaza.
 * 2. **Espacio.** Si no cabe, no se copia ni se migra, y se dice cuánto falta. Hace falta lugar
 *    para la copia, más lo que la migración puede escribir en el `-wal`, que es como mucho el
 *    tamaño de la base, más un margen fijo para que el teléfono siga funcionando. Es un valor de
 *    diseño, no una medición.
 * 3. **Copia.** Borra el temporal de una copia que no terminó, corre `VACUUM INTO candidate.tmp`
 *    con una conexión propia, antes de que Room abra la base, y deja el temporal en modo `DELETE`,
 *    el de toda copia legítima, para que la validación de una restauración lo acepte.
 * 4. **Comprobación.** `integrity_check` debe dar `ok`, y el `user_version` de la copia debe ser el
 *    de la base.
 * 5. **Durabilidad.** Sincroniza el temporal con el disco, lo renombra a `candidate.db` en una sola
 *    operación y sincroniza la carpeta.
 *
 * Una muerte a mitad deja como mucho el temporal, nunca un archivo con el nombre final a medias, y
 * la copia anterior sigue entera hasta el renombrado. El arranque siguiente borra el temporal y
 * repite. Si algo falla, el temporal se borra y no se migra.
 *
 * @param usableBytes el espacio libre donde vive la copia. Solo las pruebas lo cambian.
 * @param afterStep se entera de cada paso terminado. Solo las pruebas lo usan, para cortar la copia
 *   justo después de un paso.
 */
internal class VacuumPreMigrationCopy(
    private val files: DatabaseFiles,
    private val log: EventLog,
    private val usableBytes: (File) -> Long = ::usableSpaceOf,
    private val afterStep: (PreMigrationStep) -> Unit = {},
) : PreMigrationCopy {
    override suspend fun prepare(databaseVersion: Int): PreMigrationResult {
        val candidate = files.preMigrationCandidate
        val keptVersion = if (candidate.isFile) fileUserVersion(candidate) else null
        if (keptVersion != null && keptVersion >= databaseVersion) {
            log.info(DataEvent.PRE_MIGRATION_COPY_KEPT, LogField.code("versión", keptVersion))
            return PreMigrationResult.Ready
        }
        val temporary = files.preMigrationTemporary
        return try {
            val required = requiredBytes()
            // El temporal de una copia que no terminó se borra antes de copiar: su lugar cuenta.
            val usable = usableBytes(files.noBackupDirectory) + temporaryBytes(temporary)
            // Quien pidió la copia registra cuánto falta, al ir al rescate.
            if (usable < required) return PreMigrationResult.NotEnoughSpace(required - usable)
            copy(temporary, candidate, databaseVersion)
            PreMigrationResult.Ready
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            log.warn(DataEvent.PRE_MIGRATION_COPY_FAILED, failure)
            deleteTemporary(temporary)
            PreMigrationResult.Failed
        }
    }

    private fun copy(temporary: File, candidate: File, databaseVersion: Int) {
        val started = TimeSource.Monotonic.markNow()
        deleteTemporary(temporary)
        val directory = files.preMigrationDirectory
        if (!directory.isDirectory) {
            if (!directory.mkdirs() && !directory.isDirectory) {
                throw IOException("No se pudo crear la carpeta de la copia previa")
            }
            syncDirectory(files.noBackupDirectory)
        }
        afterStep(PreMigrationStep.TEMPORARY_CLEARED)

        openWithoutCreating(files.database).use { source ->
            source.prepare("VACUUM INTO ?").use { statement ->
                statement.bindText(1, temporary.path)
                statement.step()
            }
        }
        afterStep(PreMigrationStep.COPIED)

        openWithoutCreating(temporary).use { copy ->
            // Toda copia legítima está en modo DELETE: una en WAL abierta para validarla crearía
            // archivos a su lado.
            copy.execSQL("PRAGMA journal_mode = DELETE")
            val integrity = copy.texts("PRAGMA integrity_check")
            if (integrity != listOf("ok")) {
                throw IOException("La copia previa no pasó integrity_check")
            }
            val copiedVersion = copy.texts("PRAGMA user_version").single().toInt()
            if (copiedVersion != databaseVersion) {
                throw IOException("La copia previa no tiene la versión de la base")
            }
        }
        afterStep(PreMigrationStep.CHECKED)

        FileOutputStream(temporary, true).use { it.fd.sync() }
        afterStep(PreMigrationStep.SYNCED)

        Files.move(
            temporary.toPath(),
            candidate.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        syncDirectory(directory)
        afterStep(PreMigrationStep.RENAMED)
        log.info(
            DataEvent.PRE_MIGRATION_COPIED,
            LogField.code("versión", databaseVersion),
            LogField.count("bytes", candidate.length()),
            LogField.durationMillis("duración", started.elapsedNow().inWholeMilliseconds),
        )
    }

    /**
     * Lo que hace falta libre: la copia, que nunca ocupa más que las páginas en uso de la base, más
     * el `-wal` que la migración puede llenar, hasta el tamaño de la base, más el margen fijo.
     */
    private fun requiredBytes(): Long {
        val copyBytes =
            openWithoutCreating(files.database).use { connection ->
                val pageSize = connection.texts("PRAGMA page_size").single().toLong()
                val pages = connection.texts("PRAGMA page_count").single().toLong()
                val free = connection.texts("PRAGMA freelist_count").single().toLong()
                Math.multiplyExact(pageSize, pages - free)
            }
        val databaseBytes =
            Math.addExact(files.database.length(), File(files.database.path + "-wal").length())
        return Math.addExact(Math.addExact(copyBytes, databaseBytes), MARGIN_BYTES)
    }

    private fun temporaryBytes(temporary: File): Long =
        TEMPORARY_SUFFIXES.sumOf { File(temporary.path + it).length() }

    private fun deleteTemporary(temporary: File) {
        for (suffix in TEMPORARY_SUFFIXES) {
            val file = File(temporary.path + suffix)
            if (file.exists() && !file.delete()) {
                throw IOException("No se pudo borrar el temporal de la copia previa")
            }
        }
    }

    private companion object {
        /** Margen fijo para que el teléfono siga funcionando después de copiar y migrar. */
        const val MARGIN_BYTES = 50L * 1024L * 1024L

        /** El temporal y lo que SQLite pudo dejar a su lado. */
        val TEMPORARY_SUFFIXES = listOf("", "-journal", "-wal", "-shm")
    }
}

/**
 * El espacio libre donde quedaría [directory]. Una carpeta que todavía no existe da cero, así que se
 * mide en la primera carpeta que sí existe hacia arriba, que está en el mismo disco.
 */
private fun usableSpaceOf(directory: File): Long =
    generateSequence(directory.absoluteFile) { it.parentFile }.firstOrNull { it.isDirectory }
        ?.usableSpace ?: 0L

/** Abre [file] con el SQLite empaquetado, sin crearlo: si no existe, falla. */
private fun openWithoutCreating(file: File): SQLiteConnection =
    BundledSQLiteDriver().open(file.path, SQLITE_OPEN_READWRITE)

private fun SQLiteConnection.texts(sql: String): List<String> = prepare(sql).use { statement ->
    buildList { while (statement.step()) add(statement.getText(0)) }
}

/**
 * El `user_version` de [file] leído de sus bytes, en el desplazamiento 60 del encabezado, sin
 * abrirlo con SQLite. Solo para la copia previa, que está en modo `DELETE` y nadie tiene abierta:
 * en la base activa, con WAL, los bytes pueden estar atrasados. Un archivo que no es una base de
 * SQLite da 0.
 */
internal fun fileUserVersion(file: File): Int {
    val header = ByteArray(HEADER_BYTES)
    val read =
        try {
            file.inputStream().use { input -> input.readNBytesCompat(header) }
        } catch (failure: IOException) {
            return 0
        }
    if (read < HEADER_BYTES || !header.copyOf(MAGIC.size).contentEquals(MAGIC)) return 0
    return (USER_VERSION_OFFSET until USER_VERSION_OFFSET + 4).fold(0) { value, index ->
        (value shl 8) or (header[index].toInt() and 0xFF)
    }
}

private fun java.io.InputStream.readNBytesCompat(target: ByteArray): Int {
    var total = 0
    while (total < target.size) {
        val count = read(target, total, target.size - total)
        if (count < 0) break
        total += count
    }
    return total
}

private const val HEADER_BYTES = 100
private const val USER_VERSION_OFFSET = 60
private val MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

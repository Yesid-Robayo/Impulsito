package io.github.yesidrobayo.shopledger.data.backup

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import androidx.sqlite.execSQL
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Hace la instantánea de la base de la que sale cada copia: un archivo SQLite aparte, completo y
 * consistente, escrito con `VACUUM INTO` mientras la app sigue vendiendo.
 *
 * - **Una conexión propia.** Se abre con el SQLite empaquetado, el mismo de Room, y nunca con una
 *   conexión de Room: una lectora de Room es de solo lectura, y usar la escritora frenaría las
 *   ventas mientras dure la copia. La base está en WAL, así que esta conexión lee una foto fija de
 *   la base y las ventas siguen confirmando a la vez. Lo que se confirma durante la copia queda
 *   para la siguiente.
 * - **Nunca crea la base.** Se abre sin la opción de crear: si el archivo faltara, abrir falla y no
 *   nace una base vacía que alguien pudiera tomar por el negocio.
 * - **Sobre un nombre nuevo.** `VACUUM INTO` falla si el destino ya tiene datos, y un resto de una
 *   ejecución cortada nunca sirve: se borra antes de empezar.
 * - **Nada a medias.** Si la copia falla o se cancela, su salida se borra antes de devolver: una
 *   copia sin terminar nunca se usa.
 * - La salida queda en modo de diario `delete`, aunque la base esté en WAL: es un solo archivo.
 *
 * No escribe en la base ni la cierra, y no registra nada: quien la llama decide qué hacer con el
 * resultado. Una sola copia a la vez, bajo el cerrojo del respaldo.
 *
 * @param databaseFile la base del negocio, con ruta absoluta.
 * @param ioContext contexto de la copia, fuera del hilo principal.
 */
internal class SnapshotCreator(
    private val databaseFile: File,
    private val ioContext: CoroutineContext,
) {
    private val driver = BundledSQLiteDriver()

    /**
     * Escribe la instantánea en [target], dentro de la carpeta de preparación del respaldo.
     *
     * Si [target] ya existe, es un resto de una ejecución cortada y se borra antes. Ante cualquier
     * fallo o cancelación, [target] queda borrado.
     */
    suspend fun create(target: File): SnapshotResult {
        require(target.isAbsolute) { "La ruta de la instantánea debe ser absoluta" }
        var created = false
        try {
            val result = withContext(ioContext) { vacuumInto(target) }
            created = result is SnapshotResult.Created
            return result
        } finally {
            if (!created) withContext(NonCancellable + ioContext) { deleteOutput(target) }
        }
    }

    /**
     * Corre `PRAGMA integrity_check` sobre la instantánea en [snapshot], sin escribir en ella.
     *
     * Una copia dañada nunca debe reemplazar como más reciente a una sana en el destino: por eso se
     * comprueba antes de subirla.
     */
    suspend fun checkIntegrity(snapshot: File): IntegrityResult = withContext(ioContext) {
        try {
            driver.open(snapshot.path, SQLITE_OPEN_READONLY).use { connection ->
                if (connection.integrityProblems() == 0L) {
                    IntegrityResult.Intact
                } else {
                    IntegrityResult.Damaged
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            if (SqliteFailure.isCorruption(failure)) {
                IntegrityResult.Damaged
            } else {
                IntegrityResult.Failed(failure)
            }
        }
    }

    private fun vacuumInto(target: File): SnapshotResult {
        if (!databaseFile.isFile) return SnapshotResult.DatabaseMissing
        deleteOutput(target)
        val directory = requireNotNull(target.parentFile) { "La instantánea necesita una carpeta" }
        directory.mkdirs()
        return try {
            // Sin la opción de crear: si la base desapareció entre la comprobación y la apertura,
            // abrir falla en vez de dejar una vacía.
            driver.open(databaseFile.path, SQLITE_OPEN_READWRITE).use { connection ->
                // Espera un cerrojo ocupado en vez de fallar enseguida, por ejemplo mientras otra
                // conexión hace un punto de control del WAL.
                connection.execSQL("PRAGMA busy_timeout = $BUSY_TIMEOUT_MILLIS")
                connection.prepare("VACUUM INTO ?").use { statement ->
                    statement.bindText(1, target.path)
                    statement.step()
                }
            }
            SnapshotResult.Created(target, target.length())
        } catch (failure: Exception) {
            when {
                SqliteFailure.isCorruption(failure) -> SnapshotResult.Corrupt
                SqliteFailure.isStorageFull(failure) -> SnapshotResult.StorageFull
                else -> SnapshotResult.Failed(failure)
            }
        }
    }

    private fun deleteOutput(target: File) {
        for (suffix in OUTPUT_SUFFIXES) File(target.path + suffix).delete()
    }

    private fun SQLiteConnection.integrityProblems(): Long =
        prepare("PRAGMA integrity_check").use { statement ->
            var problems = 0L
            while (statement.step()) if (statement.getText(0) != INTEGRITY_OK) problems++
            problems
        }

    private companion object {
        /** Cinco segundos de espera ante un cerrojo ocupado. */
        const val BUSY_TIMEOUT_MILLIS = 5_000

        /** La única fila que da `integrity_check` sobre una base sana. */
        const val INTEGRITY_OK = "ok"

        /** La salida y el diario que SQLite pudo dejar junto a ella. */
        val OUTPUT_SUFFIXES = listOf("", "-journal", "-wal", "-shm")
    }
}

/** Lo que dio una instantánea. */
internal sealed interface SnapshotResult {
    /** La instantánea quedó completa en [file], con [sizeBytes] bytes. */
    data class Created(val file: File, val sizeBytes: Long) : SnapshotResult

    /** No existe la base. No se creó ninguna, y no hay nada que copiar. */
    data object DatabaseMissing : SnapshotResult

    /** SQLite encontró la base dañada al leerla. No se sube nada. */
    data object Corrupt : SnapshotResult

    /** No hubo espacio para escribir la instantánea. */
    data object StorageFull : SnapshotResult

    /**
     * Otra falla de SQLite o del disco, que se puede reintentar. Solo la clase de [cause] puede ir
     * al registro: su mensaje puede llevar rutas.
     */
    class Failed(val cause: Exception) : SnapshotResult
}

/** Lo que dio `integrity_check` sobre una instantánea. */
internal sealed interface IntegrityResult {
    /** La instantánea está sana. */
    data object Intact : IntegrityResult

    /** La instantánea está dañada, o no es una base. */
    data object Damaged : IntegrityResult

    /** No se pudo comprobar, por una falla que no dice nada del archivo. */
    class Failed(val cause: Exception) : IntegrityResult
}

/**
 * Lee el código de error de SQLite de una excepción del SQLite empaquetado.
 *
 * El controlador no expone el código como dato: lo pone al comienzo del mensaje, como
 * `Error code: 11, message: ...`. Se lee solo ese número, nunca el resto del mensaje, que puede
 * llevar rutas. Las pruebas del dispositivo provocan una base dañada para comprobar que la forma no
 * cambió con una versión nueva del controlador.
 */
internal object SqliteFailure {
    private const val PREFIX = "Error code: "

    /** Los códigos extendidos guardan el código principal en su byte bajo. */
    private const val PRIMARY_CODE_MASK = 0xFF

    private const val SQLITE_CORRUPT = 11
    private const val SQLITE_FULL = 13
    private const val SQLITE_NOTADB = 26

    /** Si la falla dice que la base está dañada o que el archivo no es una base. */
    fun isCorruption(failure: Exception): Boolean =
        primaryCode(failure).let { it == SQLITE_CORRUPT || it == SQLITE_NOTADB }

    /** Si la falla dice que el disco se llenó. */
    fun isStorageFull(failure: Exception): Boolean = primaryCode(failure) == SQLITE_FULL

    /** El código principal de SQLite, o `null` si la falla no lo trae. */
    fun primaryCode(failure: Exception): Int? {
        val message = failure.message ?: return null
        if (!message.startsWith(PREFIX)) return null
        val digits = message.drop(PREFIX.length).takeWhile { it in '0'..'9' }
        return digits.toIntOrNull()?.and(PRIMARY_CODE_MASK)
    }
}

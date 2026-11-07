package io.github.yesidrobayo.shopledger.data.backup

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * Sella la instantánea de una copia y lee de ella lo que van a decir sus metadatos.
 *
 * - **El sello.** La instantánea lleva la fila del estado del respaldo de la base, que dice a qué
 *   destino está conectada la app, lo que la persona confirmó haber visto y la subida en curso. Nada
 *   de eso debe viajar en la copia: se limpia, y en su lugar queda qué copia es, de qué teléfono y
 *   de cuándo. Solo se escribe la instantánea, nunca la base del teléfono.
 * - **Lo que se lee sale de la instantánea,** no de la base del teléfono: `change_seq` es el de los
 *   cambios que la copia trae de verdad, aunque la app haya seguido vendiendo mientras se copiaba.
 *
 * Abre la instantánea con el SQLite empaquetado, sin la opción de crear: si el archivo faltara,
 * abrir falla en vez de dejar uno vacío. No registra nada.
 *
 * @param ioContext contexto de la escritura, fuera del hilo principal.
 */
internal class SnapshotSealer(private val ioContext: CoroutineContext) {
    private val driver = BundledSQLiteDriver()

    /** Sella [snapshot] con [seal] y devuelve sus conteos. */
    suspend fun seal(snapshot: File, seal: CopySeal): SealResult = withContext(ioContext) {
        try {
            driver.open(snapshot.path, SQLITE_OPEN_READWRITE).use { connection ->
                connection.prepare(SEAL).use { statement ->
                    statement.bindLong(1, seal.generation)
                    statement.bindText(2, seal.deviceId)
                    statement.bindLong(3, seal.createdAtMillis)
                    statement.step()
                }
                if (connection.single("SELECT changes()") !=
                    1L
                ) {
                    return@withContext SealResult.NoState
                }
                SealResult.Sealed(
                    changeSeq = connection.single(
                        "SELECT change_seq FROM backup_state WHERE id = 1",
                    ),
                    records = connection.single(NeutralCounts.RECORD_COUNT),
                    catalogCount = connection.single(NeutralCounts.CATALOG_COUNT),
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            when {
                SqliteFailure.isCorruption(failure) -> SealResult.Corrupt
                SqliteFailure.isStorageFull(failure) -> SealResult.StorageFull
                else -> SealResult.Failed(failure)
            }
        }
    }

    private fun SQLiteConnection.single(sql: String): Long = prepare(sql).use { statement ->
        check(statement.step()) { "La consulta no devolvió filas" }
        statement.getLong(0)
    }

    private companion object {
        /** Deja el sello y limpia lo que no viaja en una copia, en una sola sentencia. */
        const val SEAL =
            "UPDATE backup_state SET copy_generation = ?, copy_device_id = ?, " +
                "copy_created_at = ?, pending_generation = NULL, pending_seq = NULL, " +
                "pending_sha256 = NULL, pending_size_bytes = NULL, conflict_kind = NULL, " +
                "conflict_generation = NULL, conflict_device_id = NULL, " +
                "conflict_file_ref = NULL, destination_kind = NULL, " +
                "destination_account = NULL, destination_id = NULL, destination_folder = NULL, " +
                "human_check_generation = NULL, last_human_check_at = NULL, " +
                "last_full_verify_at = NULL WHERE id = 1"
    }
}

/**
 * Qué copia es una instantánea.
 *
 * @property generation la generación de la copia.
 * @property deviceId el teléfono que la hizo.
 * @property createdAtMillis el instante de la copia, según el reloj del teléfono.
 */
internal data class CopySeal(val generation: Long, val deviceId: String, val createdAtMillis: Long)

/** Lo que dio sellar una instantánea. */
internal sealed interface SealResult {
    /**
     * La instantánea quedó sellada.
     *
     * @property changeSeq los cambios del negocio que trae la copia.
     * @property records sus registros, de las tablas que solo crecen.
     * @property catalogCount sus productos, proveedores y clientes.
     */
    data class Sealed(val changeSeq: Long, val records: Long, val catalogCount: Long) : SealResult

    /** La instantánea no tiene la fila del estado del respaldo: no es una base de la app entera. */
    data object NoState : SealResult

    /** SQLite encontró la instantánea dañada. No se sube. */
    data object Corrupt : SealResult

    /** No hubo espacio para escribir el sello. */
    data object StorageFull : SealResult

    /** Otra falla de SQLite o del disco. Solo la clase de [cause] puede ir al registro. */
    class Failed(val cause: Exception) : SealResult
}

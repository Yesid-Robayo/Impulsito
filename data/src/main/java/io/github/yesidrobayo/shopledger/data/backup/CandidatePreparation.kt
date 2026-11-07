package io.github.yesidrobayo.shopledger.data.backup

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import androidx.sqlite.execSQL
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupLinkState
import io.github.yesidrobayo.shopledger.domain.model.stock.LowStockNotificationStatus
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * El destino que tendrá la base restaurada: el de la conexión actual, porque la copia no trae
 * ninguno. Todos nulos si nunca hubo uno.
 */
internal data class DestinationColumns(
    val kind: String?,
    val account: String?,
    val id: String?,
    val folder: String?,
) {
    // La cuenta y la carpeta no van a un registro.
    override fun toString(): String = "DestinationColumns($kind)"

    companion object {
        val NONE = DestinationColumns(null, null, null, null)
    }
}

/** La cabeza del destino que quedará en la base restaurada. */
internal sealed interface RestoredHead {
    /**
     * La cabeza recién listada en el destino, o ninguna si el destino no tiene copias: entonces
     * [generation] es 0 y lo demás, nulo.
     */
    data class Listed(val generation: Long, val deviceId: String?, val fileRef: String?) :
        RestoredHead

    /**
     * El destino no se pudo listar ahora. Queda la copia restaurada como cabeza, por su sello, con
     * [sourceRef] como su lugar si se conoce. Sin sello, la que traiga el archivo: la subida
     * siguiente pasa por la guarda, y si hay duda, decide la persona.
     */
    data class FromCopy(val sourceRef: String?) : RestoredHead
}

/**
 * Lo que la preparación escribe en la candidata, fuera de los datos del negocio.
 *
 * @property backedUp si la candidata ya está en el destino conectado, por suma. Solo entonces no
 *   queda nada sin respaldar. En cualquier otro caso se fuerza una subida.
 * @property linkState el enlace con que arranca: conectado si el destino sigue disponible, o sin
 *   conectar. Un conflicto nunca se conserva, porque dejaría el respaldo detenido.
 * @property keepLastSuccess al deshacer, la última copia buena es la que trae la copia de
 *   seguridad, y no su sello, que no tiene.
 * @property activeChecks lo que la persona ya revisó del destino según la base activa, o `null` si
 *   no se pudo leer: entonces queda vacío y se pide revisar de nuevo.
 * @property deviceSecure si el teléfono tiene bloqueo de pantalla. Sin él, el bloqueo de la app se
 *   apaga, para no dejarla inaccesible.
 */
internal data class PreparationInput(
    val now: Instant,
    val backedUp: Boolean,
    val linkState: BackupLinkState,
    val destination: DestinationColumns,
    val head: RestoredHead,
    val keepLastSuccess: Boolean,
    val activeChecks: ActiveChecks?,
    val deviceSecure: Boolean,
) {
    init {
        require(
            linkState == BackupLinkState.CONNECTED || linkState == BackupLinkState.NOT_CONNECTED,
        ) {
            "La base restaurada arranca conectada o sin conectar"
        }
    }
}

/**
 * Lo que la persona ya revisó del destino, según la base activa: pertenece a este teléfono y a su
 * destino, no a la copia, que lo trae vacío.
 */
internal data class ActiveChecks(
    val humanCheckGeneration: Long?,
    val lastHumanCheckAt: Long?,
    val lastFullVerifyAt: Long?,
)

/** Lo que el arranque comprueba de la candidata preparada. */
internal data class PreparedCandidate(
    val sha256: String,
    val changeSeq: Long,
    val recordCount: Long,
)

/** Lo que dio preparar la candidata. */
internal sealed interface PreparationResult {
    data class Prepared(val candidate: PreparedCandidate) : PreparationResult

    /** La candidata quedó dañada o una guarda rechazó el estado. Se rechaza. */
    data class Refused(val reason: CandidateRejection) : PreparationResult

    /** Una falla del disco. Solo la clase de [cause] puede ir al registro. */
    class Failed(val cause: Exception) : PreparationResult
}

/**
 * El resto de la preparación de la candidata, después de validarla, migrarla y repararla: deja su
 * estado del respaldo como debe arrancar en este teléfono, y la cierra en un solo archivo.
 *
 * - **Una sola transacción.** El estado, las alertas y el bloqueo cambian juntos o no cambian.
 *   Las guardas del archivo siguen activas: un valor que la base no aceptaría se rechaza aquí.
 * - **Sin respaldo falso.** Solo si la candidata ya está en el destino, `verified_seq` alcanza a
 *   `change_seq`. Si no, `change_seq` sube en uno y el primer cambio sin copia es ahora: la
 *   copia siguiente sube, y la app nunca dice que todo está guardado por haber restaurado.
 * - **Un solo archivo.** Después pasa el `-wal` al archivo y deja el diario normal: así el
 *   reemplazo mueve un solo archivo, y Room vuelve a WAL al abrirlo.
 * - **Comprobada al final.** `integrity_check` sobre el archivo cerrado, y la suma y los dos
 *   contadores que el arranque exige encontrar.
 *
 * No registra nada.
 */
internal class CandidatePreparation(private val ioContext: CoroutineContext) {
    private val driver = BundledSQLiteDriver()

    suspend fun prepare(candidate: File, input: PreparationInput): PreparationResult =
        withContext(ioContext) {
            try {
                val counters =
                    driver.open(candidate.path, SQLITE_OPEN_READWRITE).use { connection ->
                        connection.execSQL("PRAGMA foreign_keys = ON")
                        connection.execSQL("PRAGMA trusted_schema = OFF")
                        connection.execSQL("PRAGMA synchronous = FULL")
                        connection.inTransaction { write(input) }
                        connection.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
                        check(connection.text("PRAGMA journal_mode = DELETE") == JOURNAL_DELETE) {
                            "La candidata no quedó con el diario normal"
                        }
                        if (!connection.isIntact()) {
                            return@withContext PreparationResult.Refused(
                                CandidateRejection.INTEGRITY_FAILED,
                            )
                        }
                        connection.long(RestoredDatabaseCounters.CHANGE_SEQ_SQL) to
                            connection.long(RestoredDatabaseCounters.RECORD_COUNT_SQL)
                    }
                PreparationResult.Prepared(
                    PreparedCandidate(sha256Of(candidate), counters.first, counters.second),
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                when {
                    SqliteFailure.isCorruption(failure) ->
                        PreparationResult.Refused(CandidateRejection.INTEGRITY_FAILED)

                    // Una guarda que rechaza el estado preparado: la copia trae algo que la app
                    // no sabría usar.
                    SqliteFailure.primaryCode(failure) == SQLITE_CONSTRAINT ->
                        PreparationResult.Refused(CandidateRejection.DATA_FATAL)

                    else -> PreparationResult.Failed(failure)
                }
            }
        }

    private fun SQLiteConnection.write(input: PreparationInput) {
        prepare(STATE).use { statement ->
            statement.bindLong(1, input.backedUp.toFlag())
            statement.bindLong(2, input.now.toEpochMilliseconds())
            statement.bindText(3, input.linkState.name)
            statement.bindNullableText(4, input.destination.kind)
            statement.bindNullableText(5, input.destination.account)
            statement.bindNullableText(6, input.destination.id)
            statement.bindNullableText(7, input.destination.folder)
            statement.bindNullableLong(8, input.activeChecks?.humanCheckGeneration)
            statement.bindNullableLong(9, input.activeChecks?.lastHumanCheckAt)
            statement.bindNullableLong(10, input.activeChecks?.lastFullVerifyAt)
            statement.bindLong(11, input.keepLastSuccess.toFlag())
            statement.step()
        }
        when (val head = input.head) {
            is RestoredHead.Listed -> prepare(LISTED_HEAD).use { statement ->
                statement.bindLong(1, head.generation)
                statement.bindNullableText(2, head.deviceId)
                statement.bindNullableText(3, head.fileRef)
                statement.step()
            }

            is RestoredHead.FromCopy -> prepare(COPY_HEAD).use { statement ->
                statement.bindNullableText(1, head.sourceRef)
                statement.step()
            }
        }
        // La elección de esta copia fue expresa: no cuenta como una base que se achicó. Y el
        // sello se borra de último, porque la cabeza sin conexión sale de él.
        execSQL(
            "UPDATE backup_state SET shrink_accepted_generation = head_generation, " +
                "copy_generation = NULL, copy_device_id = NULL, copy_created_at = NULL " +
                "WHERE id = 1",
        )
        check(long("SELECT changes()") == 1L) { "La candidata no tiene estado del respaldo" }
        // Un aviso de stock de la copia no se lanza: no es de ahora.
        execSQL(
            "UPDATE low_stock_alerts SET notification_status = " +
                "'${LowStockNotificationStatus.SUPPRESSED.name}' " +
                "WHERE notification_status = '${LowStockNotificationStatus.PENDING.name}'",
        )
        if (!input.deviceSecure) {
            execSQL("UPDATE app_settings SET app_lock_enabled = 0 WHERE app_lock_enabled <> 0")
        }
    }

    private inline fun SQLiteConnection.inTransaction(block: SQLiteConnection.() -> Unit) {
        execSQL("BEGIN IMMEDIATE")
        try {
            block()
            execSQL("COMMIT")
        } catch (failure: Throwable) {
            try {
                execSQL("ROLLBACK")
            } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    private fun SQLiteConnection.isIntact(): Boolean = prepare("PRAGMA integrity_check").use {
        it.step() && it.getText(0) == INTEGRITY_OK && !it.step()
    }

    private fun SQLiteConnection.long(sql: String): Long = prepare(sql).use {
        check(it.step()) { "La consulta no devolvió filas" }
        it.getLong(0)
    }

    private fun SQLiteConnection.text(sql: String): String = prepare(sql).use {
        check(it.step()) { "La consulta no devolvió filas" }
        it.getText(0)
    }

    private fun Boolean.toFlag(): Long = if (this) 1L else 0L

    private fun SQLiteStatement.bindNullableText(index: Int, value: String?) {
        if (value == null) bindNull(index) else bindText(index, value)
    }

    private fun SQLiteStatement.bindNullableLong(index: Int, value: Long?) {
        if (value == null) bindNull(index) else bindLong(index, value)
    }

    private companion object {
        const val SQLITE_CONSTRAINT = 19
        const val INTEGRITY_OK = "ok"
        const val JOURNAL_DELETE = "delete"

        /**
         * El estado del respaldo con que arranca la base restaurada. Del lado derecho, cada columna
         * vale lo que traía la copia: `verified_seq` toma el `change_seq` de la copia.
         */
        val STATE =
            """
            UPDATE backup_state SET
              change_seq = CASE WHEN ?1 THEN change_seq ELSE change_seq + 1 END,
              verified_seq = CASE WHEN ?1 THEN change_seq ELSE verified_seq END,
              first_unsynced_at = CASE WHEN ?1 THEN NULL ELSE ?2 END,
              link_state = ?3,
              destination_kind = ?4, destination_account = ?5, destination_id = ?6,
              destination_folder = ?7,
              human_check_generation = ?8, last_human_check_at = ?9, last_full_verify_at = ?10,
              last_success_at = CASE WHEN ?11 THEN last_success_at
                                     ELSE COALESCE(copy_created_at, last_success_at) END,
              failure_code = NULL, last_failure_kind = NULL,
              consecutive_failures = 0, invariant_failures = 0,
              last_upload_finished_at = NULL,
              pending_generation = NULL, pending_seq = NULL, pending_sha256 = NULL,
              pending_size_bytes = NULL,
              conflict_kind = NULL, conflict_generation = NULL, conflict_device_id = NULL,
              conflict_file_ref = NULL
            WHERE id = 1
            """.trimIndent()

        /** La cabeza recién listada en el destino. */
        const val LISTED_HEAD =
            "UPDATE backup_state SET head_generation = ?1, head_device_id = ?2, " +
                "head_file_ref = ?3 WHERE id = 1"

        /**
         * Sin lista, la copia restaurada es la cabeza, por su sello. Sin sello, se queda la cabeza
         * que traía el archivo.
         */
        const val COPY_HEAD =
            "UPDATE backup_state SET " +
                "head_file_ref = CASE WHEN copy_generation IS NULL THEN head_file_ref " +
                "ELSE ?1 END, " +
                "head_device_id = CASE WHEN copy_generation IS NULL THEN head_device_id " +
                "ELSE copy_device_id END, " +
                "head_generation = COALESCE(copy_generation, head_generation) WHERE id = 1"
    }
}

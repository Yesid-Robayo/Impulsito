package io.github.yesidrobayo.shopledger.data.local

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import io.github.yesidrobayo.shopledger.data.RescueCause
import io.github.yesidrobayo.shopledger.data.log.DataEvent
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.LogField
import io.github.yesidrobayo.shopledger.domain.port.error
import io.github.yesidrobayo.shopledger.domain.port.info
import io.github.yesidrobayo.shopledger.domain.port.warn
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/** Quién pide pasar a la base. */
internal enum class GateCaller {
    /** Una pantalla de la app, en primer plano. Puede copiar y migrar, porque tiene pantalla. */
    APP_IN_FOREGROUND,

    /** Un trabajo en segundo plano. Nunca migra: si hace falta, espera a la app. */
    BACKGROUND_WORK,
}

/** Lo que decidió la compuerta. */
internal sealed interface GateOutcome<out D> {
    /** La base abrió, ya en la versión de la app, y la marca de base creada está en disco. */
    data class Opened<D>(val database: D) : GateOutcome<D>

    /** Hace falta migrar y quien pidió pasar es un trabajo: la base no se abrió. */
    data object MigrationPending : GateOutcome<Nothing>

    /** La base no se puede abrir sin riesgo. Nada se tocó. */
    data class Rescue(val cause: RescueCause) : GateOutcome<Nothing>
}

/**
 * Deja la copia previa a migrar antes de que Room migre. La compuerta la pide justo antes de abrir
 * una base de una versión anterior.
 */
internal fun interface PreMigrationCopy {
    /** Deja la copia de la base, que está en [databaseVersion]. Lee y escribe el disco. */
    suspend fun prepare(databaseVersion: Int): PreMigrationResult
}

/** Lo que dio la copia previa a migrar. */
internal sealed interface PreMigrationResult {
    /** La copia está en disco y se puede migrar. */
    data object Ready : PreMigrationResult

    /** No hay copia: no se migra. */
    data object Failed : PreMigrationResult
}

/**
 * La compuerta del arranque: el único punto que deja pasar a la base. Decide, antes de abrir Room,
 * si abrirla es seguro, y retiene a la interfaz y a los trabajos mientras tanto.
 *
 * Lo que decide, en orden:
 * 1. **Base que falta.** Si la marca de base creada existe y el archivo no, Room no se abre:
 *    crearía una base vacía encima del negocio. Va al rescate.
 * 2. **Instalación nueva.** Sin archivo y sin marca, Room crea la base y después se deja la marca.
 * 3. **Versión.** Con el archivo, se lee su `user_version` con `PRAGMA`, en una conexión propia y
 *    sin crear nada. Nunca por bytes: con el `-wal` sin pasar al archivo, el encabezado puede traer
 *    la versión anterior.
 *    - Igual a la de la app: Room abre.
 *    - Mayor: viene de una app más nueva. Room se negaría, así que ni se intenta: rescate.
 *    - Cero: un archivo sin esquema. Si la marca existe, el negocio no está en él: rescate. Si no,
 *      es una creación que no terminó, y Room la repite.
 *    - Menor: hay que migrar. Un trabajo no abre la base y espera a la app. La app hace primero la
 *      copia previa y solo entonces abre Room, que migra en una transacción. Si la migración falla,
 *      Room la revierte y la base queda en su versión: rescate.
 * 4. **Marca.** Cada vez que la base abre, se deja la marca si falta. Si no se pudo, la base se
 *    entrega igual y la apertura siguiente lo intenta otra vez: dejar a la persona sin su app por
 *    una marca sería peor.
 *
 * Todo corre en [ioContext], fuera del hilo principal. No guarda nada entre llamadas: quien la usa
 * decide cuándo llamar otra vez, y la llama una a la vez.
 *
 * @param schemaVersion la versión del esquema que compila la app.
 * @param preMigrationCopy deja la copia previa a migrar.
 * @param openDatabase abre la base con Room, y la migra si hace falta. Lanza si no abre.
 */
internal class DatabaseGate<D : Any>(
    private val files: DatabaseFiles,
    private val schemaVersion: Int,
    private val ioContext: CoroutineContext,
    private val log: EventLog,
    private val preMigrationCopy: PreMigrationCopy,
    private val openDatabase: suspend (File) -> D,
) {
    init {
        require(schemaVersion >= 1) { "La versión del esquema empieza en 1" }
    }

    /** Decide si [caller] pasa, y le abre la base si es seguro. */
    suspend fun pass(caller: GateCaller): GateOutcome<D> = withContext(ioContext) {
        when (files.state()) {
            DatabaseFileState.MISSING -> rescue(RescueCause.DatabaseMissing)
            DatabaseFileState.NEVER_CREATED -> open()
            DatabaseFileState.PRESENT -> passExisting(caller)
        }
    }

    private suspend fun passExisting(caller: GateCaller): GateOutcome<D> {
        val version =
            try {
                readUserVersion(files.database)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                log.error(DataEvent.DATABASE_OPEN_FAILED, failure)
                return GateOutcome.Rescue(RescueCause.OpenFailed)
            }
        return when {
            version == schemaVersion -> open()

            version > schemaVersion -> rescue(RescueCause.NewerVersion, version)

            version == 0 && files.initializedMarker.exists() ->
                rescue(RescueCause.DatabaseMissing, version)

            version == 0 -> open()

            version < 0 -> rescue(RescueCause.OpenFailed, version)

            else -> pendingMigration(caller, version)
        }
    }

    private suspend fun pendingMigration(caller: GateCaller, version: Int): GateOutcome<D> =
        when (caller) {
            GateCaller.BACKGROUND_WORK -> {
                log.info(DataEvent.MIGRATION_LEFT_TO_APP, *versions(version))
                GateOutcome.MigrationPending
            }

            GateCaller.APP_IN_FOREGROUND -> migrate(version)
        }

    private suspend fun migrate(version: Int): GateOutcome<D> {
        when (preMigrationCopy.prepare(version)) {
            PreMigrationResult.Ready -> Unit

            PreMigrationResult.Failed ->
                return rescue(RescueCause.PreMigrationCopyFailed, version)
        }
        val started = TimeSource.Monotonic.markNow()
        val database =
            try {
                openDatabase(files.database)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                // Room revierte toda la cadena de saltos: la base sigue en [version].
                log.error(DataEvent.MIGRATION_FAILED, failure, *versions(version))
                return GateOutcome.Rescue(RescueCause.MigrationFailed)
            }
        log.info(
            DataEvent.DATABASE_MIGRATED,
            *versions(version),
            LogField.durationMillis("duración", started.elapsedNow().inWholeMilliseconds),
        )
        return opened(database)
    }

    private suspend fun open(): GateOutcome<D> {
        val database =
            try {
                openDatabase(files.database)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                log.error(DataEvent.DATABASE_OPEN_FAILED, failure)
                return GateOutcome.Rescue(RescueCause.OpenFailed)
            }
        return opened(database)
    }

    private fun opened(database: D): GateOutcome<D> {
        try {
            files.markInitialized()
        } catch (failure: Exception) {
            log.warn(DataEvent.INITIALIZED_MARK_FAILED, failure)
        }
        return GateOutcome.Opened(database)
    }

    private fun rescue(cause: RescueCause, version: Int? = null): GateOutcome<D> {
        val event =
            when (cause) {
                RescueCause.DatabaseMissing -> DataEvent.DATABASE_MISSING
                RescueCause.NewerVersion -> DataEvent.DATABASE_FROM_NEWER_APP
                RescueCause.OpenFailed -> DataEvent.DATABASE_OPEN_FAILED
                RescueCause.MigrationFailed -> DataEvent.MIGRATION_FAILED
                RescueCause.PreMigrationCopyFailed -> DataEvent.PRE_MIGRATION_COPY_MISSING
            }
        if (version == null) log.error(event) else log.error(event, *versions(version))
        return GateOutcome.Rescue(cause)
    }

    private fun versions(version: Int): Array<LogField> = arrayOf(
        LogField.code("versión", version),
        LogField.code("app", schemaVersion),
    )
}

/**
 * El `user_version` de la base en [file], leído con `PRAGMA` en una conexión propia del SQLite
 * empaquetado. Abre sin crear: si el archivo no existiera, falla en vez de dejar uno vacío.
 *
 * Con WAL, SQLite lee la versión del `-wal` si todavía no pasó al archivo. Al cerrar la última
 * conexión, SQLite pasa el `-wal` al archivo: no cambia ningún dato.
 */
internal fun readUserVersion(file: File): Int =
    BundledSQLiteDriver().open(file.path, SQLITE_OPEN_READWRITE).use { connection ->
        connection.prepare("PRAGMA user_version").use { statement ->
            check(statement.step()) { "PRAGMA user_version no devolvió filas" }
            statement.getLong(0).toInt()
        }
    }

package io.github.yesidrobayo.shopledger.data.backup

import android.app.KeyguardManager
import android.content.Context
import androidx.room3.executeSQL
import androidx.room3.useWriterConnection
import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.data.destination.DestinationLinkStore
import io.github.yesidrobayo.shopledger.data.destination.DestinationOpener
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.audit.AuditEventEntity
import io.github.yesidrobayo.shopledger.data.log.DataEvent
import io.github.yesidrobayo.shopledger.domain.model.audit.AuditEventType
import io.github.yesidrobayo.shopledger.domain.model.audit.AuditResult
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupLinkState
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.warn
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex

/**
 * La preparación de restauraciones de la app, armada con sus piezas de producción: la base activa
 * que abrió la compuerta, el destino conectado, el cerrojo del respaldo del proceso y las carpetas
 * de `noBackupFilesDir`.
 *
 * Crearla no toca el disco ni abre nada.
 *
 * @param ports la base activa abierta, o `null` si no abrió, como en el rescate.
 * @param backupMutex el cerrojo del respaldo, el mismo de cada ejecución.
 * @param scheduledWork los trabajos programados del respaldo, que la pausa cancela.
 */
internal fun productionRestoreCoordinator(
    context: Context,
    ioContext: CoroutineContext,
    clock: Clock,
    log: EventLog,
    files: RestoreFiles,
    ports: () -> DataPorts?,
    destinations: DestinationOpener,
    backupMutex: Mutex,
    scheduledWork: ScheduledBackupWork,
): RestoreCoordinator {
    val ops = DurableFileOps()
    val validator = CandidateValidator(ioContext)
    val links = DestinationLinkStore({ files.databaseFiles.noBackupDirectory }, ioContext)
    return RestoreCoordinator(
        files = files,
        ops = ops,
        lock = RestoreLockFile(files, ops, clock, ioContext),
        pause = MutexBackupPause(backupMutex, scheduledWork),
        active = { activeDatabaseOf(ports()) },
        linkColumns = {
            links.read()?.let {
                DestinationColumns(it.kind.name, it.account, it.destinationId, it.folderUrl)
            }
        },
        destinations = destinations,
        unpacker = CandidateUnpacker(ioContext),
        validator = validator,
        repair = CandidateRepair(ioContext, validator),
        migrator = RoomCandidateMigrator(context, ioContext, clock),
        preparation = CandidatePreparation(ioContext),
        safetyCopies = SafetyCopyStore(files, ops, ioContext),
        journalStore = RestoreJournalStore(files, ops),
        closeDatabase = { ports()?.closeForRestore() },
        deviceSecure = { isDeviceSecure(context) },
        clock = clock,
        log = log,
        ioContext = ioContext,
    )
}

/**
 * Lo que la preparación lee de la base activa, o `null` si no está abierta o no se deja leer: la
 * restauración puede empezar justo porque la base falló.
 */
internal suspend fun activeDatabaseOf(ports: DataPorts?): ActiveDatabase? {
    if (ports == null) return null
    return try {
        val row = ports.backupState.get() ?: return null
        ActiveDatabase(
            hasData = !ports.backupState.isEmpty(),
            changeSeq = row.changeSeq,
            verifiedSeq = row.verifiedSeq,
            linkState = BackupLinkState.entries.firstOrNull { it.name == row.linkState },
            destination =
                DestinationColumns(
                    kind = row.destinationKind,
                    account = row.destinationAccount,
                    id = row.destinationId,
                    folder = row.destinationFolder,
                ),
            checks =
                ActiveChecks(
                    humanCheckGeneration = row.humanCheckGeneration,
                    lastHumanCheckAt = row.lastHumanCheckAt,
                    lastFullVerifyAt = row.lastFullVerifyAt,
                ),
        )
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        null
    }
}

/**
 * Migra la candidata abriéndola con la base de la app: Room corre las mismas migraciones que
 * correría sobre la base activa, en una transacción. Después pasa el `-wal` al archivo y la cierra.
 */
internal class RoomCandidateMigrator(
    private val context: Context,
    private val ioContext: CoroutineContext,
    private val clock: Clock,
) : CandidateMigrator {
    override suspend fun migrate(candidate: File) {
        val database = AppDatabase.open(context, candidate, ioContext, clock)
        try {
            database.useWriterConnection { it.executeSQL("PRAGMA wal_checkpoint(TRUNCATE)") }
        } finally {
            database.close()
        }
    }
}

/**
 * Anota en la auditoría de la base abierta que el cifrado de las copias cambió: el código, al
 * restaurar, o el apagado. Una falla al anotar nunca detiene nada: solo queda en el registro, por
 * su clase, con [failed].
 */
internal suspend fun recordEncryptionChanged(
    ports: DataPorts,
    clock: Clock,
    log: EventLog,
    failed: DataEvent = DataEvent.RESTORE_AUDIT_FAILED,
) {
    try {
        ports.auditEvents.insert(
            AuditEventEntity(
                occurredAt = clock.nowEpochMillis(),
                eventType = AuditEventType.ENCRYPTION_CHANGED.name,
                result = AuditResult.SUCCESS.name,
            ),
        )
        ports.auditEvents.pruneOldest()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        log.warn(failed, failure)
    }
}

/** Si el teléfono tiene bloqueo de pantalla: sin él, el bloqueo de la app la dejaría inaccesible. */
private fun isDeviceSecure(context: Context): Boolean =
    context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure ?: false

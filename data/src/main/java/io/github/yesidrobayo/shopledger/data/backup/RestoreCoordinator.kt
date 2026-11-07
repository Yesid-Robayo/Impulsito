package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.destination.BackupDestination
import io.github.yesidrobayo.shopledger.data.destination.ConnectedDestination
import io.github.yesidrobayo.shopledger.data.destination.CopyListing
import io.github.yesidrobayo.shopledger.data.destination.CopyMetadata
import io.github.yesidrobayo.shopledger.data.destination.DestinationFailure
import io.github.yesidrobayo.shopledger.data.destination.DestinationOpen
import io.github.yesidrobayo.shopledger.data.destination.DestinationOpener
import io.github.yesidrobayo.shopledger.data.destination.DestinationResult
import io.github.yesidrobayo.shopledger.data.destination.ListedCopy
import io.github.yesidrobayo.shopledger.data.destination.ListedMeta
import io.github.yesidrobayo.shopledger.data.destination.SlMeta
import io.github.yesidrobayo.shopledger.data.destination.SlMetaCodec
import io.github.yesidrobayo.shopledger.data.destination.TransferControl
import io.github.yesidrobayo.shopledger.data.destination.UnsupportedReason
import io.github.yesidrobayo.shopledger.data.destination.identity
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.log.DataEvent
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupLimits
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupLinkState
import io.github.yesidrobayo.shopledger.domain.model.backup.CandidateCheck
import io.github.yesidrobayo.shopledger.domain.model.backup.CandidateVerdict
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.LogField
import io.github.yesidrobayo.shopledger.domain.port.info
import io.github.yesidrobayo.shopledger.domain.port.warn
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Lo que la preparación necesita de la base activa, leído con ella abierta. `null` si no se pudo
 * leer, como cuando la restauración empieza desde el rescate.
 *
 * @property hasData si tiene algo del negocio: entonces una copia vacía no la reemplaza sin una
 *   decisión expresa.
 * @property changeSeq y [verifiedSeq] lo que guarda la copia de seguridad: si el primero es mayor,
 *   esa copia es el único respaldo de algunos cambios.
 * @property linkState el enlace con el destino, o `null` si su valor no se reconoce.
 * @property destination el destino de la conexión actual.
 * @property checks lo que la persona ya revisó del destino.
 */
internal data class ActiveDatabase(
    val hasData: Boolean,
    val changeSeq: Long,
    val verifiedSeq: Long,
    val linkState: BackupLinkState?,
    val destination: DestinationColumns,
    val checks: ActiveChecks,
)

/** Abre la candidata con las migraciones de la app, para que migre antes del reemplazo. */
internal fun interface CandidateMigrator {
    /** Migra [candidate] a la versión de la app. Lanza si la migración falla. */
    suspend fun migrate(candidate: File)
}

/**
 * La primera fase de una restauración, con la app viva: prepara la base que reemplazará a la activa
 * sin tocarla, y deja escrito el diario que el arranque sigue para hacer el reemplazo.
 *
 * Los pasos van en el orden de la documentación, y cada uno solo corre si el anterior pasó:
 *
 * 1. **Cerrojo.** Con un diario pendiente no empieza: el arranque termina primero ese reemplazo. Si
 *    no, toma `restore/in_progress`, y desde ahí ninguna escritura entra en la base.
 * 2. **Pausa.** Ninguna ejecución del respaldo corre hasta terminar o cancelar.
 * 3. **Espacio.** Tres veces la base de la copia y 50 MiB, o nueve veces el archivo si no se sabe
 *    cuánto ocupa descomprimido. Si no cabe, dice cuánto falta y no baja nada.
 * 4. **Bajar.** A un nombre fijo, con el tope de tamaño. Un nombre que venga de afuera nunca es una
 *    ruta. Al deshacer, la copia de seguridad se aparta y se copia como candidata.
 * 5. a 11. **Revisar.** Suma, formato, descompresión con tope y el validador entero. Un hallazgo
 *    reparable, o una copia vacía sobre una base con datos, se detienen a preguntar.
 * 12. **Migrar** la candidata si es de un esquema anterior: una migración que falla nunca llega a la
 *     base activa.
 * 13. **Preparar.** Los valores desconocidos, los saldos si la persona confirmó, y el estado del
 *     respaldo con que arranca: el destino actual, su cabeza recién listada y si la copia ya está
 *     guardada allí.
 * 14. **Copia de seguridad** de la base activa, para deshacer durante 30 días.
 * 15. **Diario** en `PREPARED`, solo si el cerrojo sigue siendo el de esta preparación.
 * 16. **Cerrar la base.** Falta reiniciar el proceso, y eso lo hace la app con su pantalla.
 *
 * Hasta el paso 14 se puede cancelar, y cualquier rechazo o falla se detiene ahí: borra lo
 * temporal, devuelve el origen de un deshacer, retira el cerrojo y reanuda el respaldo. La base
 * activa nunca cambia. Si el proceso muere a mitad, el arranque siguiente limpia lo mismo.
 *
 * Una preparación a la vez. Nada de lo que registra lleva datos del negocio, rutas ni nombres de
 * archivo: solo códigos y conteos.
 *
 * @param active lee la base activa.
 * @param linkColumns el destino de `link.json`, para cuando la base activa no se puede leer.
 * @param destinations abre el destino conectado.
 * @param closeDatabase cierra la base activa, con su `-wal` pasado al archivo, tras el diario.
 * @param deviceSecure si el teléfono tiene bloqueo de pantalla.
 * @param freeSpace los bytes libres para la app en la carpeta dada.
 * @param appSchemaVersion la versión del esquema que compila la app.
 */
internal class RestoreCoordinator(
    private val files: RestoreFiles,
    private val ops: DurableFileOps,
    private val lock: RestoreLockFile,
    private val pause: BackupPause,
    private val active: suspend () -> ActiveDatabase?,
    private val linkColumns: suspend () -> DestinationColumns?,
    private val destinations: DestinationOpener,
    private val unpacker: CandidateUnpacker,
    private val validator: CandidateValidator,
    private val repair: CandidateRepair,
    private val migrator: CandidateMigrator,
    private val preparation: CandidatePreparation,
    private val safetyCopies: SafetyCopyStore,
    private val journalStore: RestoreJournalStore,
    private val closeDatabase: suspend () -> Unit,
    private val deviceSecure: () -> Boolean,
    private val clock: Clock,
    private val log: EventLog,
    private val ioContext: CoroutineContext,
    private val freeSpace: (File) -> Long = File::getUsableSpace,
    private val appSchemaVersion: Int = AppDatabase.SCHEMA_VERSION,
) {
    private val mutex = Mutex()
    private var session: Session? = null

    /** Empieza a preparar la restauración desde [source], tras la confirmación de la persona. */
    suspend fun start(source: RestoreSource): RestoreStage = mutex.withLock {
        if (session != null) return@withLock RestoreStage.Busy
        guarded { begin(source) }
    }

    /** Sigue la preparación detenida con [decision]. Sin una preparación en espera, `Busy`. */
    suspend fun decide(decision: RestoreDecision): RestoreStage = mutex.withLock {
        val current = session ?: return@withLock RestoreStage.Busy
        guarded { resume(current, decision) }
    }

    /**
     * Cancela la preparación en espera de una decisión. Devuelve `false` si no había ninguna: con
     * el diario escrito, ya no se cancela.
     */
    suspend fun cancel(): Boolean = mutex.withLock {
        val current = session ?: return@withLock false
        withContext(NonCancellable) { abandon(current) }
        log.info(DataEvent.RESTORE_CANCELLED)
        true
    }

    /**
     * Corre un tramo de la preparación. Un rechazo, una falla o una cancelación de la corrutina
     * abandonan la preparación entera; una pregunta la deja en espera.
     */
    private suspend fun guarded(block: suspend () -> RestoreStage): RestoreStage {
        val stage =
            try {
                block()
            } catch (cancellation: CancellationException) {
                session?.let { withContext(NonCancellable) { abandon(it) } }
                throw cancellation
            } catch (failure: IOException) {
                RestoreStage.Failed(failure)
            } catch (failure: Exception) {
                // Una falla que nadie previó: la base activa no cambió, y el cerrojo no se queda.
                session?.let { withContext(NonCancellable) { abandon(it) } }
                throw failure
            }
        when (stage) {
            is RestoreStage.ConfirmRepair,
            RestoreStage.ConfirmEmptyCopy,
            RestoreStage.ConfirmWithoutSafetyCopy,
            is RestoreStage.ChooseSafetyCopyToDiscard,
            -> Unit

            is RestoreStage.ReadyToRestart -> {
                session = null
                log.info(DataEvent.RESTORE_PREPARED)
            }

            RestoreStage.Busy -> Unit

            is RestoreStage.NotEnoughSpace,
            is RestoreStage.Refused,
            is RestoreStage.Failed,
            -> {
                session?.let { withContext(NonCancellable) { abandon(it) } }
                logStop(stage)
            }
        }
        return stage
    }

    private suspend fun begin(source: RestoreSource): RestoreStage {
        // Paso 1. Un diario, legible o no, es un reemplazo que el arranque todavía no terminó:
        // una preparación nueva lo pisaría.
        if (withContext(ioContext) { files.journal.exists() }) return RestoreStage.Busy
        val takenAt = lock.acquire() ?: return RestoreStage.Busy
        val current = Session(source, takenAt).also { session = it }
        // Paso 2.
        pause.pause()
        current.paused = true
        val meta = (source as? RestoreSource.Listed)?.let { metadataOf(it.copy) }
        if (meta?.fatal == true) return RestoreStage.Refused(RestoreRefusal.MarkedFatal)
        current.expected =
            meta?.let { ExpectedCopy(it.deviceId, it.generation, it.schemaVersion.toInt()) }
        // Paso 3.
        spaceShortage(source, meta)?.let { return it }
        // Pasos 4 a 6.
        fetch(current, meta)?.let { return it }
        current.activeDatabase = active()
        return validate(current)
    }

    private suspend fun resume(current: Session, decision: RestoreDecision): RestoreStage =
        when (decision) {
            RestoreDecision.ReplaceWithEmptyCopy -> {
                current.emptyCopyConfirmed = true
                validate(current)
            }

            RestoreDecision.Repair -> {
                current.repairConfirmed = true
                migrateAndPrepare(current)
            }

            RestoreDecision.ContinueWithoutSafetyCopy -> {
                current.withoutSafetyCopy = true
                saveSafetyCopy(current)
            }

            is RestoreDecision.DiscardSafetyCopy -> {
                safetyCopies.discard(decision.copy)
                saveSafetyCopy(current)
            }
        }

    /** El espacio del paso 3, o `null` si cabe. */
    private suspend fun spaceShortage(source: RestoreSource, meta: SlMeta?): RestoreStage? {
        val estimate =
            when (source) {
                is RestoreSource.Listed ->
                    if (meta != null) {
                        if (meta.rawSize > BackupLimits.MAX_RESTORE_DECOMPRESSED_BYTES) {
                            return RestoreStage.Refused(RestoreRefusal.TooLarge)
                        }
                        RAW_FACTOR * meta.rawSize
                    } else {
                        compressedEstimate(source.copy.sizeBytes) ?: return tooLarge()
                    }

                is RestoreSource.PickedFile ->
                    source.declaredSizeBytes?.let { compressedEstimate(it) ?: return tooLarge() }
                        ?: 0L

                is RestoreSource.Undo ->
                    RAW_FACTOR * withContext(ioContext) { source.copy.files.database.length() }
            }
        val required = estimate + SPACE_MARGIN_BYTES
        val free = withContext(ioContext) {
            ops.ensureDirectory(files.restoreDirectory)
            freeSpace(files.restoreDirectory)
        }
        return if (free < required) RestoreStage.NotEnoughSpace(required - free) else null
    }

    private fun compressedEstimate(sizeBytes: Long): Long? =
        if (sizeBytes < 0 || sizeBytes > BackupLimits.MAX_BACKUP_DOWNLOAD_BYTES) {
            null
        } else {
            COMPRESSED_FACTOR * sizeBytes
        }

    private fun tooLarge() = RestoreStage.Refused(RestoreRefusal.TooLarge)

    /** Pasos 4 a 6: deja la candidata en su carpeta, o dice por qué no. */
    private suspend fun fetch(current: Session, meta: SlMeta?): RestoreStage? {
        when (val source = current.source) {
            is RestoreSource.Undo -> {
                withContext(ioContext) { prepareUndo(files, ops, source.copy) }
                current.undoPrepared = true
                return null
            }

            is RestoreSource.Listed -> download(source.copy)?.let { return it }

            is RestoreSource.PickedFile -> copyPicked(source)?.let { return it }
        }
        val unpacked = unpacker.unpack(files.incoming, files.candidate, meta?.sha256)
        withContext(ioContext) { ops.delete(files.incoming) }
        return when (unpacked) {
            is UnpackResult.Unpacked -> {
                current.sourceSha256 = unpacked.sourceSha256
                null
            }

            is UnpackResult.Rejected ->
                RestoreStage.Refused(RestoreRefusal.Candidate(unpacked.reason))

            is UnpackResult.Failed -> RestoreStage.Failed(unpacked.cause)
        }
    }

    private suspend fun download(copy: ListedCopy): RestoreStage? {
        val link = connected(active()?.destination ?: linkColumns())
            ?: return RestoreStage.Refused(RestoreRefusal.NoDestination)
        val destination =
            when (val opened = destinations.open(link)) {
                is DestinationOpen.Opened -> opened.destination

                DestinationOpen.PermissionLost ->
                    return RestoreStage.Refused(
                        RestoreRefusal.Download(DestinationFailure.PermissionLost()),
                    )

                DestinationOpen.RetryLater -> return RestoreStage.Failed(null)
            }
        val result = withContext(ioContext) {
            ops.ensureDirectory(files.restoreDirectory)
            FileOutputStream(files.incoming).use { output ->
                destination.download(
                    copy.ref,
                    output,
                    BackupLimits.MAX_BACKUP_DOWNLOAD_BYTES,
                    TransferControl.Unbounded,
                ).also { output.fd.sync() }
            }
        }
        return when (result) {
            is DestinationResult.Success -> null
            is DestinationResult.Failure -> downloadFailure(result.failure)
        }
    }

    private fun downloadFailure(failure: DestinationFailure): RestoreStage = when (failure) {
        is DestinationFailure.Unsupported ->
            if (failure.reason == UnsupportedReason.TOO_LARGE) {
                tooLarge()
            } else {
                RestoreStage.Refused(RestoreRefusal.Download(failure))
            }

        is DestinationFailure.Transient,
        is DestinationFailure.Interrupted,
        DestinationFailure.Incomplete,
        DestinationFailure.LocalStorage,
        -> RestoreStage.Failed(null)

        is DestinationFailure.PermissionLost,
        DestinationFailure.QuotaExceeded,
        DestinationFailure.VerifyMismatch,
        DestinationFailure.ChangedOutside,
        DestinationFailure.NoSafeSlot,
        -> RestoreStage.Refused(RestoreRefusal.Download(failure))
    }

    /** Copia el archivo elegido a su nombre fijo, sin pasar del tope que se acepta bajar. */
    private suspend fun copyPicked(source: RestoreSource.PickedFile): RestoreStage? {
        val input = source.open()
        val copied = withContext(ioContext) {
            ops.ensureDirectory(files.restoreDirectory)
            input.use { stream ->
                FileOutputStream(files.incoming).use { output -> copyBounded(stream, output) }
            }
        }
        return if (copied) null else tooLarge()
    }

    /** Copia [input] en [output] y la sincroniza. Devuelve `false` si pasó del tope. */
    private fun copyBounded(input: InputStream, output: FileOutputStream): Boolean {
        val buffer = ByteArray(BUFFER_BYTES)
        var written = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            written += read
            if (written > BackupLimits.MAX_BACKUP_DOWNLOAD_BYTES) return false
            output.write(buffer, 0, read)
        }
        output.fd.sync()
        return true
    }

    /** Pasos 7 a 11 sobre la candidata, con lo que la persona ya decidió. */
    private suspend fun validate(current: Session): RestoreStage {
        val validation = validator.validate(files.candidate, current.context())
        val checked =
            when (validation) {
                is CandidateValidation.Refused ->
                    return RestoreStage.Refused(RestoreRefusal.Candidate(validation.reason))

                is CandidateValidation.Failed -> return RestoreStage.Failed(validation.cause)

                is CandidateValidation.Checked -> validation
            }
        current.schemaVersion = checked.schemaVersion
        current.warnings = checked.verdict.warnings.size
        return when (val verdict = checked.verdict) {
            is CandidateVerdict.Rejected -> rejected(verdict)
            is CandidateVerdict.NeedsRepair -> RestoreStage.ConfirmRepair(verdict.rows())
            is CandidateVerdict.Restorable -> migrateAndPrepare(current)
        }
    }

    private fun rejected(verdict: CandidateVerdict.Rejected): RestoreStage =
        if (verdict.fatal.all { it.check == CandidateCheck.EMPTY_OVER_DATA }) {
            RestoreStage.ConfirmEmptyCopy
        } else {
            RestoreStage.Refused(RestoreRefusal.Candidate(CandidateRejection.DATA_FATAL))
        }

    private fun CandidateVerdict.NeedsRepair.rows(): Long = repairs.sumOf { it.cases }

    /** Pasos 12 y 13. */
    private suspend fun migrateAndPrepare(current: Session): RestoreStage {
        if (!current.migrated && current.schemaVersion < appSchemaVersion) {
            try {
                migrator.migrate(files.candidate)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                return RestoreStage.Refused(RestoreRefusal.MigrationFailed)
            }
        }
        current.migrated = true
        val now = clock.now()
        when (
            val repaired =
                repair.prepare(files.candidate, current.context(), current.repairConfirmed, now)
        ) {
            is CandidateValidation.Refused ->
                return RestoreStage.Refused(RestoreRefusal.Candidate(repaired.reason))

            is CandidateValidation.Failed -> return RestoreStage.Failed(repaired.cause)

            is CandidateValidation.Checked -> when (val verdict = repaired.verdict) {
                is CandidateVerdict.Rejected -> return rejected(verdict)

                // Solo sin confirmar: confirmado, la reparación incompleta ya se rechazó.
                is CandidateVerdict.NeedsRepair -> return RestoreStage.ConfirmRepair(verdict.rows())

                is CandidateVerdict.Restorable -> current.warnings = verdict.warnings.size
            }
        }
        val view = destinationView(current.activeDatabase)
        val input =
            PreparationInput(
                now = now,
                backedUp = current.isBackedUpIn(view),
                linkState = view.linkState,
                destination = view.columns,
                head = view.head ?: RestoredHead.FromCopy(current.sourceRef()),
                keepLastSuccess = current.source is RestoreSource.Undo,
                activeChecks = current.activeDatabase?.checks,
                deviceSecure = deviceSecure(),
            )
        current.prepared =
            when (val prepared = preparation.prepare(files.candidate, input)) {
                is PreparationResult.Prepared -> prepared.candidate

                is PreparationResult.Refused ->
                    return RestoreStage.Refused(RestoreRefusal.Candidate(prepared.reason))

                is PreparationResult.Failed -> return RestoreStage.Failed(prepared.cause)
            }
        return saveSafetyCopy(current)
    }

    /** Pasos 14 a 16. */
    private suspend fun saveSafetyCopy(current: Session): RestoreStage {
        val prepared = checkNotNull(current.prepared) { "La copia de seguridad va tras preparar" }
        // Sin el cerrojo, una venta pudo entrar: ni copia de seguridad ni diario.
        if (!lock.isOwned(current.lockTakenAt)) return RestoreStage.Refused(RestoreRefusal.LockLost)
        val database = current.activeDatabase
        // Sin la base activa a la vista, la copia se trata como el único respaldo de algo.
        val safetyOk =
            when (
                val saved =
                    safetyCopies.save(
                        database?.changeSeq ?: UNKNOWN_CHANGE_SEQ,
                        database?.verifiedSeq ?: 0L,
                        clock.now(),
                    )
            ) {
                SafetyCopyResult.Saved, SafetyCopyResult.NoActiveDatabase -> true

                SafetyCopyResult.ActiveDatabaseDamaged ->
                    if (current.withoutSafetyCopy) {
                        false
                    } else {
                        return RestoreStage.ConfirmWithoutSafetyCopy
                    }

                is SafetyCopyResult.NeedsChoice ->
                    return RestoreStage.ChooseSafetyCopyToDiscard(saved.copies)

                SafetyCopyResult.StorageFull ->
                    return RestoreStage.Refused(RestoreRefusal.SafetyCopyNoSpace)

                is SafetyCopyResult.Failed -> return RestoreStage.Failed(saved.cause)
            }
        if (!lock.isOwned(current.lockTakenAt)) return RestoreStage.Refused(RestoreRefusal.LockLost)
        return withContext(NonCancellable) {
            val hadActiveDb = withContext(ioContext) { files.database.exists() }
            withContext(ioContext) {
                journalStore.write(
                    RestoreJournal(
                        state = RestoreJournalState.PREPARED,
                        expectedSha256 = prepared.sha256,
                        expectedChangeSeq = prepared.changeSeq,
                        expectedRecordCount = prepared.recordCount,
                        hadActiveDb = hadActiveDb,
                        safetyCopyOk = safetyOk,
                    ),
                )
            }
            // Desde aquí el arranque hace el reemplazo, aunque el proceso muera. La sesión
            // termina: ya no se cancela.
            session = null
            try {
                closeDatabase()
            } catch (failure: Exception) {
                // El diario ya está: el reinicio cierra lo que quedó abierto, y el arranque sigue.
                log.warn(DataEvent.RESTORE_CLOSE_FAILED, failure)
            }
            RestoreStage.ReadyToRestart(current.warnings)
        }
    }

    /** El destino al que restaurar deja conectada la base, y lo que tiene ahora. */
    private suspend fun destinationView(database: ActiveDatabase?): DestinationView {
        val columns = database?.destination ?: linkColumns() ?: DestinationColumns.NONE
        val disconnected = database != null && database.linkState == BackupLinkState.NOT_CONNECTED
        val link = connected(columns)
        if (link == null || disconnected) {
            return DestinationView(columns, BackupLinkState.NOT_CONNECTED, null, emptySet())
        }
        val destination =
            when (val opened = destinations.open(link)) {
                is DestinationOpen.Opened -> opened.destination

                DestinationOpen.PermissionLost ->
                    return DestinationView(columns, BackupLinkState.NOT_CONNECTED, null, emptySet())

                // Un fallo pasajero no es perder el destino: sigue conectado, y la copia
                // restaurada queda como cabeza hasta la próxima lista.
                DestinationOpen.RetryLater ->
                    return DestinationView(columns, BackupLinkState.CONNECTED, null, emptySet())
            }
        return listed(columns, destination)
    }

    private suspend fun listed(
        columns: DestinationColumns,
        destination: BackupDestination,
    ): DestinationView = when (val result = destination.listCopies(TransferControl.Unbounded)) {
        is DestinationResult.Success -> {
            val known = validCopies(result.value)
            val top = known.maxOfOrNull { it.meta.generation }
            val head = known.filter { it.meta.generation == top }.minByOrNull { it.ref }
            DestinationView(
                columns = columns,
                linkState = BackupLinkState.CONNECTED,
                head = RestoredHead.Listed(
                    generation = head?.meta?.generation ?: 0L,
                    deviceId = head?.meta?.deviceId,
                    fileRef = head?.ref,
                ),
                listedSha256 = known.mapTo(HashSet()) { it.meta.sha256 },
            )
        }

        is DestinationResult.Failure ->
            if (result.failure is DestinationFailure.PermissionLost) {
                DestinationView(columns, BackupLinkState.NOT_CONNECTED, null, emptySet())
            } else {
                DestinationView(columns, BackupLinkState.CONNECTED, null, emptySet())
            }
    }

    /** Las copias de la lista cuyos metadatos son válidos y coinciden con su nombre. */
    private fun validCopies(listing: CopyListing): List<KnownListedCopy> =
        listing.copies.mapNotNull { copy ->
            metadataOf(copy)?.let { KnownListedCopy(copy.ref.value, it) }
        }

    private fun metadataOf(copy: ListedCopy): SlMeta? {
        val bytes = (copy.meta as? ListedMeta.Present)?.bytes ?: return null
        val decoded =
            copy.name?.let { SlMetaCodec.decode(bytes, it.identity()) }
                ?: SlMetaCodec.decodeUnnamed(bytes)
        return (decoded as? CopyMetadata.Valid)?.meta
    }

    private fun connected(columns: DestinationColumns?): ConnectedDestination? {
        val kind =
            BackupDestinationKind.entries.firstOrNull { it.name == columns?.kind } ?: return null
        val id = columns?.id ?: return null
        return ConnectedDestination(kind, id, columns.folder)
    }

    /** Borra todo lo de la preparación y deja el respaldo como estaba. La base activa no cambió. */
    private suspend fun abandon(current: Session) {
        withContext(ioContext) {
            ops.delete(files.incoming)
            ops.delete(files.safetyCopyTemporary)
            if (current.undoPrepared) {
                cancelUndo(files, ops)
            } else {
                ops.delete(files.candidateTemporary)
                ops.delete(files.candidate)
            }
            for (suffix in CANDIDATE_SIDE_FILES) ops.delete(File(files.candidate.path + suffix))
            ops.delete(files.pendingKey)
        }
        if (lock.isOwned(current.lockTakenAt, margin = Duration.ZERO)) lock.releaseNow()
        if (current.paused) pause.resume()
        session = null
    }

    private fun logStop(stage: RestoreStage) {
        when (stage) {
            is RestoreStage.Refused ->
                log.warn(DataEvent.RESTORE_REFUSED, LogField.state("motivo", stage.reason.code()))

            is RestoreStage.NotEnoughSpace ->
                log.warn(
                    DataEvent.RESTORE_NO_SPACE,
                    LogField.count("faltan_bytes", stage.missingBytes),
                )

            is RestoreStage.Failed -> {
                val cause = stage.cause
                if (cause == null) {
                    log.warn(DataEvent.RESTORE_FAILED)
                } else {
                    log.warn(DataEvent.RESTORE_FAILED, cause)
                }
            }

            else -> Unit
        }
    }

    /** El código del rechazo para el registro: el de la candidata, o el propio de este paso. */
    private fun RestoreRefusal.code(): Enum<*> = when (this) {
        is RestoreRefusal.Candidate -> reason
        is RestoreRefusal.Download -> RefusalCode.DOWNLOAD
        RestoreRefusal.LockLost -> RefusalCode.LOCK_LOST
        RestoreRefusal.MarkedFatal -> RefusalCode.MARKED_FATAL
        RestoreRefusal.MigrationFailed -> RefusalCode.MIGRATION_FAILED
        RestoreRefusal.NoDestination -> RefusalCode.NO_DESTINATION
        RestoreRefusal.SafetyCopyNoSpace -> RefusalCode.SAFETY_COPY_NO_SPACE
        RestoreRefusal.TooLarge -> RefusalCode.TOO_LARGE
    }

    /** Los rechazos que no vienen de la candidata, por su nombre en el registro. */
    private enum class RefusalCode {
        DOWNLOAD,
        LOCK_LOST,
        MARKED_FATAL,
        MIGRATION_FAILED,
        NO_DESTINATION,
        SAFETY_COPY_NO_SPACE,
        TOO_LARGE,
    }

    /** Una preparación en curso, con lo que ya se sabe y lo que la persona ya decidió. */
    private class Session(val source: RestoreSource, val lockTakenAt: Long) {
        var paused = false
        var undoPrepared = false
        var expected: ExpectedCopy? = null
        var sourceSha256: String? = null
        var activeDatabase: ActiveDatabase? = null
        var schemaVersion = 0
        var warnings = 0
        var emptyCopyConfirmed = false
        var repairConfirmed = false
        var migrated = false
        var withoutSafetyCopy = false
        var prepared: PreparedCandidate? = null

        /** Sin la base activa a la vista, se trata como si tuviera datos: no se pisa sin decidir. */
        fun context(): CandidateContext = CandidateContext(
            expectedCopy = expected,
            activeHasData = activeDatabase?.hasData ?: true,
            emptyCopyConfirmed = emptyCopyConfirmed,
        )

        /**
         * Si la candidata ya está en el destino conectado, por suma, sin reparación de por medio.
         * Deshacer nunca cuenta: esa copia nunca subió.
         */
        fun isBackedUpIn(view: DestinationView): Boolean {
            if (source is RestoreSource.Undo || repairConfirmed) return false
            val sha = sourceSha256 ?: return false
            return sha in view.listedSha256
        }

        fun sourceRef(): String? = (source as? RestoreSource.Listed)?.copy?.ref?.value
    }

    /** Lo que se sabe del destino al preparar. [head] es nulo si no se pudo listar. */
    private class DestinationView(
        val columns: DestinationColumns,
        val linkState: BackupLinkState,
        val head: RestoredHead?,
        val listedSha256: Set<String>,
    )

    private class KnownListedCopy(val ref: String, val meta: SlMeta)

    private companion object {
        /** Tres veces la base: la candidata, la copia de seguridad y lo que migre o se repare. */
        const val RAW_FACTOR = 3L

        /** Sin tamaño de la base, nueve veces el archivo: gzip la reduce unas tres veces. */
        const val COMPRESSED_FACTOR = 9L

        const val SPACE_MARGIN_BYTES = 50L * 1024 * 1024
        const val BUFFER_BYTES = 64 * 1024

        /** Sin contadores de la base activa, la copia de seguridad cuenta como sin subir. */
        const val UNKNOWN_CHANGE_SEQ = 1L

        val CANDIDATE_SIDE_FILES = listOf("-journal", "-wal", "-shm")
    }
}

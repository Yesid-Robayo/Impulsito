package io.github.yesidrobayo.shopledger.data.backup

import android.system.ErrnoException
import android.system.OsConstants
import io.github.yesidrobayo.shopledger.data.crypto.DurableFile
import io.github.yesidrobayo.shopledger.data.destination.BackupDestination
import io.github.yesidrobayo.shopledger.data.destination.BackupFileFormat
import io.github.yesidrobayo.shopledger.data.destination.BackupFileName
import io.github.yesidrobayo.shopledger.data.destination.ConnectedDestination
import io.github.yesidrobayo.shopledger.data.destination.CopyListing
import io.github.yesidrobayo.shopledger.data.destination.CopyMetadata
import io.github.yesidrobayo.shopledger.data.destination.CopyRef
import io.github.yesidrobayo.shopledger.data.destination.DestinationFailure
import io.github.yesidrobayo.shopledger.data.destination.DestinationOpen
import io.github.yesidrobayo.shopledger.data.destination.DestinationOpener
import io.github.yesidrobayo.shopledger.data.destination.DestinationResult
import io.github.yesidrobayo.shopledger.data.destination.InterruptReason
import io.github.yesidrobayo.shopledger.data.destination.ListedMeta
import io.github.yesidrobayo.shopledger.data.destination.OutgoingCopy
import io.github.yesidrobayo.shopledger.data.destination.SlMeta
import io.github.yesidrobayo.shopledger.data.destination.SlMetaCodec
import io.github.yesidrobayo.shopledger.data.destination.TransferControl
import io.github.yesidrobayo.shopledger.data.destination.VerifyMode
import io.github.yesidrobayo.shopledger.data.destination.identity
import io.github.yesidrobayo.shopledger.data.local.backup.BackupStateDao
import io.github.yesidrobayo.shopledger.data.local.backup.BackupStateEntity
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupConflictKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupFailureCode
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupLimits
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupLinkState
import io.github.yesidrobayo.shopledger.domain.model.backup.CopyInstant
import io.github.yesidrobayo.shopledger.domain.model.backup.NewCopyMarks
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotCandidate
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.LogField
import io.github.yesidrobayo.shopledger.domain.port.warn
import java.io.File
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * El motor del respaldo: una ejecución arma una copia de la base, la sube al destino, la comprueba
 * y solo entonces la confirma y deja podar. Nunca pisa ni borra la última copia buena antes de que
 * la nueva esté comprobada.
 *
 * Los pasos, en orden, con lo que pasa si fallan:
 *
 * 0. **Precondiciones.** Sin destino conectado, sin cambios pendientes o con la base vacía, termina
 *    sin hacer nada. Limpia los restos de la carpeta de preparación, salvo el archivo de una subida
 *    pendiente.
 * 1. **Espacio y tamaño.** El teléfono debe tener libre una vez y media la base, y la base no debe
 *    pasar de lo que una restauración acepta: si no, `LOCAL_STORAGE_LOW` o `BACKUP_TOO_LARGE`.
 * 2. y 3. **Destino y credencial.** Abre el destino que recuerda el estado. Si no deja entrar, el
 *    enlace pasa a acceso perdido.
 * 4. **Listar.** Lo que hay en el destino. Una lista con errores nunca es una carpeta vacía: se
 *    reintenta.
 * 5. **Retomar.** Una subida que quedó sin confirmar todavía no se retoma: la copia nueva lleva una
 *    generación mayor, y la anterior queda en el destino sin confirmar, sin pisar nada.
 * 6. **Guarda.** [BackupGuard] decide si se puede subir y con qué generación, comparando la
 *    cabeza del destino con lo que esta base conoce y tiene. Si el destino tiene algo que una copia
 *    nueva taparía, el enlace queda en conflicto y la persona decide; si la cabeza viene de una app
 *    más nueva, `CLOUD_SCHEMA_NEWER`. En ningún caso genera nada.
 * 7. **Instantánea.** `VACUUM INTO` en una conexión propia, mientras la app sigue vendiendo.
 * 8. **Sello.** Limpia de la instantánea lo que no viaja en una copia, corre `integrity_check` y lee
 *    los conteos. Una instantánea dañada nunca sube: `LOCAL_CORRUPT`. Después la revisa el mismo
 *    validador de las restauraciones. Con saldos por recalcular, la copia sube igual, marcada como
 *    no válida, y la base activa guarda cuántos elementos son. Si la app no podría restaurarla,
 *    con WebDAV sube marcada como fatal, solo para un rescate manual, y con ranuras no se escribe:
 *    taparía una copia que sí se restaura.
 * 9. **Comprimir.** gzip con su suma, comprobado al releerlo, y los metadatos en la carpeta.
 * 10. **Intención y subida.** Guarda en el estado qué copia va en camino, y solo después escribe en
 *     el destino. Al enviar el último byte anota la hora.
 * 11. **Comprobar.** Lo que el destino tiene debe ser lo que se subió. Si no, `VERIFY_MISMATCH`, y
 *     esa copia nunca se confirma.
 * 12. **Confirmar.** En una sola sentencia, y solo si el destino sigue siendo el de esta ejecución.
 *     Una copia fatal pasa a ser la cabeza, pero los cambios siguen sin respaldar.
 * 13. **Podar.** Solo después de confirmar, y un fallo ahí no invalida la copia.
 * 14. **Terminar.** Borra los temporales en un `finally`, salvo el archivo de una subida que se
 *     reintenta, que se conserva hasta un día.
 *
 * Entre pasos revisa si el sistema pidió detenerse. Una ejecución a la vez, bajo [mutex]: la
 * segunda espera, y al entrar vuelve a mirar si queda algo pendiente.
 *
 * Nada de lo que registra lleva datos del negocio, la dirección del servicio ni un nombre de
 * archivo: solo códigos, generaciones y conteos, y de una falla, su clase.
 *
 * @param databaseFile la base del negocio, con ruta absoluta.
 * @param deviceId da el identificador de esta instalación.
 * @param guard decide si se sube y con qué generación.
 * @param pruner poda el destino tras confirmar.
 * @param freeSpace los bytes libres para la app en la carpeta dada.
 * @param verifyMode cuánto del contenido se lee para comprobar, según la red.
 * @param transportFailures cuenta los fallos de transporte seguidos de cada ejecución que se
 *   reintenta, para dejar a la vista uno que no se resuelve solo.
 * @param snapshotValidation revisa la instantánea con el validador de las restauraciones.
 * @param appVersion la versión de la app para los metadatos, si tiene la forma que aceptan.
 * @param deviceLabel fabricante y modelo del teléfono para los metadatos, ya limpios.
 * @param mutex el cerrojo del respaldo, uno por proceso.
 */
internal class BackupRunner(
    private val state: BackupStateDao,
    private val databaseFile: File,
    private val destinations: DestinationOpener,
    private val snapshots: SnapshotCreator,
    private val sealer: SnapshotSealer,
    private val packager: BackupPackager,
    private val staging: BackupStaging,
    private val deviceId: suspend () -> String,
    private val clock: Clock,
    private val log: EventLog,
    private val ioContext: CoroutineContext,
    private val schemaVersion: Int,
    private val appVersion: String?,
    private val deviceLabel: String?,
    private val mutex: Mutex,
    private val guard: CopyGuard = BackupGuard,
    private val pruner: CopyPruner = CopyPruner.None,
    private val freeSpace: (File) -> Long = File::getUsableSpace,
    private val verifyMode: (TransferControl) -> VerifyMode = ::verifyModeFor,
    private val transportFailures: TransportFailures = TransportFailures(state),
    private val snapshotValidation: SnapshotValidation =
        SnapshotValidation(CandidateValidator(ioContext, schemaVersion)),
) {
    /** Corre una ejecución entera. Solo lanza la cancelación, o una falla que nadie previó. */
    suspend fun run(control: TransferControl): BackupRun = mutex.withLock {
        val current =
            state.get() ?: return@withLock stop(StopCause.Failure(BackupFailureCode.UNEXPECTED))
        val attempt = Attempt(current.pendingGeneration)
        try {
            steps(current, control, attempt).also {
                attempt.outcome = it
                transportFailures.record(it)
            }
        } finally {
            withContext(NonCancellable + ioContext) {
                staging.cleanUp(attempt.keptGeneration(), clock.now())
            }
        }
    }

    /** Lo que la limpieza del final necesita saber de la ejecución. */
    private class Attempt(var pendingGeneration: Long?) {
        var outcome: BackupRun? = null

        /** El archivo de la subida pendiente se conserva solo si la ejecución se reintenta. */
        fun keptGeneration(): Long? = pendingGeneration.takeIf { outcome is BackupRun.Retry }
    }

    private suspend fun steps(
        current: BackupStateEntity,
        control: TransferControl,
        attempt: Attempt,
    ): BackupRun {
        // Paso 0: precondiciones.
        val link = connectedOf(current) ?: return BackupRun.Skipped(SkipReason.NOT_CONNECTED)
        if (current.changeSeq == current.verifiedSeq && current.pendingGeneration == null) {
            return BackupRun.Skipped(SkipReason.NOTHING_PENDING)
        }
        if (state.isEmpty()) return BackupRun.Skipped(SkipReason.EMPTY_DATABASE)
        withContext(ioContext) { staging.cleanUp(current.pendingGeneration, clock.now()) }
        state.markAttempt(clock.nowEpochMillis())

        // Paso 1: espacio y tamaño.
        spaceProblem()?.let { return stop(StopCause.Failure(it)) }
        interruption(control)?.let { return it }

        // Pasos 2 y 3: destino y credencial.
        val destination =
            when (val opened = destinations.open(link)) {
                is DestinationOpen.Opened -> opened.destination

                DestinationOpen.PermissionLost -> return permissionLost(link)

                DestinationOpen.RetryLater ->
                    return BackupRun.Retry(RetryReason.DESTINATION_NOT_READY)
            }

        // Paso 4: listar.
        val listing =
            when (val listed = destination.listCopies(control)) {
                is DestinationResult.Success -> listed.value
                is DestinationResult.Failure -> return failed(listed.failure, link)
            }
        interruption(control)?.let { return it }

        // Paso 6: guarda.
        val device = deviceId()
        val generation =
            when (val decision = guard.decide(guardInput(listing, current, device))) {
                is GuardDecision.Upload -> decision.generation

                GuardDecision.Empty -> return BackupRun.Skipped(SkipReason.EMPTY_DATABASE)

                is GuardDecision.Refused -> return stop(StopCause.Failure(decision.code))

                is GuardDecision.Conflict ->
                    return conflict(
                        decision.kind,
                        link,
                        ConflictMark(
                            decision.head.generation,
                            decision.head.deviceId,
                            decision.head.ref,
                        ),
                    )
            }

        // Pasos 7 a 9: la copia.
        val copy =
            when (val made = makeCopy(generation, device, destination.kind, control)) {
                is Made.Copy -> made.copy
                is Made.Stop -> return made.run
            }

        // Paso 10: intención y subida.
        val plan =
            when (val planned = destination.planUpload(copy.outgoing, headRefOf(current))) {
                is DestinationResult.Success -> planned.value
                is DestinationResult.Failure -> return failed(planned.failure, link)
            }
        interruption(control)?.let { return it }
        state.saveIntent(generation, copy.changeSeq, copy.outgoing.sha256, copy.outgoing.sizeBytes)
        attempt.pendingGeneration = generation
        val stored =
            when (val uploaded = destination.upload(plan, control)) {
                is DestinationResult.Success -> uploaded.value
                is DestinationResult.Failure -> return failed(uploaded.failure, link)
            }
        state.markUploadFinished(clock.nowEpochMillis())

        // Paso 11: comprobar.
        val mode = verifyMode(control)
        when (val verified = destination.verify(stored, mode, copy.outgoing.file, control)) {
            is DestinationResult.Success -> Unit

            is DestinationResult.Failure -> {
                if (verified.failure == DestinationFailure.VerifyMismatch) {
                    // Esa copia nunca se confirma, ni después por otra vía.
                    state.clearIntent()
                    attempt.pendingGeneration = null
                }
                return failed(verified.failure, link)
            }
        }

        // Paso 12: confirmar.
        when (val confirmed = destination.confirm(stored)) {
            is DestinationResult.Success -> Unit
            is DestinationResult.Failure -> return failed(confirmed.failure, link)
        }
        val rows =
            if (copy.marks.fatal) {
                // La app no la restaura: es la cabeza, pero los cambios siguen sin respaldar.
                state.confirmUnrestorable(
                    generation = generation,
                    deviceId = device,
                    fileRef = stored.ref.value,
                    failureCode = BackupFailureCode.LOCAL_INVARIANT_FATAL.name,
                    destinationId = link.destinationId,
                )
            } else {
                state.confirm(
                    generation = generation,
                    deviceId = device,
                    fileRef = stored.ref.value,
                    seq = copy.changeSeq,
                    now = clock.nowEpochMillis(),
                    copiedAt = copy.createdAtMillis,
                    failureCode =
                        BackupFailureCode.LOCAL_INVARIANT.name.takeUnless { copy.marks.valid },
                    destinationId = link.destinationId,
                )
            }
        if (rows == 0) return BackupRun.Skipped(SkipReason.DESTINATION_CHANGED)
        attempt.pendingGeneration = null

        // Paso 13: podar, solo ahora.
        if (destination.kind == BackupDestinationKind.WEBDAV) {
            prune(destination, ConfirmedCopy(generation, stored.ref), control)
        }
        return BackupRun.Verified(generation, unrestorable = copy.marks.fatal)
    }

    /** Una copia lista para subir, con lo que sale de su instantánea y de su revisión. */
    private class ReadyCopy(
        val outgoing: OutgoingCopy,
        val changeSeq: Long,
        val createdAtMillis: Long,
        val marks: NewCopyMarks,
    )

    private sealed interface Made {
        class Copy(val copy: ReadyCopy) : Made

        class Stop(val run: BackupRun) : Made
    }

    private suspend fun makeCopy(
        generation: Long,
        device: String,
        kind: BackupDestinationKind,
        control: TransferControl,
    ): Made {
        val now = clock.now()
        val createdAt = CopyInstant.of(now) ?: return stopWith(BackupFailureCode.UNEXPECTED)

        // Paso 7: instantánea.
        val snapshot =
            when (val created = snapshots.create(staging.snapshotFile(generation))) {
                is SnapshotResult.Created -> created
                SnapshotResult.DatabaseMissing -> return stopWith(BackupFailureCode.UNEXPECTED)
                SnapshotResult.Corrupt -> return stopWith(BackupFailureCode.LOCAL_CORRUPT)
                SnapshotResult.StorageFull -> return stopWith(BackupFailureCode.LOCAL_STORAGE_LOW)
                is SnapshotResult.Failed -> return localFailure(created.cause)
            }
        interruption(control)?.let { return Made.Stop(it) }

        // Paso 8: sello, comprobación y conteos.
        val sealed =
            when (
                val result =
                    sealer.seal(
                        snapshot.file,
                        CopySeal(generation, device, now.toEpochMilliseconds()),
                    )
            ) {
                is SealResult.Sealed -> result
                SealResult.NoState -> return stopWith(BackupFailureCode.LOCAL_CORRUPT)
                SealResult.Corrupt -> return stopWith(BackupFailureCode.LOCAL_CORRUPT)
                SealResult.StorageFull -> return stopWith(BackupFailureCode.LOCAL_STORAGE_LOW)
                is SealResult.Failed -> return localFailure(result.cause)
            }
        when (val integrity = snapshots.checkIntegrity(snapshot.file)) {
            IntegrityResult.Intact -> Unit
            IntegrityResult.Damaged -> return stopWith(BackupFailureCode.LOCAL_CORRUPT)
            is IntegrityResult.Failed -> return localFailure(integrity.cause)
        }
        val marks =
            when (val verdict = snapshotValidation.check(snapshot.file)) {
                is SnapshotVerdict.Checked -> {
                    if (verdict.repairCases > 0) state.recordInvariantFailures(verdict.repairCases)
                    verdict.marks
                }

                SnapshotVerdict.Corrupt -> return stopWith(BackupFailureCode.LOCAL_CORRUPT)

                is SnapshotVerdict.Failed -> return localFailure(verdict.cause)
            }
        if (marks.fatal && kind == BackupDestinationKind.DOCUMENT_SLOTS) {
            // Escribirla tapa una ranura que sí se restaura: no se escribe, y los cambios siguen
            // sin respaldar hasta una copia que la app pueda restaurar.
            state.recordFailure(BackupFailureCode.LOCAL_INVARIANT_FATAL.name)
            return Made.Stop(BackupRun.Skipped(SkipReason.UNRESTORABLE_NOT_WRITTEN))
        }
        interruption(control)?.let { return Made.Stop(it) }
        val rawSize = snapshot.file.length()
        if (rawSize > BackupLimits.MAX_RESTORE_DECOMPRESSED_BYTES) {
            return stopWith(BackupFailureCode.BACKUP_TOO_LARGE)
        }
        interruption(control)?.let { return Made.Stop(it) }

        // Paso 9: comprimir.
        val packed =
            when (
                val result = packager.compress(snapshot.file, staging.compressedFile(generation))
            ) {
                is PackageResult.Packed -> result
                PackageResult.TooLarge -> return stopWith(BackupFailureCode.BACKUP_TOO_LARGE)
                PackageResult.StorageFull -> return stopWith(BackupFailureCode.LOCAL_STORAGE_LOW)
                PackageResult.Mismatch -> return stopWith(BackupFailureCode.UNEXPECTED)
                is PackageResult.Failed -> return localFailure(result.cause)
            }
        val meta =
            SlMeta(
                format = PLAIN_FORMAT,
                keyId = null,
                deviceId = device,
                deviceLabel = deviceLabel,
                generation = generation,
                schemaVersion = schemaVersion.toLong(),
                appVersion = appVersion,
                sha256 = packed.fileSha256,
                rawSize = packed.rawSizeBytes,
                fileSize = packed.fileSizeBytes,
                records = sealed.records,
                catalogCount = sealed.catalogCount,
                valid = marks.valid,
                fatal = marks.fatal,
            )
        val metaBytes = SlMetaCodec.encode(meta)
        writeMeta(generation, metaBytes)?.let { return Made.Stop(it) }
        val name =
            BackupFileName(
                generation = generation,
                schemaVersion = schemaVersion,
                createdAt = createdAt,
                devicePrefix = device.take(DEVICE_PREFIX_LENGTH),
                format = BackupFileFormat.GZIP,
            )
        val outgoing =
            OutgoingCopy(
                file = packed.file,
                name = name,
                sizeBytes = packed.fileSizeBytes,
                sha256 = packed.fileSha256,
                metaBytes = metaBytes,
                candidate = SlotCandidate(marks, sealed.records, sealed.catalogCount),
            )
        return Made.Copy(
            ReadyCopy(outgoing, sealed.changeSeq, now.toEpochMilliseconds(), marks),
        )
    }

    /** Deja los metadatos junto a la copia, para subirlos si la subida se corta entre los dos. */
    private suspend fun writeMeta(generation: Long, bytes: ByteArray): BackupRun? =
        withContext(ioContext) {
            try {
                DurableFile.write(staging.metaFile(generation), bytes)
                null
            } catch (failure: IOException) {
                if (isStorageFull(failure)) {
                    stop(StopCause.Failure(BackupFailureCode.LOCAL_STORAGE_LOW))
                } else {
                    log.warn(BackupEvent.LOCAL_FAILURE, failure)
                    BackupRun.Retry(RetryReason.LOCAL_FAILURE)
                }
            }
        }

    /** Paso 1. Lee el disco. */
    private suspend fun spaceProblem(): BackupFailureCode? = withContext(ioContext) {
        val databaseBytes =
            DATABASE_SUFFIXES.sumOf {
                File(databaseFile.path + it).takeIf(File::isFile)?.length()
                    ?: 0L
            }
        if (databaseBytes > BackupLimits.MAX_RESTORE_DECOMPRESSED_BYTES) {
            return@withContext BackupFailureCode.BACKUP_TOO_LARGE
        }
        staging.directory.mkdirs()
        // Cabe sin desbordar: la base no pasa del tope de restauración, de 1 GiB.
        val needed = databaseBytes + databaseBytes / 2
        if (freeSpace(staging.directory) < needed) BackupFailureCode.LOCAL_STORAGE_LOW else null
    }

    private suspend fun prune(
        destination: BackupDestination,
        confirmed: ConfirmedCopy,
        control: TransferControl,
    ) {
        when (val pruned = pruner.prune(destination, confirmed, control)) {
            is DestinationResult.Success -> Unit

            is DestinationResult.Failure ->
                log.warn(
                    BackupEvent.PRUNE_FAILED,
                    LogField.state("fallo", failureName(pruned.failure)),
                )
        }
    }

    /**
     * Traduce un fallo del destino a lo que el respaldo hace con él: el estado que deja a la vista y
     * si el trabajo se reintenta.
     */
    private suspend fun failed(failure: DestinationFailure, link: ConnectedDestination): BackupRun =
        when (failure) {
            is DestinationFailure.PermissionLost -> permissionLost(link)

            is DestinationFailure.Unsupported ->
                stop(StopCause.Failure(BackupFailureCode.DESTINATION_UNSUPPORTED))

            DestinationFailure.QuotaExceeded ->
                stop(StopCause.Failure(BackupFailureCode.QUOTA_EXCEEDED))

            DestinationFailure.VerifyMismatch ->
                stop(StopCause.Failure(BackupFailureCode.VERIFY_MISMATCH))

            // De ese lado no hay generación ni teléfono: solo la ranura que cambió.
            is DestinationFailure.ChangedOutside ->
                conflict(
                    BackupConflictKind.OTHER_DEVICE,
                    link,
                    ConflictMark(null, null, failure.ref),
                )

            // Ninguna ranura deja escribir sin tapar una copia con más datos: la persona decide.
            // Sin generación, a diferencia de la pérdida de datos que ve la guarda: así se sabe
            // que las tres ranuras quedaron bloqueadas.
            is DestinationFailure.NoSafeSlot ->
                conflict(
                    BackupConflictKind.LOCAL_SHRUNK,
                    link,
                    ConflictMark(null, null, failure.conflictRef),
                )

            // Falló el disco del teléfono, no el destino: se reintenta, y se ve como falta de
            // espacio, la causa más probable.
            DestinationFailure.LocalStorage -> {
                state.recordFailure(BackupFailureCode.LOCAL_STORAGE_LOW.name)
                BackupRun.Retry(RetryReason.LOCAL_STATE_NOT_SAVED)
            }

            DestinationFailure.Incomplete -> BackupRun.Retry(RetryReason.INCOMPLETE_LISTING)

            is DestinationFailure.Transient ->
                BackupRun.Retry(RetryReason.TRANSIENT, transientKind = failure.kind)

            is DestinationFailure.Interrupted ->
                BackupRun.Retry(RetryReason.INTERRUPTED, interruptReason = failure.reason)
        }

    private suspend fun permissionLost(link: ConnectedDestination): BackupRun {
        state.leaveConnected(BackupLinkState.PERMISSION_LOST.name, link.destinationId)
        return BackupRun.Stopped(StopCause.PermissionLost)
    }

    /** Lo que el conflicto guarda del lado del destino. Cada dato puede faltar. */
    private class ConflictMark(val generation: Long?, val deviceId: String?, val ref: CopyRef?)

    private suspend fun conflict(
        kind: BackupConflictKind,
        link: ConnectedDestination,
        mark: ConflictMark,
    ): BackupRun {
        state.markConflict(
            kind.name,
            mark.generation,
            mark.deviceId,
            mark.ref?.value,
            link.destinationId,
        )
        return BackupRun.Stopped(StopCause.Conflict(kind))
    }

    /** Deja el código a la vista y detiene la ejecución. */
    private suspend fun stop(cause: StopCause.Failure): BackupRun {
        state.recordFailure(cause.code.name)
        return BackupRun.Stopped(cause)
    }

    private suspend fun stopWith(code: BackupFailureCode): Made =
        Made.Stop(stop(StopCause.Failure(code)))

    private fun localFailure(cause: Exception): Made {
        log.warn(BackupEvent.LOCAL_FAILURE, cause)
        return Made.Stop(BackupRun.Retry(RetryReason.LOCAL_FAILURE))
    }

    /** Si el sistema pidió detenerse o se acabó el tiempo, la ejecución se corta entre pasos. */
    private fun interruption(control: TransferControl): BackupRun? = when {
        control.isStopped() ->
            BackupRun.Retry(RetryReason.INTERRUPTED, interruptReason = InterruptReason.STOPPED)

        control.remainingMillis()?.let { it <= 0 } == true ->
            BackupRun.Retry(RetryReason.INTERRUPTED, interruptReason = InterruptReason.OUT_OF_TIME)

        else -> null
    }

    private suspend fun guardInput(
        listing: CopyListing,
        current: BackupStateEntity,
        device: String,
    ): GuardInput = GuardInput(
        copies = knownCopies(listing),
        deviceId = device,
        headGeneration = current.headGeneration,
        headDeviceId = current.headDeviceId,
        pendingGeneration = current.pendingGeneration,
        appSchemaVersion = schemaVersion,
        local =
            LocalCounts(
                // El paso 0 ya salió con la base vacía.
                isEmpty = false,
                records = state.recordCount(),
                catalogCount = state.catalogCount(),
                pendingChanges = current.changeSeq - current.verifiedSeq,
            ),
        shrinkAcceptedGeneration = current.shrinkAcceptedGeneration,
        highestOwnGeneration = highestOwnGeneration(listing, device),
    )

    private fun headRefOf(current: BackupStateEntity): CopyRef? =
        current.headFileRef?.takeIf { it.isNotEmpty() && it.length <= CopyRef.MAX_LENGTH }
            ?.let(::CopyRef)

    private companion object {
        /** Las copias van sin cifrar hasta que exista el cifrado opcional. */
        const val PLAIN_FORMAT = 1

        const val DEVICE_PREFIX_LENGTH = 8

        /** La base y su diario de escritura anticipada: los dos cuentan para el espacio. */
        val DATABASE_SUFFIXES = listOf("", "-wal")
    }
}

/**
 * Poda el destino después de confirmar una copia. Solo se llama con la copia nueva ya confirmada,
 * y un fallo aquí no la invalida: la poda sigue en la ejecución siguiente.
 */
internal fun interface CopyPruner {
    suspend fun prune(
        destination: BackupDestination,
        confirmed: ConfirmedCopy,
        control: TransferControl,
    ): DestinationResult<PruneReport>

    companion object {
        /** No poda nada: todas las copias quedan. */
        val None =
            CopyPruner { _, _, _ ->
                DestinationResult.Success(PruneReport(PruneResult.NOT_APPLICABLE, 0))
            }
    }
}

/**
 * La copia que el respaldo acaba de confirmar: la poda no corre si no la ve en la lista.
 *
 * @property ref su nombre en el destino.
 */
internal data class ConfirmedCopy(val generation: Long, val ref: CopyRef)

/** Cómo terminó una poda. */
internal enum class PruneResult {
    /** Se podó lo que la regla dice, hasta el tope de la ejecución. */
    PRUNED,

    /** Con ranuras no hay poda. */
    NOT_APPLICABLE,

    /** La copia nueva no aparece en la lista, o no con su tamaño: la lista no se cree. */
    INCOMPLETE_LISTING,

    /** Hay una copia de otro teléfono tan nueva como la confirmada: eso lo decide la persona. */
    FOREIGN_AHEAD,

    /** Una copia por podar tiene más registros que la cabeza, sin una fijada que los cubra. */
    BLOCKED_BY_RECORDS,
}

/**
 * Lo que hizo una poda.
 *
 * @property deleted cuántos archivos borró.
 */
internal data class PruneReport(val result: PruneResult, val deleted: Int)

/**
 * Las copias del destino cuyos metadatos se pueden creer: válidos, del mismo nombre que su copia y
 * con el tamaño que dicen. Los metadatos de una ranura no tienen nombre con que compararse.
 */
internal fun knownCopies(listing: CopyListing): List<KnownCopy> =
    listing.copies.mapNotNull { copy ->
        val bytes = (copy.meta as? ListedMeta.Present)?.bytes ?: return@mapNotNull null
        val decoded =
            copy.name?.let { SlMetaCodec.decode(bytes, it.identity()) }
                ?: SlMetaCodec.decodeUnnamed(bytes)
        val meta = (decoded as? CopyMetadata.Valid)?.meta ?: return@mapNotNull null
        if (meta.fileSize != copy.sizeBytes) return@mapNotNull null
        KnownCopy(
            ref = copy.ref,
            generation = meta.generation,
            deviceId = meta.deviceId,
            schemaVersion = meta.schemaVersion,
            format = meta.format,
            records = meta.records,
            catalogCount = meta.catalogCount,
        )
    }

/**
 * La mayor generación que el destino muestra de [deviceId] por los nombres, cuente o no para
 * decidir: copias sin metadatos o con otro tamaño, metadatos sueltos y temporales. Una copia nueva
 * con esa generación chocaría con un nombre que ya está. 0 si no hay ninguna.
 */
internal fun highestOwnGeneration(listing: CopyListing, deviceId: String): Long {
    // El nombre lleva los primeros 8 caracteres del teléfono, igual que al subir.
    val prefix = deviceId.take(8)
    val named =
        listing.copies.mapNotNull { it.name } +
            listing.orphanMetas.mapNotNull { BackupFileName.parseMeta(it.value) } +
            listing.temporaries.mapNotNull { temporary ->
                val base = temporary.ref.value.removeSuffix(BackupFileName.TEMPORARY_SUFFIX)
                BackupFileName.parse(base) ?: BackupFileName.parseMeta(base)
            }
    return named.filter { it.devicePrefix == prefix }.maxOfOrNull(BackupFileName::generation) ?: 0
}

/** El destino conectado del estado, o nulo si no hay uno al que subir. */
internal fun connectedOf(current: BackupStateEntity): ConnectedDestination? {
    if (current.linkState != BackupLinkState.CONNECTED.name) return null
    return destinationOf(current)
}

/** El destino que recuerda el estado, en cualquier estado del enlace, o nulo si no hay uno. */
internal fun destinationOf(current: BackupStateEntity): ConnectedDestination? {
    val kind =
        BackupDestinationKind.entries.firstOrNull { it.name == current.destinationKind }
            ?: return null
    val id = current.destinationId ?: return null
    return ConnectedDestination(kind, id, current.destinationFolder)
}

/** Con red no medida se lee la copia entera, y con red medida solo una muestra. */
internal fun verifyModeFor(control: TransferControl): VerifyMode =
    if (control.isMetered()) VerifyMode.SAMPLE else VerifyMode.FULL

/** El nombre del fallo del destino para el registro: un código, nunca datos. */
internal fun failureName(failure: DestinationFailure): DestinationFailureName = when (failure) {
    is DestinationFailure.PermissionLost -> DestinationFailureName.PERMISSION_LOST
    is DestinationFailure.Unsupported -> DestinationFailureName.UNSUPPORTED
    DestinationFailure.QuotaExceeded -> DestinationFailureName.QUOTA_EXCEEDED
    DestinationFailure.LocalStorage -> DestinationFailureName.LOCAL_STORAGE
    DestinationFailure.VerifyMismatch -> DestinationFailureName.VERIFY_MISMATCH
    is DestinationFailure.ChangedOutside -> DestinationFailureName.CHANGED_OUTSIDE
    is DestinationFailure.NoSafeSlot -> DestinationFailureName.NO_SAFE_SLOT
    DestinationFailure.Incomplete -> DestinationFailureName.INCOMPLETE
    is DestinationFailure.Transient -> DestinationFailureName.TRANSIENT
    is DestinationFailure.Interrupted -> DestinationFailureName.INTERRUPTED
}

/** Los fallos del destino como códigos para el registro. */
internal enum class DestinationFailureName {
    PERMISSION_LOST,
    UNSUPPORTED,
    QUOTA_EXCEEDED,
    LOCAL_STORAGE,
    VERIFY_MISMATCH,
    CHANGED_OUTSIDE,
    NO_SAFE_SLOT,
    INCOMPLETE,
    TRANSIENT,
    INTERRUPTED,
}

/** Si la falla dice que el disco se llenó. */
private fun isStorageFull(failure: IOException): Boolean =
    generateSequence<Throwable>(failure) { it.cause }
        .any { it is ErrnoException && it.errno == OsConstants.ENOSPC }

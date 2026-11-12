package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.destination.BackupDestination
import io.github.yesidrobayo.shopledger.data.destination.CopyMetadata
import io.github.yesidrobayo.shopledger.data.destination.CopyRef
import io.github.yesidrobayo.shopledger.data.destination.DestinationFailure
import io.github.yesidrobayo.shopledger.data.destination.DestinationOpen
import io.github.yesidrobayo.shopledger.data.destination.DestinationOpener
import io.github.yesidrobayo.shopledger.data.destination.DestinationResult
import io.github.yesidrobayo.shopledger.data.destination.ListedMeta
import io.github.yesidrobayo.shopledger.data.destination.SlMeta
import io.github.yesidrobayo.shopledger.data.destination.SlMetaCodec
import io.github.yesidrobayo.shopledger.data.destination.TransferControl
import io.github.yesidrobayo.shopledger.data.destination.identity
import io.github.yesidrobayo.shopledger.data.local.backup.BackupStateDao
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDrillResult
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupFailureCode
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupLimits
import io.github.yesidrobayo.shopledger.domain.model.backup.CandidateVerdict
import io.github.yesidrobayo.shopledger.domain.model.backup.DrillOutcome
import io.github.yesidrobayo.shopledger.domain.model.backup.FullVerifyOutcome
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.info
import io.github.yesidrobayo.shopledger.domain.port.warn
import java.io.File
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * La comprobación completa de la cabeza, una vez al mes y solo con una red sin datos móviles: el
 * simulacro de restaurar, sin restaurar nada. Prueba que la copia que cuenta como la válida más
 * reciente se puede restaurar de verdad, también el centro del archivo, que una muestra no ve.
 *
 * 1. Baja la cabeza a `backup-staging/deep-check/`, sin pasar del mayor archivo que se acepta.
 *    Con la nube del teléfono la lee del proveedor: prueba lo que este teléfono recibe, no que la
 *    otra app la haya subido.
 * 2. La descomprime comparando la suma de sus metadatos, y le pasa el validador de las
 *    restauraciones, como la preparación de una restauración, sobre un temporal.
 * 3. Borra todo lo que bajó, pase lo que pase.
 * 4. Solo entonces toma el cerrojo del respaldo, y solo para anotar: si el destino sigue siendo el
 *    de esa lectura y esa cabeza sigue ahí. Una lectura del destino anterior no dice nada del
 *    nuevo.
 *
 * - **Si pasa**, con WebDAV anota la lectura completa y quita la copia de las dañadas.
 * - **Si falla**, deja `VERIFY_MISMATCH` a la vista, marca la copia como dañada, con WebDAV en
 *   `damaged-generation` y con ranuras como no válida, y fuerza una subida. **Nunca la borra**: quizá
 *   falló la lectura, o un validador nuevo.
 * - **Sin espacio** se salta, y lo anota como saltada: no dice nada de la copia.
 * - **Una cabeza que la app no restauraría** no se baja: ya se sabe, y su fallo sigue a la vista.
 *
 * Un fallo pasajero del destino se reintenta. No hace nada sin un destino conectado, sin la
 * política aceptada o con una restauración a medias.
 *
 * **El mismo simulacro, a mano.** `Probar restauración` corre lo mismo con [runNow], con cualquier
 * red, porque lo pidió la persona, y devuelve cómo terminó. Además de validar, abre la candidata
 * con la base de la app, que corre las migraciones como las correría al restaurar: una copia que no
 * migra no se puede restaurar. Todo pasa sobre el temporal: la base activa ni se abre. Un simulacro
 * a la vez: el mensual y el pedido usan la misma carpeta.
 *
 * @param prepare abre la base y mira las precondiciones.
 * @param stagingDirectory la carpeta de preparación del respaldo. Pedirla toca el disco.
 * @param freeSpace el espacio libre de una carpeta.
 * @param mutex el cerrojo del respaldo, solo para anotar el resultado.
 * @param migrator abre la candidata con la base de la app y la migra, sobre el temporal. Nulo donde
 *   no se migra.
 */
internal class HeadDeepCheck(
    private val prepare: suspend () -> ProbeAccess,
    private val destinations: DestinationOpener,
    private val stagingDirectory: suspend () -> File,
    private val unpacker: CandidateUnpacker,
    private val validator: CandidateValidator,
    private val fullVerify: suspend () -> FullVerifyGenerationStore,
    private val damaged: suspend () -> DamagedGenerationStore,
    private val mutex: Mutex,
    private val clock: Clock,
    private val log: EventLog,
    private val ioContext: CoroutineContext,
    private val freeSpace: (File) -> Long = File::getUsableSpace,
    private val migrator: CandidateMigrator? = null,
) {
    /** Un simulacro a la vez en el proceso: los dos usan la misma carpeta. */
    private val running = Mutex()

    /** Corre una comprobación, la del trabajo mensual. */
    suspend fun run(control: TransferControl): ProbeRun {
        // Si la persona está probando ahora, el mensual no repite lo mismo.
        if (!running.tryLock()) return ProbeRun.DONE
        val drill =
            try {
                drill(control)
            } finally {
                running.unlock()
            }
        return when (drill) {
            Drill.Retry -> ProbeRun.RETRY
            Drill.Nothing, is Drill.Done -> ProbeRun.DONE
        }
    }

    /**
     * `Probar restauración`: el mismo simulacro, cuando lo pide la persona, con cualquier red.
     * Espera a que termine uno que ya corre, y anota el resultado igual que el mensual.
     */
    suspend fun runNow(control: TransferControl): DrillOutcome {
        val drill = running.withLock { drill(control) }
        return when (drill) {
            Drill.Retry -> DrillOutcome.UNREACHABLE

            Drill.Nothing -> DrillOutcome.NO_COPY

            is Drill.Done -> when (drill.result) {
                BackupDrillResult.PASSED -> DrillOutcome.PASSED
                BackupDrillResult.FAILED -> DrillOutcome.FAILED
                BackupDrillResult.SKIPPED -> DrillOutcome.SKIPPED
            }
        }
    }

    /**
     * `Comprobar ahora`, con WebDAV: baja la cabeza entera y compara su SHA-256 con el de sus
     * metadatos, sin descomprimir ni validar. La pide la persona, con cualquier red, porque gasta
     * datos. Como el simulacro, solo toma el cerrojo para anotar, y solo si el destino sigue siendo
     * el de la lectura y esa cabeza sigue ahí.
     *
     * - **Si coincide**, anota la lectura completa y quita la copia de las dañadas.
     * - **Si no**, deja `VERIFY_MISMATCH`, marca la copia como dañada y fuerza una subida. Nunca la
     *   borra.
     *
     * Es la única escritura de `Salud de mis datos`.
     */
    suspend fun verifyNow(control: TransferControl): FullVerifyOutcome = running.withLock {
        verify(control)
    }

    private suspend fun verify(control: TransferControl): FullVerifyOutcome {
        val ready = when (val access = prepare()) {
            is ProbeAccess.Ready -> access
            ProbeAccess.Waiting -> return FullVerifyOutcome.UNREACHABLE
            ProbeAccess.Nothing -> return FullVerifyOutcome.NO_COPY
        }
        val link = ready.link
        // Con ranuras la app lee por esa nube: no prueba que la copia haya subido.
        if (link.kind != BackupDestinationKind.WEBDAV) return FullVerifyOutcome.NO_COPY
        val current = ready.state.get() ?: return FullVerifyOutcome.NO_COPY
        val headRef = headRefOf(current) ?: return FullVerifyOutcome.NO_COPY
        if (current.headGeneration <= 0) return FullVerifyOutcome.NO_COPY
        val destination =
            when (val opened = destinations.open(link)) {
                is DestinationOpen.Opened -> opened.destination
                DestinationOpen.PermissionLost -> return FullVerifyOutcome.UNREACHABLE
                DestinationOpen.RetryLater -> return FullVerifyOutcome.UNREACHABLE
            }
        val head =
            when (val found = headMeta(destination, headRef, current.headGeneration, control)) {
                is Found.Meta -> found.meta
                Found.Retry -> return FullVerifyOutcome.UNREACHABLE
                Found.None -> return FullVerifyOutcome.NO_COPY
            }
        val staging = stagingDirectory()
        val workDirectory = File(staging, DIRECTORY_NAME)
        val outcome =
            try {
                read(destination, headRef, head, staging, workDirectory, control)
            } finally {
                withContext(NonCancellable + ioContext) { workDirectory.deleteRecursively() }
            }
        when (outcome) {
            FullVerifyOutcome.MATCHED, FullVerifyOutcome.MISMATCH -> Unit

            FullVerifyOutcome.NO_SPACE,
            FullVerifyOutcome.NO_COPY,
            FullVerifyOutcome.UNREACHABLE,
            -> return outcome
        }
        val noted =
            recordVerify(
                ready.state,
                destination,
                link.destinationId,
                headRef,
                head,
                outcome == FullVerifyOutcome.MATCHED,
                control,
            )
        return if (noted) outcome else FullVerifyOutcome.NO_COPY
    }

    /** Baja la cabeza y compara su suma. */
    private suspend fun read(
        destination: BackupDestination,
        ref: CopyRef,
        head: SlMeta,
        staging: File,
        workDirectory: File,
        control: TransferControl,
    ): FullVerifyOutcome {
        if (withContext(ioContext) { freeSpaceOf(staging) } < head.fileSize) {
            return FullVerifyOutcome.NO_SPACE
        }
        val download = File(workDirectory, DOWNLOAD_NAME)
        withContext(ioContext) { workDirectory.mkdirs() }
        val downloaded =
            try {
                withContext(ioContext) {
                    download.outputStream().use { sink ->
                        destination.download(
                            ref,
                            sink,
                            BackupLimits.MAX_BACKUP_DOWNLOAD_BYTES,
                            control,
                        )
                    }
                }
            } catch (_: IOException) {
                return FullVerifyOutcome.UNREACHABLE
            }
        when (downloaded) {
            is DestinationResult.Success -> Unit

            is DestinationResult.Failure -> return when (downloaded.failure) {
                DestinationFailure.Incomplete,
                DestinationFailure.LocalStorage,
                is DestinationFailure.Transient,
                is DestinationFailure.Interrupted,
                is DestinationFailure.PermissionLost,
                -> FullVerifyOutcome.UNREACHABLE

                DestinationFailure.VerifyMismatch,
                is DestinationFailure.Unsupported,
                is DestinationFailure.ChangedOutside,
                DestinationFailure.QuotaExceeded,
                is DestinationFailure.NoSafeSlot,
                -> FullVerifyOutcome.MISMATCH
            }
        }
        val sum = withContext(ioContext) { sha256Of(download) }
        return if (sum == head.sha256) FullVerifyOutcome.MATCHED else FullVerifyOutcome.MISMATCH
    }

    /**
     * Anota la comprobación bajo el cerrojo, si el destino sigue siendo el de la lectura y la
     * cabeza leída sigue ahí. Devuelve `false` si no anotó nada.
     */
    private suspend fun recordVerify(
        state: BackupStateDao,
        destination: BackupDestination,
        destinationId: String,
        ref: CopyRef,
        head: SlMeta,
        matched: Boolean,
        control: TransferControl,
    ): Boolean = mutex.withLock {
        val stillThere =
            state.get()?.destinationId == destinationId &&
                headMeta(destination, ref, head.generation, control).let {
                    it is Found.Meta && it.meta.sha256 == head.sha256
                }
        if (!stillThere) {
            log.info(ProbeEvent.DEEP_CHECK_STALE)
            return@withLock false
        }
        val now = clock.nowEpochMillis()
        if (matched) {
            if (state.markFullVerify(now, destinationId) == 0) return@withLock false
            fullVerify().advanceTo(head.generation)
            damaged().remove(head.generation)
            log.info(ProbeEvent.DEEP_CHECK_PASSED)
        } else {
            damaged().add(head.generation)
            state.recordFailure(BackupFailureCode.VERIFY_MISMATCH.name)
            // La cabeza dañada no puede seguir contando como la copia válida más reciente.
            state.forceUpload(now)
            log.warn(ProbeEvent.DEEP_CHECK_FAILED)
        }
        true
    }

    private suspend fun drill(control: TransferControl): Drill {
        val ready = when (val access = prepare()) {
            is ProbeAccess.Ready -> access
            ProbeAccess.Waiting -> return Drill.Retry
            ProbeAccess.Nothing -> return Drill.Nothing
        }
        val state = ready.state
        val link = ready.link
        val current = state.get() ?: return Drill.Nothing
        val headRef = headRefOf(current) ?: return Drill.Nothing
        if (current.headGeneration <= 0) return Drill.Nothing
        val destination =
            when (val opened = destinations.open(link)) {
                is DestinationOpen.Opened -> opened.destination

                // El acceso perdido lo anota la sonda o el respaldo: esta solo comprueba la copia.
                DestinationOpen.PermissionLost -> return Drill.Nothing

                DestinationOpen.RetryLater -> return Drill.Retry
            }
        val head =
            when (val found = headMeta(destination, headRef, current.headGeneration, control)) {
                is Found.Meta -> found.meta

                Found.Retry -> return Drill.Retry

                // Una cabeza que falta o cuyo tamaño cambió la resuelve la sonda.
                Found.None -> return Drill.Nothing
            }
        // Ya se sabe que la app no la restaura: no se baja, y su fallo sigue a la vista.
        if (head.fatal) return Drill.Nothing
        val staging = stagingDirectory()
        val workDirectory = File(staging, DIRECTORY_NAME)
        val outcome =
            try {
                check(destination, headRef, head, staging, workDirectory, control)
            } finally {
                withContext(NonCancellable + ioContext) { workDirectory.deleteRecursively() }
            }
        val result = when (outcome) {
            Outcome.Retry -> return Drill.Retry
            Outcome.Passed -> BackupDrillResult.PASSED
            Outcome.Failed -> BackupDrillResult.FAILED
            Outcome.NoSpace -> BackupDrillResult.SKIPPED
        }
        record(state, destination, link.destinationId, headRef, head, result, control)
        return Drill.Done(result)
    }

    private suspend fun check(
        destination: BackupDestination,
        ref: CopyRef,
        head: SlMeta,
        staging: File,
        workDirectory: File,
        control: TransferControl,
    ): Outcome {
        val needed = head.fileSize + head.rawSize
        if (withContext(ioContext) { freeSpaceOf(staging) } < needed) return Outcome.NoSpace
        val download = File(workDirectory, DOWNLOAD_NAME)
        val candidate = File(workDirectory, CANDIDATE_NAME)
        withContext(ioContext) { workDirectory.mkdirs() }
        val downloaded =
            try {
                withContext(ioContext) {
                    download.outputStream().use { sink ->
                        destination.download(
                            ref,
                            sink,
                            BackupLimits.MAX_BACKUP_DOWNLOAD_BYTES,
                            control,
                        )
                    }
                }
            } catch (_: IOException) {
                return Outcome.Retry
            }
        when (downloaded) {
            is DestinationResult.Success -> Unit

            is DestinationResult.Failure -> return when (downloaded.failure) {
                DestinationFailure.Incomplete,
                DestinationFailure.LocalStorage,
                is DestinationFailure.Transient,
                is DestinationFailure.Interrupted,
                is DestinationFailure.PermissionLost,
                -> Outcome.Retry

                // No se pudo abrir lo que debía estar entero: la copia no se comprueba.
                DestinationFailure.VerifyMismatch,
                is DestinationFailure.Unsupported,
                is DestinationFailure.ChangedOutside,
                DestinationFailure.QuotaExceeded,
                is DestinationFailure.NoSafeSlot,
                -> Outcome.Failed
            }
        }
        val unpacked = unpacker.unpack(download, candidate, head.sha256)
        if (unpacked !is UnpackResult.Unpacked) return Outcome.Failed
        val expected = ExpectedCopy(head.deviceId, head.generation, head.schemaVersion.toInt())
        val validated =
            when (val validation = validator.validate(candidate, CandidateContext(expected))) {
                // Con saldos por recalcular se restaura igual, con aviso: solo lo fatal falla.
                is CandidateValidation.Checked -> when (validation.verdict) {
                    is CandidateVerdict.Rejected -> Outcome.Failed

                    is CandidateVerdict.NeedsRepair, is CandidateVerdict.Restorable ->
                        Outcome.Passed
                }

                is CandidateValidation.Refused -> Outcome.Failed

                // Una falla del teléfono no dice nada de la copia.
                is CandidateValidation.Failed -> Outcome.Retry
            }
        if (validated != Outcome.Passed) return validated
        return migrated(candidate)
    }

    /**
     * Abre la candidata con la base de la app, que corre sus migraciones como al restaurar. Si no
     * abre, la copia no se podría restaurar. Una falla de Keystore o del disco no dice nada de ella,
     * pero tampoco se puede distinguir aquí: se toma como fallo, como en la restauración.
     */
    private suspend fun migrated(candidate: File): Outcome {
        val migrate = migrator ?: return Outcome.Passed
        return try {
            migrate.migrate(candidate)
            Outcome.Passed
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            log.warn(ProbeEvent.DEEP_CHECK_FAILED, failure)
            Outcome.Failed
        }
    }

    /**
     * Anota el resultado bajo el cerrojo, si el destino sigue siendo el de la lectura y la cabeza
     * leída sigue ahí con su generación. Si no, lo leído ya no dice nada y no se anota.
     */
    private suspend fun record(
        state: BackupStateDao,
        destination: BackupDestination,
        destinationId: String,
        ref: CopyRef,
        head: SlMeta,
        result: BackupDrillResult,
        control: TransferControl,
    ) = mutex.withLock {
        val current = state.get()
        val stillThere =
            current?.destinationId == destinationId &&
                headMeta(destination, ref, head.generation, control).let {
                    it is Found.Meta && it.meta.sha256 == head.sha256
                }
        if (!stillThere) {
            log.info(ProbeEvent.DEEP_CHECK_STALE)
            return@withLock
        }
        val now = clock.nowEpochMillis()
        if (state.markDrill(now, result.name, destinationId) == 0) return@withLock
        val webDav = destination.kind == BackupDestinationKind.WEBDAV
        when (result) {
            BackupDrillResult.PASSED -> {
                if (webDav) {
                    state.markFullVerify(now, destinationId)
                    fullVerify().advanceTo(head.generation)
                    damaged().remove(head.generation)
                }
                log.info(ProbeEvent.DEEP_CHECK_PASSED)
            }

            BackupDrillResult.FAILED -> {
                if (webDav) damaged().add(head.generation) else destination.markUnreadable(ref)
                state.recordFailure(BackupFailureCode.VERIFY_MISMATCH.name)
                // La cabeza dañada no puede seguir contando como la copia válida más reciente.
                state.forceUpload(now)
                log.warn(ProbeEvent.DEEP_CHECK_FAILED)
            }

            BackupDrillResult.SKIPPED -> log.info(ProbeEvent.DEEP_CHECK_SKIPPED)
        }
    }

    /** Los metadatos válidos de la cabeza [ref], si la lista la muestra con [generation] y su tamaño. */
    private suspend fun headMeta(
        destination: BackupDestination,
        ref: CopyRef,
        generation: Long,
        control: TransferControl,
    ): Found {
        val listing =
            when (val listed = destination.listCopies(control)) {
                is DestinationResult.Success -> listed.value
                is DestinationResult.Failure -> return Found.Retry
            }
        val copy = listing.copies.firstOrNull { it.ref == ref } ?: return Found.None
        val bytes = (copy.meta as? ListedMeta.Present)?.bytes ?: return Found.None
        val decoded =
            copy.name?.let { SlMetaCodec.decode(bytes, it.identity()) }
                ?: SlMetaCodec.decodeUnnamed(bytes)
        val meta = (decoded as? CopyMetadata.Valid)?.meta ?: return Found.None
        if (meta.generation != generation || meta.fileSize != copy.sizeBytes) return Found.None
        return Found.Meta(meta)
    }

    private fun freeSpaceOf(directory: File): Long {
        var existing: File? = directory
        while (existing != null && !existing.exists()) existing = existing.parentFile
        return existing?.let(freeSpace) ?: 0
    }

    private sealed interface Found {
        class Meta(val meta: SlMeta) : Found

        data object None : Found

        data object Retry : Found
    }

    private enum class Outcome { Passed, Failed, NoSpace, Retry }

    /** Cómo terminó un simulacro, antes de decirlo a quien lo pidió. */
    private sealed interface Drill {
        /** No hay cabeza que probar, o no hay destino. */
        data object Nothing : Drill

        /** El destino no respondió, o falta terminar algo antes: se repite después. */
        data object Retry : Drill

        data class Done(val result: BackupDrillResult) : Drill
    }

    companion object {
        /** La carpeta de la comprobación dentro de `backup-staging/`. Se borra al terminar. */
        const val DIRECTORY_NAME = "deep-check"

        private const val DOWNLOAD_NAME = "head.download"
        private const val CANDIDATE_NAME = "head.db"
    }
}

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
import io.github.yesidrobayo.shopledger.data.destination.StoredCopy
import io.github.yesidrobayo.shopledger.data.destination.TransferControl
import io.github.yesidrobayo.shopledger.data.destination.UploadPlan
import io.github.yesidrobayo.shopledger.data.destination.VerifiedCopy
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
import io.github.yesidrobayo.shopledger.domain.model.backup.UploadGateVerdict
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
 * 0. **Precondiciones.** Sin destino conectado, sin la versión vigente de la política aceptada, sin
 *    cambios pendientes o con la base vacía, termina sin hacer nada, también con `Respaldar ahora`:
 *    sin aceptar la política no sale ninguna copia a la nube. Una copia automática pasa además por
 *    la compuerta de red ([UploadGate]): con datos móviles y el intervalo corriendo, o con `Solo con
 *    Wi-Fi`, termina sin hacer nada y sin contar un intento. Limpia los restos de la carpeta de
 *    preparación, salvo el archivo de una subida pendiente.
 * 1. **Espacio y tamaño.** El teléfono debe tener libre una vez y media la base, y la base no debe
 *    pasar de lo que una restauración acepta: si no, `LOCAL_STORAGE_LOW` o `BACKUP_TOO_LARGE`.
 * 2. y 3. **Destino y credencial.** Abre el destino que recuerda el estado. Si no deja entrar, el
 *    enlace pasa a acceso perdido.
 * 4. **Listar.** Lo que hay en el destino. Una lista con errores nunca es una carpeta vacía: se
 *    reintenta. Borra los temporales de este teléfono que dejó una subida cortada. Con el servicio
 *    lleno, una copia automática que no cabría termina sin generar nada ([QuotaGate]).
 * 5. **Retomar.** Una subida que quedó sin confirmar se busca por su nombre, o con ranuras por su
 *    marca: si ya está entera, salta a comprobarla o a confirmarla, y si no llegó, sube otra vez el
 *    mismo archivo, con ranuras en la misma ranura. Lo que nunca se confirmó y no sirve se rechaza
 *    en disco antes de borrarlo, y nunca se adopta ([UploadRecovery]).
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
 * 9. **Comprimir.** gzip con su suma, comprobado al releerlo, y los metadatos en la carpeta. Con el
 *    cifrado activo, la copia sale cifrada y se comprueba descifrándola; si la clave no abre, no
 *    sale nada, nunca en claro: `ENCRYPTION_KEY_UNAVAILABLE`.
 * 10. **Intención y subida.** Guarda en el estado qué copia va en camino, y solo después escribe en
 *     el destino. Al enviar el último byte anota la hora, y también si la subida se corta en red
 *     medida: esos datos ya se gastaron, y el intervalo cuenta desde ahí.
 * 11. **Comprobar.** Lo que el destino tiene debe ser lo que se subió: siempre el tamaño, y el
 *     contenido entero o una muestra según la red y el plazo ([verifyModeFor]). Si no coincide,
 *     `VERIFY_MISMATCH`: la generación se rechaza en disco antes de borrar la copia, y nunca se
 *     confirma. Después lista otra vez: una copia de otro teléfono igual o más nueva es un
 *     conflicto, y una lista que no muestra la copia nueva no deja podar.
 * 12. **Confirmar.** En una sola sentencia, y solo si el destino sigue siendo el de esta ejecución.
 *     Una copia fatal pasa a ser la cabeza, pero los cambios siguen sin respaldar.
 *     Una lectura completa que coincidió queda anotada para el plazo y para la poda.
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
 * @param verifyMode cuánto del contenido se lee para comprobar, según la red y el plazo, o nulo si
 *   hay que esperar una red no medida.
 * @param meteredAllowed si la política de la persona permite respaldar con datos móviles.
 * @param policyAccepted si la persona aceptó la versión vigente de la política y los términos.
 * @param uploadGate la compuerta de red de las copias automáticas.
 * @param transportFailures cuenta los fallos de transporte seguidos de cada ejecución que se
 *   reintenta, para dejar a la vista uno que no se resuelve solo.
 * @param snapshotValidation revisa la instantánea con el validador de las restauraciones.
 * @param appVersion la versión de la app para los metadatos, si tiene la forma que aceptan.
 * @param deviceLabel fabricante y modelo del teléfono para los metadatos, ya limpios.
 * @param mutex el cerrojo del respaldo, uno por proceso.
 * @param rejected las generaciones propias rechazadas, que nunca se adoptan ni se repiten.
 * @param fullVerify la generación de la última copia leída entera, que la poda conserva.
 * @param encryption el cifrado de las copias en este teléfono, que la guarda mira para no subir en
 *   claro encima de copias cifradas sin el código.
 * @param afterCopy el paso 9b: con `Mantener al día esta carpeta`, deja el archivo de cada copia
 *   nueva que no es fatal también en el segundo destino, antes de subir y bajo el mismo cerrojo.
 *   Nunca lanza ni detiene la subida: un fallo ahí se anota aparte.
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
    rejected: RejectedGenerationStore,
    private val fullVerify: FullVerifyGenerationStore,
    private val guard: CopyGuard = BackupGuard,
    private val pruner: CopyPruner = CopyPruner.None,
    private val freeSpace: (File) -> Long = File::getUsableSpace,
    private val verifyMode: (VerifyRequest) -> VerifyMode? = ::verifyModeFor,
    private val meteredAllowed: suspend () -> Boolean,
    private val policyAccepted: suspend () -> Boolean,
    private val uploadGate: UploadGate = UploadGate.Open,
    private val transportFailures: TransportFailures = TransportFailures(state),
    private val snapshotValidation: SnapshotValidation =
        SnapshotValidation(CandidateValidator(ioContext, schemaVersion)),
    private val afterCopy: suspend (Delivery.Upload) -> Unit = {},
    private val encryption: suspend () -> LocalEncryption = { LocalEncryption.NONE },
    private val offHeadKeyId: suspend (String) -> Unit = {},
) {
    private val recovery =
        UploadRecovery(
            state,
            staging,
            rejected,
            clock,
            log,
            ioContext,
            schemaVersion,
            encryptionActive = { encryption().active },
        )

    /** Corre una ejecución entera. Solo lanza la cancelación, o una falla que nadie previó. */
    suspend fun run(requested: TransferControl): BackupRun = mutex.withLock {
        val current =
            state.get() ?: return@withLock stop(StopCause.Failure(BackupFailureCode.UNEXPECTED))
        val attempt = Attempt(current.pendingGeneration)
        val control = RunControl(requested)
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
        control: RunControl,
        attempt: Attempt,
    ): BackupRun {
        // Paso 0: precondiciones.
        val link = connectedOf(current) ?: return BackupRun.Skipped(SkipReason.NOT_CONNECTED)
        if (!policyAccepted()) return BackupRun.Skipped(SkipReason.POLICY_PENDING)
        if (current.changeSeq == current.verifiedSeq && current.pendingGeneration == null) {
            return BackupRun.Skipped(SkipReason.NOTHING_PENDING)
        }
        if (state.isEmpty()) return BackupRun.Skipped(SkipReason.EMPTY_DATABASE)
        gate(link.kind, current, control)?.let { return it }
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
        val device = deviceId()
        recovery.removeOwnTemporaries(destination, listing, device, control)
        interruption(control)?.let { return it }
        if (quotaHolds(current, listing, control)) {
            // No intentó subir: el intento anterior sigue siendo el que cuenta para el plazo.
            state.restoreAttempt(current.lastAttemptAt)
            return stop(StopCause.Failure(BackupFailureCode.QUOTA_EXCEEDED))
        }

        // Paso 5: retomar la subida que quedó sin confirmar, o descartarla.
        val delivery =
            when (
                val recovered = recovery.recover(
                    destination,
                    listing,
                    current,
                    device,
                    control,
                )
            ) {
                is Recovery.Resume -> recovered.delivery

                is Recovery.Failed -> return failed(recovered.failure, link)

                Recovery.LocalFailure -> return BackupRun.Retry(RetryReason.LOCAL_FAILURE)

                is Recovery.StartOver -> {
                    attempt.pendingGeneration = recovered.pendingGeneration
                    when (val made = newCopy(listing, current, device, recovered, link, control)) {
                        is Made.Copy -> {
                            // Paso 9b: la carpeta local, si sigue a la copia principal.
                            if (!made.copy.marks.fatal) afterCopy(made.copy)
                            made.copy
                        }

                        is Made.Stop -> return made.run
                    }
                }
            }
        interruption(control)?.let { return it }

        // Paso 10: intención y subida.
        val stored =
            when (delivery) {
                is Delivery.Verify -> delivery.stored

                is Delivery.Reread -> delivery.stored

                is Delivery.Recorded -> delivery.stored

                is Delivery.Upload -> {
                    val outgoing = delivery.copy
                    // Una ranura a medias con este mismo archivo se reescribe ahí: nunca en otra.
                    val plan =
                        delivery.fixedRef?.let { UploadPlan(outgoing, it) }
                            ?: when (
                                val planned = destination.planUpload(outgoing, headRefOf(current))
                            ) {
                                is DestinationResult.Success -> planned.value
                                is DestinationResult.Failure -> return failed(planned.failure, link)
                            }
                    interruption(control)?.let { return it }
                    state.saveIntent(
                        delivery.generation,
                        delivery.changeSeq,
                        outgoing.sha256,
                        outgoing.sizeBytes,
                    )
                    attempt.pendingGeneration = delivery.generation
                    val uploaded =
                        when (val result = destination.upload(plan, control)) {
                            is DestinationResult.Success -> result.value

                            is DestinationResult.Failure -> {
                                if (spentMeteredData(destination, result.failure, control)) {
                                    state.markUploadFinished(clock.nowEpochMillis())
                                }
                                return failed(result.failure, link)
                            }
                        }
                    state.markUploadFinished(clock.nowEpochMillis())
                    uploaded
                }
            }

        // Paso 11: comprobar.
        val webDav = destination.kind == BackupDestinationKind.WEBDAV
        val mode =
            verifyMode(verifyRequest(delivery, current, control))
                // Una copia retomada no se comprueba por muestra, y la política no deja leerla
                // entera con datos móviles: queda para una red no medida, con su archivo.
                ?: return BackupRun.Retry(
                    RetryReason.INTERRUPTED,
                    interruptReason = InterruptReason.METERED,
                )
        // Una ranura que la recuperación ya releyó, o ya anotó, no se lee otra vez.
        val reread = delivery is Delivery.Reread || delivery is Delivery.Recorded
        val fullyRead =
            when (
                val verified =
                    if (reread) {
                        DestinationResult.Success(VerifiedCopy(fullyRead = true))
                    } else {
                        destination.verify(stored, mode, delivery.localFile, control)
                    }
            ) {
                is DestinationResult.Success -> verified.value.fullyRead

                is DestinationResult.Failure -> {
                    if (verified.failure == DestinationFailure.VerifyMismatch) {
                        // Esa copia nunca se confirma, ni después por otra vía: con WebDAV se
                        // rechaza en disco antes de borrarla. Si el disco no deja anotarla, todo
                        // queda como estaba y se reintenta, lo más probable por falta de espacio.
                        if (!webDav) {
                            state.clearIntent()
                        } else if (
                            !recovery.rejectMismatch(
                                destination,
                                delivery.generation,
                                stored.ref,
                                control,
                            )
                        ) {
                            state.recordFailure(BackupFailureCode.LOCAL_STORAGE_LOW.name)
                            return BackupRun.Retry(RetryReason.LOCAL_STATE_NOT_SAVED)
                        }
                        attempt.pendingGeneration = null
                    }
                    return failed(verified.failure, link)
                }
            }
        val relisted = if (webDav) relist(destination, stored, delivery, device, control) else null

        // Paso 12: confirmar. Con ranuras, primero en el estado de las ranuras, salvo que ya estuviera.
        if (delivery !is Delivery.Recorded) {
            when (val confirmed = destination.confirm(stored)) {
                is DestinationResult.Success -> Unit
                is DestinationResult.Failure -> return failed(confirmed.failure, link)
            }
        }
        val rows =
            if (delivery.marks.fatal) {
                // La app no la restaura: es la cabeza, pero los cambios siguen sin respaldar. Una
                // copia retomada trae la marca de sus metadatos.
                state.confirmUnrestorable(
                    generation = delivery.generation,
                    deviceId = device,
                    fileRef = stored.ref.value,
                    failureCode = BackupFailureCode.LOCAL_INVARIANT_FATAL.name,
                    destinationId = link.destinationId,
                )
            } else {
                state.confirm(
                    generation = delivery.generation,
                    deviceId = device,
                    fileRef = stored.ref.value,
                    seq = delivery.changeSeq,
                    now = clock.nowEpochMillis(),
                    copiedAt = delivery.createdAtMillis,
                    failureCode =
                        BackupFailureCode.LOCAL_INVARIANT.name.takeUnless { delivery.marks.valid },
                    destinationId = link.destinationId,
                )
            }
        if (rows == 0) return BackupRun.Skipped(SkipReason.DESTINATION_CHANGED)
        attempt.pendingGeneration = null
        if (webDav && fullyRead) markFullVerify(delivery.generation, link)
        relisted?.conflict?.let { foreign ->
            // La copia nueva está comprobada y queda como cabeza, pero otro teléfono subió a la
            // vez: las copias se detienen hasta que la persona decida, y nada se poda.
            state.markConflict(
                BackupConflictKind.OTHER_DEVICE.name,
                foreign.generation,
                foreign.deviceId,
                null,
                link.destinationId,
            )
            return BackupRun.Stopped(StopCause.Conflict(BackupConflictKind.OTHER_DEVICE))
        }

        // Paso 13: podar, solo ahora. No si la copia no se leyó, porque su contenido sigue sin
        // comprobar, ni si la segunda lista no la mostró, porque quizá viene de una caché.
        if (webDav && mode != VerifyMode.SIZE_AND_META && relisted?.showsTheCopy == true) {
            prune(destination, ConfirmedCopy(delivery.generation, stored.ref), control)
        }
        return BackupRun.Verified(delivery.generation, unrestorable = delivery.marks.fatal)
    }

    /**
     * La compuerta de red del paso 0, para una copia automática. `Respaldar ahora` y la copia que
     * ya lleva un día no la miran. Si deja pasar con una red sin datos móviles y con datos móviles
     * habría hecho esperar, la subida se corta si la red pasa a medida.
     */
    private suspend fun gate(
        kind: BackupDestinationKind,
        current: BackupStateEntity,
        control: RunControl,
    ): BackupRun? {
        if (control.isBackupNow() || control.isPastInterval()) return null
        val metered = control.isMetered()
        return when (uploadGate.check(kind, current.lastUploadFinishedAt, metered)) {
            UploadGateVerdict.PROCEED -> {
                control.cutOnMetered =
                    kind == BackupDestinationKind.WEBDAV &&
                    !metered &&
                    uploadGate.check(kind, current.lastUploadFinishedAt, metered = true) !=
                    UploadGateVerdict.PROCEED
                null
            }

            UploadGateVerdict.WAIT_FOR_INTERVAL ->
                BackupRun.Skipped(SkipReason.WAITING_FOR_INTERVAL)

            UploadGateVerdict.WAIT_FOR_UNMETERED ->
                BackupRun.Skipped(SkipReason.WAITING_FOR_UNMETERED)
        }
    }

    /** Si el servicio sigue lleno para esta copia automática. */
    private fun quotaHolds(
        current: BackupStateEntity,
        listing: CopyListing,
        control: TransferControl,
    ): Boolean = QuotaGate.holds(
        failureCode = current.failureCode,
        backupNow = control.isBackupNow(),
        quotaAvailableBytes = listing.quotaAvailableBytes,
        expectedBytes =
            current.pendingSizeBytes
                ?: listing.copies.firstOrNull { it.ref.value == current.headFileRef }?.sizeBytes,
        lastAttemptAt = current.lastAttemptAt,
        nowMillis = clock.nowEpochMillis(),
    )

    /**
     * Si una subida a WebDAV que no terminó pudo gastar datos móviles: se cortó porque la red pasó
     * a medida, o falló o se detuvo con la red medida. Esos datos ya se gastaron, y sin anotarlo
     * cada reintento enviaría el archivo entero otra vez sin contar el intervalo.
     */
    private fun spentMeteredData(
        destination: BackupDestination,
        failure: DestinationFailure,
        control: TransferControl,
    ): Boolean {
        if (destination.kind != BackupDestinationKind.WEBDAV) return false
        return when (failure) {
            is DestinationFailure.Interrupted ->
                failure.reason == InterruptReason.METERED || control.isMetered()

            is DestinationFailure.Transient -> control.isMetered()

            else -> false
        }
    }

    /** Lo que mostró la segunda lista del paso 11. */
    private class Relisted(val conflict: KnownCopy?, val showsTheCopy: Boolean)

    /**
     * Lista otra vez, tras comprobar. Una copia de otro teléfono con generación igual o mayor que la
     * nueva es un conflicto: dos teléfonos subieron a la vez. Si la lista falla, o no trae la copia
     * nueva, la copia sigue comprobada y esa ejecución no poda: nunca se decide con una lista que
     * quizá viene de una caché.
     */
    private suspend fun relist(
        destination: BackupDestination,
        stored: StoredCopy,
        delivery: Delivery,
        device: String,
        control: TransferControl,
    ): Relisted {
        val listing =
            when (val listed = destination.listCopies(control)) {
                is DestinationResult.Success -> listed.value

                is DestinationResult.Failure -> {
                    log.warn(
                        VerifyEvent.RELIST_FAILED,
                        LogField.state("fallo", failureName(listed.failure)),
                    )
                    return Relisted(conflict = null, showsTheCopy = false)
                }
            }
        val conflict =
            knownCopies(listing).firstOrNull {
                it.deviceId != device && it.generation >= delivery.generation
            }
        val shows = listing.copies.any { it.ref == stored.ref && it.sizeBytes == stored.sizeBytes }
        if (!shows) log.warn(VerifyEvent.COPY_NOT_RELISTED)
        return Relisted(conflict, shows)
    }

    /**
     * Anota la lectura completa que coincidió: `last_full_verify_at`, para el plazo, y la
     * generación, para que la poda la conserve. Si el archivo no se escribe, la poda conserva la
     * anterior: nada se pierde.
     */
    private suspend fun markFullVerify(generation: Long, link: ConnectedDestination) {
        state.markFullVerify(clock.nowEpochMillis(), link.destinationId)
        try {
            fullVerify.advanceTo(generation)
        } catch (failure: IOException) {
            log.warn(VerifyEvent.FULL_VERIFY_NOT_SAVED, failure)
        }
    }

    /**
     * Pasos 6 a 9: la guarda y una copia nueva. [recovered] dice qué quedó rechazado y si la
     * intención sigue en el estado.
     */
    private suspend fun newCopy(
        listing: CopyListing,
        current: BackupStateEntity,
        device: String,
        recovered: Recovery.StartOver,
        link: ConnectedDestination,
        control: TransferControl,
    ): Made {
        // Paso 6: guarda.
        val input =
            guardInput(
                listing,
                current.copy(pendingGeneration = recovered.pendingGeneration),
                device,
                recovered.rejected,
            )
        noteOffHeadKey(input)
        val generation =
            when (val decision = guard.decide(input)) {
                is GuardDecision.Upload -> decision.generation

                GuardDecision.Empty ->
                    return Made.Stop(BackupRun.Skipped(SkipReason.EMPTY_DATABASE))

                is GuardDecision.Refused -> return Made.Stop(stop(StopCause.Failure(decision.code)))

                is GuardDecision.Conflict ->
                    return Made.Stop(
                        conflict(
                            decision.kind,
                            link,
                            ConflictMark(
                                decision.head.generation,
                                decision.head.deviceId,
                                decision.head.ref,
                            ),
                        ),
                    )
            }

        // Pasos 7 a 9: la copia.
        return makeCopy(generation, device, link.kind, control)
    }

    private suspend fun verifyRequest(
        delivery: Delivery,
        current: BackupStateEntity,
        control: TransferControl,
    ) = VerifyRequest(
        metered = control.isMetered(),
        backupNow = control.isBackupNow(),
        resumed = delivery is Delivery.Verify,
        // Solo se lee la política cuando decide algo: una copia retomada en red medida.
        meteredAllowed =
            delivery is Delivery.Verify && control.isMetered() && !control.isBackupNow() &&
                meteredAllowed(),
        lastFullVerifyAtMillis = current.lastFullVerifyAt,
        nowMillis = clock.nowEpochMillis(),
    )

    private sealed interface Made {
        /** Una copia lista para subir, con lo que sale de su instantánea. */
        class Copy(val copy: Delivery.Upload) : Made

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
                val result =
                    packager.compress(
                        snapshot.file,
                        { encrypted -> staging.packedFile(generation, encrypted) },
                        CopyFacts(generation, schemaVersion, now.toEpochMilliseconds(), device),
                    )
            ) {
                is PackageResult.Packed -> result

                PackageResult.TooLarge -> return stopWith(BackupFailureCode.BACKUP_TOO_LARGE)

                PackageResult.StorageFull -> return stopWith(BackupFailureCode.LOCAL_STORAGE_LOW)

                PackageResult.Mismatch -> return stopWith(BackupFailureCode.UNEXPECTED)

                // Sin la clave no sale nada: nunca una copia en claro con el cifrado activo.
                PackageResult.EncryptionUnavailable ->
                    return stopWith(BackupFailureCode.ENCRYPTION_KEY_UNAVAILABLE)

                // Keystore falló un momento: se reintenta sin cambiar el estado.
                PackageResult.KeyRetry ->
                    return Made.Stop(BackupRun.Retry(RetryReason.LOCAL_FAILURE))

                is PackageResult.Failed -> return localFailure(result.cause)
            }
        val meta =
            SlMeta(
                format = if (packed.encrypted) ENCRYPTED_FORMAT else PLAIN_FORMAT,
                keyId = packed.keyIdHex,
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
                format =
                    if (packed.encrypted) BackupFileFormat.ENCRYPTED else BackupFileFormat.GZIP,
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
            Delivery.Upload(outgoing, generation, sealed.changeSeq, now.toEpochMilliseconds()),
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

    /**
     * Con el cifrado apagado sin código en este teléfono, apagar no pudo saber qué clave tenía la
     * cabeza, porque no espera a la red. La primera lista lo completa: así una restauración de esa
     * copia deja el cifrado apagado.
     */
    private suspend fun noteOffHeadKey(input: GuardInput) {
        if (!input.encryption.offConfirmed || input.encryption.hasRecoveryCode) return
        val keyId = BackupGuard.headOf(input).firstNotNullOfOrNull(KnownCopy::keyId) ?: return
        offHeadKeyId(keyId)
    }

    /** Lo que mira la guarda, con las generaciones propias rechazadas, que nunca se confirmaron. */
    private suspend fun guardInput(
        listing: CopyListing,
        current: BackupStateEntity,
        device: String,
        rejected: Set<Long>,
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
        rejectedGenerations = rejected,
        encryption = encryption(),
    )

    private fun headRefOf(current: BackupStateEntity): CopyRef? =
        current.headFileRef?.takeIf { it.isNotEmpty() && it.length <= CopyRef.MAX_LENGTH }
            ?.let(::CopyRef)

    private companion object {
        /** Las copias van sin cifrar hasta que exista el cifrado opcional. */
        const val PLAIN_FORMAT = 1

        /** El formato de una copia cifrada, el `.slbk`. */
        const val ENCRYPTED_FORMAT = 2

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
            keyId = meta.keyId,
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

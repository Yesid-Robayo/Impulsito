package io.github.yesidrobayo.shopledger.data.destination

import io.github.yesidrobayo.shopledger.data.DataAccess
import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.data.backup.ConflictLook
import io.github.yesidrobayo.shopledger.data.backup.ConflictResolver
import io.github.yesidrobayo.shopledger.data.backup.ConflictView
import io.github.yesidrobayo.shopledger.data.backup.KeepOutcome
import io.github.yesidrobayo.shopledger.data.backup.SlotFileFacts
import io.github.yesidrobayo.shopledger.data.destination.slots.DOCUMENT_SLOTS_DESTINATION_ID
import io.github.yesidrobayo.shopledger.data.destination.slots.DocumentAccess
import io.github.yesidrobayo.shopledger.data.destination.slots.DocumentResult
import io.github.yesidrobayo.shopledger.data.destination.slots.SlotFileDocuments
import io.github.yesidrobayo.shopledger.data.destination.slots.SlotPendingStore
import io.github.yesidrobayo.shopledger.data.destination.slots.SlotState
import io.github.yesidrobayo.shopledger.data.destination.slots.SlotStateLoad
import io.github.yesidrobayo.shopledger.data.destination.slots.SlotStateStore
import io.github.yesidrobayo.shopledger.data.local.audit.AuditEventEntity
import io.github.yesidrobayo.shopledger.data.local.backup.BackupStateDao
import io.github.yesidrobayo.shopledger.data.local.backup.BackupStateEntity
import io.github.yesidrobayo.shopledger.data.log.DataEvent
import io.github.yesidrobayo.shopledger.domain.model.audit.AuditEventType
import io.github.yesidrobayo.shopledger.domain.model.audit.AuditResult
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupLinkState
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupSlot
import io.github.yesidrobayo.shopledger.domain.model.backup.ConflictLookup
import io.github.yesidrobayo.shopledger.domain.model.backup.ConflictSides
import io.github.yesidrobayo.shopledger.domain.model.backup.DisconnectResult
import io.github.yesidrobayo.shopledger.domain.model.backup.KeepPhoneResult
import io.github.yesidrobayo.shopledger.domain.port.BackupLinkPort
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.info
import io.github.yesidrobayo.shopledger.domain.port.warn
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Los pasos de desconectar, en orden. Las pruebas cortan tras cada uno. */
internal enum class DisconnectStep {
    /** La base dice `NOT_CONNECTED`: desde aquí no hay vuelta atrás. */
    STATE_SAVED,

    /** `link.json` ya no está. */
    LINK_REMOVED,

    /** La credencial, o las ranuras con sus permisos, ya no están. */
    ACCESS_REMOVED,
}

/** Los pasos de cambiar de destino, en orden. Las pruebas cortan tras cada uno. */
internal enum class ChangeStep {
    /** `webdav.sealed.new`, o `slots.json` con las ranuras nuevas, ya están en el disco. */
    NEXT_WRITTEN,

    /** La base tiene el destino nuevo: desde aquí no hay vuelta atrás. */
    STATE_CHANGED,

    /** La credencial nueva quedó en `webdav.sealed`, o no hacía falta. */
    CREDENTIAL_PROMOTED,

    /** Se soltó el destino anterior: su credencial, o sus ranuras con sus permisos. */
    PREVIOUS_RELEASED,

    /** Se olvidó lo que el teléfono recordaba del destino anterior. */
    MEMORY_FORGOTTEN,

    /** `link.json` describe el destino nuevo. */
    LINK_SAVED,
}

/** Cómo terminó un cambio de destino. */
internal enum class ChangeOutcome {
    /** El destino nuevo quedó conectado, y el anterior suelto. */
    CHANGED,

    /** No había otro destino guardado, o ya era ese: es conectar, no cambiar. Nada cambió. */
    NOT_A_CHANGE,

    /** No se pudo guardar. El destino anterior sigue igual, y se puede repetir. */
    FAILED,
}

/**
 * El enlace al destino desde la pantalla del respaldo: desconectar, volver tras iniciar sesión en
 * la nube del teléfono, decidir un conflicto y cambiar de destino. También deja en orden, al abrir
 * la app, lo que un corte dejó a medias.
 *
 * **Desconectar es local y sin red**, para que una muerte del proceso no deje nada a medias:
 *
 * 1. Cancela los trabajos de subida y toma el cerrojo del respaldo, esperando a que salga el
 *    trabajo en curso.
 * 2. Deja `NOT_CONNECTED` en la base y borra el usuario y el conflicto. Desde aquí, la base ya no
 *    tiene destino conectado y ningún trabajo sube: es el punto de no retorno.
 * 3. Borra `link.json`.
 * 4. Con WebDAV borra `webdav.sealed` y sus claves; con ranuras suelta los tres permisos y borra
 *    `slots.json`. Los archivos de la nube quedan allí, sin tocar.
 * 5. Anota `DESTINATION_DISCONNECTED` en la auditoría, sin dirección ni usuario. Si no queda, el
 *    destino sigue desconectado: el registro nunca deshace nada.
 *
 * Conserva el tipo, la huella, la carpeta, la cabeza conocida y la pérdida aceptada: el mismo
 * destino, al volver, sigue con su carpeta y su generación. Los cambios siguen contándose.
 *
 * Un corte entre el paso 2 y el 4 deja la base desconectada con archivos de más: al abrir, [tidy]
 * los borra. Nunca al revés: la base nunca dice conectada sin su credencial o sus ranuras.
 *
 * **Cambiar de destino** es como cambiar de cuenta, y corre bajo el mismo cerrojo: sin él, un
 * trabajo con la credencial vieja subiría al destino anterior y daría la copia por hecha. Cancela
 * los trabajos, toma el cerrojo y sigue este orden:
 *
 * 1. Escribe lo del destino nuevo al lado de lo actual: `webdav.sealed.new`, o `slots.json` con las
 *    ranuras nuevas. El destino anterior sigue funcionando.
 * 2. En una sentencia, la base pasa al destino nuevo sin heredar nada del anterior y fuerza una
 *    subida. Es el punto de no retorno. Después, y aparte, anota `DESTINATION_CHANGED`.
 * 3. Renombra `webdav.sealed.new` a `webdav.sealed` y borra la clave del archivo anterior.
 * 4. Suelta el destino anterior: sus ranuras con sus permisos, o su credencial.
 * 5. Olvida la sonda, las generaciones recordadas y la copia a medio subir.
 * 6. Escribe `link.json`, de último.
 *
 * Un corte antes del paso 2 deja el destino anterior intacto, y al abrir se borra lo que quedó del
 * nuevo. Un corte después deja `link.json` distinto de la base, y al abrir se repiten los pasos 3 a
 * 6. Las copias del destino anterior nunca se tocan.
 *
 * @param open abre la base de la app, o dice que no se puede.
 * @param mutex el cerrojo del respaldo, uno por proceso.
 * @param cancelWork cancela la espera, las subidas y la sonda única. No espera a que salgan: el
 *   cerrojo lo hace.
 * @param afterLinkRestored tras volver a conectar o salir de un conflicto, encola lo pendiente y la
 *   sonda única, ya fuera del cerrojo.
 * @param conflicts el que decide los conflictos sobre el estado de la base abierta.
 * @param memory lo que el teléfono recuerda del destino fuera de la base.
 * @param afterChange tras cambiar de destino, reprograma la sonda con la red del nuevo y encola
 *   la subida, ya fuera del cerrojo.
 * @param checkpoint lo que corre tras cada paso de desconectar. Solo las pruebas lo usan, para
 *   cortar ahí.
 * @param changeCheckpoint lo mismo, tras cada paso de cambiar de destino.
 */
internal class DestinationLinkService(
    private val open: suspend () -> DataAccess,
    private val mutex: Mutex,
    private val credentials: WebDavCredentialStore,
    private val links: DestinationLinkStore,
    private val slots: SlotStateStore,
    private val pending: SlotPendingStore,
    private val permissions: SlotFileDocuments,
    private val documents: DocumentAccess,
    private val cancelWork: suspend () -> Unit,
    private val afterLinkRestored: suspend () -> Unit,
    private val conflicts: (BackupStateDao) -> ConflictResolver,
    private val clock: Clock,
    private val log: EventLog,
    private val ioContext: CoroutineContext,
    private val memory: DestinationMemory? = null,
    private val afterChange: suspend () -> Unit = {},
    private val checkpoint: suspend (DisconnectStep) -> Unit = {},
    private val changeCheckpoint: suspend (ChangeStep) -> Unit = {},
) : BackupLinkPort {
    override suspend fun disconnect(): DisconnectResult = withContext(NonCancellable) {
        val ports = ports() ?: return@withContext DisconnectResult.FAILED
        cancelWork()
        mutex.withLock { disconnectLocked(ports) }
    }

    private suspend fun disconnectLocked(ports: DataPorts): DisconnectResult {
        val dao = ports.backupState
        val row = dao.get() ?: return DisconnectResult.FAILED
        if (row.linkState == BackupLinkState.NOT_CONNECTED.name) {
            // Nada que desconectar, pero quizá un corte dejó archivos del destino anterior.
            tidyDisconnected()
            return DisconnectResult.NOT_CONNECTED
        }
        val destinationId = row.destinationId ?: return DisconnectResult.FAILED
        if (dao.disconnect(destinationId) != 1) return DisconnectResult.FAILED
        checkpoint(DisconnectStep.STATE_SAVED)
        removeLink()
        checkpoint(DisconnectStep.LINK_REMOVED)
        removeAccess(kindOf(row))
        checkpoint(DisconnectStep.ACCESS_REMOVED)
        audit(ports, AuditEventType.DESTINATION_DISCONNECTED)
        log.info(DataEvent.DESTINATION_DISCONNECTED)
        return DisconnectResult.DISCONNECTED
    }

    /**
     * Deja en orden lo que un corte dejó a medias, al abrir la app y bajo el cerrojo:
     *
     * - **Sin destino conectado,** borra `link.json`, la credencial, `webdav.sealed.new` y
     *   `slots.json` que hayan quedado, y suelta los permisos de las ranuras que no estén en una
     *   preparación a medias.
     * - **Con un destino,** borra lo que quedó de otro: un `webdav.sealed.new` que la base no
     *   confirmó, o que sí confirmó y falta renombrar; las ranuras con WebDAV, o la credencial con
     *   ranuras. Si `link.json` no es del destino de la base, termina el cambio: olvida lo recordado
     *   del anterior y escribe `link.json` desde la base, también si una conexión se cortó antes.
     * - **Con las ranuras conectadas,** borra la marca de sesión cerrada que quedó tras volver.
     *
     * No usa la red, y una falla queda para la próxima vez.
     */
    suspend fun tidy() = withContext(NonCancellable) {
        val dao = ports()?.backupState ?: return@withContext
        mutex.withLock {
            val row = dao.get() ?: return@withLock
            when (BackupLinkState.entries.firstOrNull { it.name == row.linkState }) {
                BackupLinkState.NOT_CONNECTED -> {
                    tidyDisconnected()
                    if (credentials.nextExists()) credentials.deleteNext()
                }

                BackupLinkState.CONNECTED -> {
                    finishChange(row)
                    clearStaleSessionMark(row)
                }

                BackupLinkState.PERMISSION_LOST, BackupLinkState.CONFLICT -> finishChange(row)

                null -> Unit
            }
        }
    }

    /**
     * Cambia al servicio WebDAV de [link], con [credential], si hay otro destino guardado,
     * conectado o no. Sin otro destino, o con el mismo, no hace nada y devuelve
     * [ChangeOutcome.NOT_A_CHANGE]: eso es conectar.
     */
    suspend fun changeToWebDav(
        credential: StoredWebDavCredential,
        link: DestinationLink,
    ): ChangeOutcome {
        val outcome =
            withContext(NonCancellable) {
                val ports = ports() ?: return@withContext ChangeOutcome.FAILED
                cancelWork()
                mutex.withLock { changeToWebDavLocked(ports, credential, link) }
            }
        if (outcome == ChangeOutcome.CHANGED) afterChange()
        return outcome
    }

    private suspend fun changeToWebDavLocked(
        ports: DataPorts,
        credential: StoredWebDavCredential,
        link: DestinationLink,
    ): ChangeOutcome {
        val dao = ports.backupState
        val previous = dao.get()?.destinationId ?: return ChangeOutcome.NOT_A_CHANGE
        if (previous == link.destinationId) return ChangeOutcome.NOT_A_CHANGE
        when (credentials.saveNext(credential)) {
            CredentialSave.Saved -> Unit
            is CredentialSave.Rejected, is CredentialSave.Failed -> return ChangeOutcome.FAILED
        }
        changeCheckpoint(ChangeStep.NEXT_WRITTEN)
        val changed =
            dao.changeDestination(
                previousId = previous,
                kind = BackupDestinationKind.WEBDAV.name,
                account = link.account,
                destinationId = link.destinationId,
                folder = link.folderUrl,
                now = clock.now().toEpochMilliseconds(),
            ) == 1
        if (!changed) {
            // La base sigue con el destino anterior: la credencial nueva no sirve a nadie.
            credentials.deleteNext()
            return ChangeOutcome.FAILED
        }
        changeCheckpoint(ChangeStep.STATE_CHANGED)
        audit(ports, AuditEventType.DESTINATION_CHANGED)
        finishChange(requireNotNull(dao.get()) { "Falta la fila del respaldo" })
        return ChangeOutcome.CHANGED
    }

    /**
     * Cambia a las ranuras de [state], con sus tres archivos nuevos, si hay otro destino guardado,
     * conectado o no. Sin otro destino, o si ya eran las ranuras, devuelve
     * [ChangeOutcome.NOT_A_CHANGE] sin hacer nada.
     */
    suspend fun changeToSlots(state: SlotState): ChangeOutcome {
        val outcome =
            withContext(NonCancellable) {
                val ports = ports() ?: return@withContext ChangeOutcome.FAILED
                cancelWork()
                mutex.withLock { changeToSlotsLocked(ports, state) }
            }
        if (outcome == ChangeOutcome.CHANGED) afterChange()
        return outcome
    }

    private suspend fun changeToSlotsLocked(ports: DataPorts, state: SlotState): ChangeOutcome {
        val dao = ports.backupState
        val previous = dao.get()?.destinationId ?: return ChangeOutcome.NOT_A_CHANGE
        if (previous == DOCUMENT_SLOTS_DESTINATION_ID) return ChangeOutcome.NOT_A_CHANGE
        try {
            slots.save(state)
        } catch (_: IOException) {
            return ChangeOutcome.FAILED
        }
        changeCheckpoint(ChangeStep.NEXT_WRITTEN)
        val changed =
            dao.changeDestination(
                previousId = previous,
                kind = BackupDestinationKind.DOCUMENT_SLOTS.name,
                account = null,
                destinationId = DOCUMENT_SLOTS_DESTINATION_ID,
                folder = null,
                now = clock.now().toEpochMilliseconds(),
            ) == 1
        if (!changed) {
            // Los archivos siguen en la preparación, con sus permisos: se puede repetir.
            slots.delete()
            return ChangeOutcome.FAILED
        }
        changeCheckpoint(ChangeStep.STATE_CHANGED)
        audit(ports, AuditEventType.DESTINATION_CHANGED)
        finishChange(requireNotNull(dao.get()) { "Falta la fila del respaldo" })
        return ChangeOutcome.CHANGED
    }

    /**
     * Los pasos que siguen al de no retorno, sobre el destino que dice [row]. Se repiten al abrir
     * tras un corte: cada uno mira el disco, así que hacerlo dos veces no cambia nada.
     */
    private suspend fun finishChange(row: BackupStateEntity) {
        val destinationId = row.destinationId ?: return
        val kind = kindOf(row) ?: return
        when (kind) {
            BackupDestinationKind.WEBDAV ->
                if (credentials.nextExists()) {
                    // Confirmada en la base, falta renombrarla; si es de otro destino, sobra.
                    val promoted = credentials.promoteNext(destinationId)
                    if (!promoted) credentials.deleteNext()
                }

            BackupDestinationKind.DOCUMENT_SLOTS ->
                if (credentials.nextExists()) credentials.deleteNext()
        }
        changeCheckpoint(ChangeStep.CREDENTIAL_PROMOTED)
        when (kind) {
            // Un `slots.json` con WebDAV es de las ranuras anteriores, o de un cambio que no llegó.
            BackupDestinationKind.WEBDAV ->
                if (slots.load() !is SlotStateLoad.Missing) {
                    removeAccess(BackupDestinationKind.DOCUMENT_SLOTS)
                }

            BackupDestinationKind.DOCUMENT_SLOTS ->
                if (credentials.exists()) removeAccess(BackupDestinationKind.WEBDAV)
        }
        // La preparación de las ranuras ya está en `slots.json`: no queda nada que confirmar.
        if (kind == BackupDestinationKind.DOCUMENT_SLOTS) forgetUsedPending()
        changeCheckpoint(ChangeStep.PREVIOUS_RELEASED)
        if (links.read()?.destinationId == destinationId) return
        memory?.forget(clock.now())
        changeCheckpoint(ChangeStep.MEMORY_FORGOTTEN)
        val written =
            try {
                links.write(
                    DestinationLink(
                        kind = kind,
                        account = row.destinationAccount,
                        destinationId = destinationId,
                        folderUrl = row.destinationFolder,
                        connectedAtMillis = clock.now().toEpochMilliseconds(),
                    ),
                )
                true
            } catch (failure: IOException) {
                log.warn(DataEvent.DESTINATION_TIDY_FAILED, failure)
                false
            }
        if (written) changeCheckpoint(ChangeStep.LINK_SAVED)
    }

    /** Borra la preparación de las ranuras si todos sus archivos ya están en `slots.json`. */
    private suspend fun forgetUsedPending() {
        val used = loadedSlots()?.slots?.values?.map { it.uri }?.toSet() ?: return
        val waiting = pending.load().values.map { it.uri }
        if (waiting.all { it in used }) pending.delete()
    }

    override suspend fun resumeAfterSignIn(): Boolean {
        val resumed =
            withContext(NonCancellable) {
                val dao = ports()?.backupState ?: return@withContext false
                mutex.withLock { resumeLocked(dao) }
            }
        if (resumed) {
            log.info(DataEvent.SLOT_SESSION_RESUMED)
            afterLinkRestored()
        }
        return resumed
    }

    /**
     * Si la sesión de la nube volvió: las tres ranuras responden. Primero la base y después la
     * marca, para que un corte entre los dos nunca deje la base sin acceso y sin la marca, que
     * llevaría a crear archivos nuevos sin necesidad. La marca que quede la borra [tidy].
     */
    private suspend fun resumeLocked(dao: BackupStateDao): Boolean {
        val row = dao.get() ?: return false
        if (row.linkState != BackupLinkState.PERMISSION_LOST.name) return false
        if (row.destinationId != DOCUMENT_SLOTS_DESTINATION_ID) return false
        val state = loadedSlots()?.takeIf { it.sessionRequired } ?: return false
        val allAnswer =
            BackupSlot.entries.all { documents.query(state[it].uri) is DocumentResult.Ok }
        if (!allAnswer) return false
        if (dao.resumeConnected(DOCUMENT_SLOTS_DESTINATION_ID) != 1) return false
        saveSlots(state.copy(sessionRequired = false))
        return true
    }

    override suspend fun describeConflict(): ConflictLookup {
        val dao = ports()?.backupState ?: return ConflictLookup.Unreachable
        val kind = kindOf(dao.get() ?: return ConflictLookup.NotInConflict)
            ?: return ConflictLookup.NotInConflict
        return when (val look = conflicts(dao).describe(TransferControl.Unbounded)) {
            is ConflictLook.Shown -> ConflictLookup.Shown(sidesOf(look.view, kind))

            ConflictLook.NotInConflict -> ConflictLookup.NotInConflict

            ConflictLook.EmptyDatabase -> ConflictLookup.EmptyDatabase

            is ConflictLook.NewSlotFileRequired ->
                ConflictLookup.NewSlotFileRequired(
                    slot = BackupSlot.fromFileRefOrNull(look.slot?.value),
                    fileName = look.fileName,
                    threeBlocked = look.threeBlocked,
                    localRecords = look.localRecords,
                    pendingChanges = look.pendingChanges,
                )

            is ConflictLook.Unreachable -> ConflictLookup.Unreachable
        }
    }

    override suspend fun keepThisPhone(fewerRecordsConfirmed: Boolean): KeepPhoneResult {
        val dao = ports()?.backupState ?: return KeepPhoneResult.Unreachable
        val kind = kindOf(dao.get() ?: return KeepPhoneResult.NotInConflict)
            ?: return KeepPhoneResult.NotInConflict
        val outcome =
            withContext(NonCancellable) {
                conflicts(dao).keepThisPhone(fewerRecordsConfirmed, TransferControl.Unbounded)
            }
        return when (outcome) {
            KeepOutcome.Kept -> {
                afterLinkRestored()
                KeepPhoneResult.Kept
            }

            is KeepOutcome.NeedsConfirmation ->
                KeepPhoneResult.NeedsConfirmation(sidesOf(outcome.view, kind))

            is KeepOutcome.CloudChanged ->
                KeepPhoneResult.CloudChanged(sidesOf(outcome.view, kind))

            is KeepOutcome.NewSlotFileRequired -> {
                val slot = BackupSlot.fromFileRefOrNull(outcome.slot?.value)
                KeepPhoneResult.NewSlotFileRequired(slot)
            }

            KeepOutcome.TooManyPinned -> KeepPhoneResult.TooManyPinned

            KeepOutcome.PinUnavailable, is KeepOutcome.Unreachable -> KeepPhoneResult.Unreachable

            KeepOutcome.EmptyDatabase -> KeepPhoneResult.EmptyDatabase

            KeepOutcome.NotInConflict -> KeepPhoneResult.NotInConflict
        }
    }

    /** Borra lo que quede de un destino que la base ya no tiene conectado. */
    private suspend fun tidyDisconnected() {
        removeLink()
        if (credentials.exists()) removeAccess(BackupDestinationKind.WEBDAV)
        if (slots.load() !is SlotStateLoad.Missing) {
            removeAccess(BackupDestinationKind.DOCUMENT_SLOTS)
        }
    }

    private suspend fun clearStaleSessionMark(row: BackupStateEntity) {
        if (row.destinationId != DOCUMENT_SLOTS_DESTINATION_ID) return
        val state = loadedSlots()?.takeIf { it.sessionRequired } ?: return
        saveSlots(state.copy(sessionRequired = false))
    }

    private suspend fun removeLink() {
        val removed =
            try {
                links.delete()
            } catch (failure: IOException) {
                log.warn(DataEvent.DESTINATION_TIDY_FAILED, failure)
                return
            }
        if (!removed) log.warn(DataEvent.DESTINATION_TIDY_FAILED)
    }

    /**
     * Suelta el acceso al destino de clase [kind]: la credencial con sus claves, o los permisos de
     * las ranuras y `slots.json`. Con ranuras, un documento que está en una preparación a medias
     * conserva su permiso: lo usa esa preparación.
     */
    private suspend fun removeAccess(kind: BackupDestinationKind?) {
        when (kind) {
            BackupDestinationKind.WEBDAV ->
                when (credentials.delete()) {
                    CredentialDelete.Deleted -> Unit
                    is CredentialDelete.Failed -> log.warn(DataEvent.DESTINATION_TIDY_FAILED)
                }

            BackupDestinationKind.DOCUMENT_SLOTS -> {
                val uris = loadedSlots()?.slots?.values?.map { it.uri }.orEmpty()
                val inUse = pending.load().values.map { it.uri }.toSet()
                withContext(ioContext) {
                    val unused = uris.filterNot { it in inUse }.distinct()
                    unused.forEach(permissions::releasePermission)
                }
                val removed =
                    try {
                        slots.delete()
                    } catch (failure: IOException) {
                        log.warn(DataEvent.DESTINATION_TIDY_FAILED, failure)
                        return
                    }
                if (!removed) log.warn(DataEvent.DESTINATION_TIDY_FAILED)
            }

            null -> Unit
        }
    }

    /**
     * Anota [type] en la auditoría, fuera de la escritura que cambió el destino: una falla al
     * anotar nunca deshace nada, y solo queda en el registro por su clase.
     */
    private suspend fun audit(ports: DataPorts, type: AuditEventType) {
        try {
            ports.auditEvents.insert(
                AuditEventEntity(
                    occurredAt = clock.now().toEpochMilliseconds(),
                    eventType = type.name,
                    result = AuditResult.SUCCESS.name,
                ),
            )
            ports.auditEvents.pruneOldest()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            log.warn(DataEvent.DESTINATION_AUDIT_FAILED, failure)
        }
    }

    private suspend fun saveSlots(state: SlotState) {
        try {
            slots.save(state)
        } catch (failure: IOException) {
            log.warn(DataEvent.DESTINATION_TIDY_FAILED, failure)
        }
    }

    private suspend fun loadedSlots(): SlotState? = (slots.load() as? SlotStateLoad.Loaded)?.state

    private suspend fun ports(): DataPorts? = when (val access = open()) {
        is DataAccess.Ready -> access.ports
        is DataAccess.Rescue -> null
    }

    private fun kindOf(row: BackupStateEntity): BackupDestinationKind? =
        BackupDestinationKind.entries.firstOrNull { it.name == row.destinationKind }

    private fun sidesOf(view: ConflictView, kind: BackupDestinationKind) = ConflictSides(
        kind = view.kind,
        destinationKind = kind,
        localRecords = view.localRecords,
        localCatalogCount = view.localCatalogCount,
        pendingChanges = view.pendingChanges,
        cloudCopyKey = view.cloudRef?.value,
        cloudCreatedAt = view.cloudCreatedAtMillis?.let(Instant::fromEpochMilliseconds),
        cloudDeviceLabel = view.cloudDeviceLabel,
        cloudFromThisPhone = view.cloudFromThisPhone,
        cloudRecords = view.cloudRecords,
        cloudCatalogCount = view.cloudCatalogCount,
    )
}

/**
 * Lo que este estado de las ranuras recuerda de la ranura [ref], para el diálogo del conflicto, o
 * nulo si [ref] no es una ranura o no hay estado que leer.
 */
internal suspend fun SlotStateStore.factsOf(ref: CopyRef): SlotFileFacts? {
    val slot = BackupSlot.fromFileRefOrNull(ref.value) ?: return null
    val state = (load() as? SlotStateLoad.Loaded)?.state ?: return null
    val entry = state[slot]
    return SlotFileFacts(entry.displayName, entry.copy?.writtenAt)
}

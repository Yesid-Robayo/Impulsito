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

/**
 * El enlace al destino desde la pantalla del respaldo: desconectar, volver tras iniciar sesión en
 * la nube del teléfono y decidir un conflicto. También deja en orden, al abrir la app, lo que un
 * corte dejó a medias.
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
 * @param open abre la base de la app, o dice que no se puede.
 * @param mutex el cerrojo del respaldo, uno por proceso.
 * @param cancelWork cancela la espera, las subidas y la sonda única. No espera a que salgan: el
 *   cerrojo lo hace.
 * @param afterLinkRestored tras volver a conectar o salir de un conflicto, encola lo pendiente y la
 *   sonda única, ya fuera del cerrojo.
 * @param conflicts el que decide los conflictos sobre el estado de la base abierta.
 * @param checkpoint lo que corre tras cada paso de desconectar. Solo las pruebas lo usan, para
 *   cortar ahí.
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
    private val checkpoint: suspend (DisconnectStep) -> Unit = {},
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
     * - **Sin destino conectado,** borra `link.json`, la credencial y `slots.json` que hayan
     *   quedado, y suelta los permisos de las ranuras que no estén en una preparación a medias.
     * - **Con las ranuras conectadas,** borra la marca de sesión cerrada que quedó tras volver.
     *
     * No usa la red, y una falla queda para la próxima vez.
     */
    suspend fun tidy() = withContext(NonCancellable) {
        val dao = ports()?.backupState ?: return@withContext
        mutex.withLock {
            val row = dao.get() ?: return@withLock
            when (BackupLinkState.entries.firstOrNull { it.name == row.linkState }) {
                BackupLinkState.NOT_CONNECTED -> tidyDisconnected()
                BackupLinkState.CONNECTED -> clearStaleSessionMark(row)
                BackupLinkState.PERMISSION_LOST, BackupLinkState.CONFLICT, null -> Unit
            }
        }
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

package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.DataAccess
import io.github.yesidrobayo.shopledger.data.WorkAccess
import io.github.yesidrobayo.shopledger.data.destination.ConnectedDestination
import io.github.yesidrobayo.shopledger.data.destination.CopyListing
import io.github.yesidrobayo.shopledger.data.destination.CopyRef
import io.github.yesidrobayo.shopledger.data.destination.DestinationFailure
import io.github.yesidrobayo.shopledger.data.destination.DestinationOpen
import io.github.yesidrobayo.shopledger.data.destination.DestinationOpener
import io.github.yesidrobayo.shopledger.data.destination.DestinationResult
import io.github.yesidrobayo.shopledger.data.destination.TransferControl
import io.github.yesidrobayo.shopledger.data.local.backup.BackupStateDao
import io.github.yesidrobayo.shopledger.data.local.backup.BackupStateEntity
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupConflictKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupFailureCode
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupLinkState
import io.github.yesidrobayo.shopledger.domain.model.backup.ServiceSpace
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.LogEvent
import io.github.yesidrobayo.shopledger.domain.port.LogField
import io.github.yesidrobayo.shopledger.domain.port.info
import io.github.yesidrobayo.shopledger.domain.port.warn
import kotlinx.coroutines.sync.Mutex

/**
 * La sonda de la cabeza: contrasta con el destino que lo que la app recuerda siga siendo cierto,
 * sin esperar a un cambio local. Sin cambios pendientes el respaldo no corre, y el estado diría
 * que todo está guardado aunque la persona haya borrado las copias, u otro teléfono haya subido
 * algo más nuevo.
 *
 * - **Bajo el cerrojo del respaldo, sin esperarlo.** Si está ocupado sale sin anotar nada: esa copia
 *   o esa restauración ya están mirando el destino.
 * - **Con WebDAV, por la lista**, que trae también el espacio libre del servicio. **Con ranuras,
 *   por el tamaño de los tres documentos**, salvo la ranura a medias, sin pedir red.
 * - **La cabeza que falta, o cuyo tamaño ya no es el de sus metadatos, fuerza una subida.**
 * - **Una cabeza más nueva da conflicto** sin esperar a un cambio local, con la misma guarda del
 *   respaldo. Con una subida propia sin confirmar no decide conflicto: esa copia se vería como una
 *   cabeza más nueva, y el aviso no sería cierto. Eso lo resuelve la recuperación de la subida.
 * - **Ranuras:** una que falta o perdió el permiso deja el acceso perdido; un tamaño nulo, el
 *   destino sin soporte; un tamaño distinto, conflicto con otro teléfono.
 * - **Al terminar** anota la sonda en `last-probe`, con el espacio del servicio si lo informó.
 *
 * No hace nada sin un destino conectado, sin la política aceptada o con una restauración a medias.
 * Nada de lo que escribe lleva datos del negocio, y del destino solo registra códigos.
 *
 * @param prepare abre la base y mira las precondiciones, como el mantenimiento.
 * @param mutex el cerrojo del respaldo, el mismo de cada copia y de cada restauración.
 * @param rejected las generaciones propias rechazadas, que nunca cuentan como cabeza.
 */
internal class HeadProbe(
    private val prepare: suspend () -> ProbeAccess,
    private val destinations: DestinationOpener,
    private val deviceId: suspend () -> String,
    private val schemaVersion: Int,
    private val rejected: suspend () -> RejectedGenerationStore,
    private val lastProbe: suspend () -> LastProbeStore,
    private val mutex: Mutex,
    private val clock: Clock,
    private val log: EventLog,
) {
    /** Corre una sonda. */
    suspend fun run(control: TransferControl): ProbeRun {
        val ready = when (val access = prepare()) {
            is ProbeAccess.Ready -> access
            ProbeAccess.Waiting -> return ProbeRun.RETRY
            ProbeAccess.Nothing -> return ProbeRun.DONE
        }
        if (!mutex.tryLock()) {
            log.info(ProbeEvent.BACKUP_BUSY)
            return ProbeRun.DONE
        }
        try {
            return probe(ready.state, ready.link, control)
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun probe(
        state: BackupStateDao,
        link: ConnectedDestination,
        control: TransferControl,
    ): ProbeRun {
        // Con el cerrojo tomado, el estado ya no cambia por una copia.
        val current = state.get() ?: return ProbeRun.DONE
        if (connectedOf(current) != link) return ProbeRun.DONE
        val destination =
            when (val opened = destinations.open(link)) {
                is DestinationOpen.Opened -> opened.destination

                DestinationOpen.PermissionLost -> {
                    state.leaveConnected(BackupLinkState.PERMISSION_LOST.name, link.destinationId)
                    return ProbeRun.DONE
                }

                DestinationOpen.RetryLater -> return ProbeRun.RETRY
            }
        val listing =
            when (val listed = destination.listCopies(control)) {
                is DestinationResult.Success -> listed.value
                is DestinationResult.Failure -> return failed(listed.failure, state, link)
            }
        val device = deviceId()
        val rejectedGenerations = rejected().load()
        val known =
            knownCopies(listing).filterNot {
                it.deviceId == device && it.generation in rejectedGenerations
            }
        val headMissing = headMissing(current, known)
        if (headMissing) {
            state.forceUpload(clock.nowEpochMillis())
            log.warn(ProbeEvent.HEAD_MISSING)
        }
        // Con una subida propia sin confirmar, esa copia parecería una cabeza más nueva.
        if (current.pendingGeneration == null) {
            val decision =
                BackupGuard.decide(
                    guardInput(state, listing, known, current, device, rejectedGenerations),
                )
            if (decision is GuardDecision.Conflict) {
                state.markConflict(
                    decision.kind.name,
                    decision.head.generation,
                    decision.head.deviceId,
                    decision.head.ref.value,
                    link.destinationId,
                )
                log.warn(ProbeEvent.CONFLICT, LogField.state("conflicto", decision.kind))
            }
        }
        val space = if (destination.kind ==
            BackupDestinationKind.WEBDAV
        ) {
            spaceOf(listing, current)
        } else {
            null
        }
        lastProbe().save(LastProbe(clock.now(), space))
        log.info(ProbeEvent.PROBED)
        return ProbeRun.DONE
    }

    /**
     * Si la cabeza que esta base confirmó ya no está, o no con el tamaño de sus metadatos. Sin
     * ninguna confirmada no hay qué buscar.
     */
    private fun headMissing(current: BackupStateEntity, known: List<KnownCopy>): Boolean {
        val ref = current.headFileRef ?: return false
        if (current.headGeneration <= 0) return false
        return known.none { it.ref.value == ref && it.generation == current.headGeneration }
    }

    private suspend fun guardInput(
        state: BackupStateDao,
        listing: CopyListing,
        known: List<KnownCopy>,
        current: BackupStateEntity,
        device: String,
        rejectedGenerations: Set<Long>,
    ): GuardInput = GuardInput(
        copies = known,
        deviceId = device,
        headGeneration = current.headGeneration,
        headDeviceId = current.headDeviceId,
        pendingGeneration = current.pendingGeneration,
        appSchemaVersion = schemaVersion,
        local =
            LocalCounts(
                isEmpty = state.isEmpty(),
                records = state.recordCount(),
                catalogCount = state.catalogCount(),
                pendingChanges = current.changeSeq - current.verifiedSeq,
            ),
        shrinkAcceptedGeneration = current.shrinkAcceptedGeneration,
        highestOwnGeneration = highestOwnGeneration(listing, device),
        rejectedGenerations = rejectedGenerations,
    )

    /**
     * El espacio del servicio, si informó un tope, con el tamaño de la cabeza o, sin ella, el de la
     * copia más grande de la lista.
     */
    private fun spaceOf(listing: CopyListing, current: BackupStateEntity): ServiceSpace? {
        val available = listing.quotaAvailableBytes ?: return null
        val head = listing.copies.firstOrNull { it.ref.value == current.headFileRef }
        val copyBytes =
            head?.sizeBytes ?: listing.copies.maxOfOrNull { it.sizeBytes } ?: return null
        return ServiceSpace(available, copyBytes)
    }

    /**
     * Lo que dice un fallo al listar. Lo que espera a la persona queda anotado como en el respaldo;
     * lo pasajero se reintenta sin anotar la sonda, que vuelve a tocar.
     */
    private suspend fun failed(
        failure: DestinationFailure,
        state: BackupStateDao,
        link: ConnectedDestination,
    ): ProbeRun {
        log.info(ProbeEvent.LIST_FAILED, LogField.state("fallo", failureName(failure)))
        return when (failure) {
            is DestinationFailure.PermissionLost -> {
                state.leaveConnected(BackupLinkState.PERMISSION_LOST.name, link.destinationId)
                ProbeRun.DONE
            }

            is DestinationFailure.Unsupported -> {
                state.recordFailure(BackupFailureCode.DESTINATION_UNSUPPORTED.name)
                ProbeRun.DONE
            }

            is DestinationFailure.ChangedOutside -> {
                state.markConflict(
                    BackupConflictKind.OTHER_DEVICE.name,
                    null,
                    null,
                    failure.ref?.value,
                    link.destinationId,
                )
                ProbeRun.DONE
            }

            DestinationFailure.Incomplete,
            DestinationFailure.LocalStorage,
            is DestinationFailure.Transient,
            is DestinationFailure.Interrupted,
            -> ProbeRun.RETRY

            // Listar no da estos fallos: si llegaran, no dicen nada de la cabeza.
            DestinationFailure.QuotaExceeded,
            DestinationFailure.VerifyMismatch,
            is DestinationFailure.NoSafeSlot,
            -> ProbeRun.DONE
        }
    }
}

/** Lo que una sonda o una comprobación encontró antes de tocar el destino. */
internal sealed interface ProbeAccess {
    /** La base está abierta, el destino conectado y la política aceptada. */
    class Ready(val state: BackupStateDao, val link: ConnectedDestination) : ProbeAccess

    /** Una restauración, una migración o un reemplazo esperan: hay que volver más tarde. */
    data object Waiting : ProbeAccess

    /** No hay nada que contrastar: sin destino, sin la política aceptada o con la base en rescate. */
    data object Nothing : ProbeAccess
}

/** Cómo terminó una sonda o una comprobación. */
internal enum class ProbeRun {
    /** Terminó, o no tocaba: la siguiente es la programada. */
    DONE,

    /** Algo pasajero: el sistema la reintenta. */
    RETRY,
}

/** Lo que necesita una sonda de lo que el estado guarda de la cabeza: su lugar, si lo hay. */
internal fun headRefOf(current: BackupStateEntity): CopyRef? = current.headFileRef?.takeIf {
    it.isNotEmpty() && it.length <= CopyRef.MAX_LENGTH
}?.let(::CopyRef)

/** Los eventos del registro de la sonda y de la comprobación. Solo llevan códigos y estados. */
internal enum class ProbeEvent(override val text: String) : LogEvent {
    PROBED("Sonda de la cabeza terminada"),
    BACKUP_BUSY("Respaldo o restauración en curso: la sonda queda para la siguiente"),
    HEAD_MISSING("La cabeza ya no está en el destino: se fuerza una subida"),
    CONFLICT("La sonda encontró una cabeza que la persona debe revisar"),
    LIST_FAILED("La sonda no pudo listar el destino"),
    DEEP_CHECK_PASSED("Comprobación completa de la cabeza correcta"),
    DEEP_CHECK_FAILED("La comprobación completa no pudo comprobar la cabeza"),
    DEEP_CHECK_SKIPPED("Sin espacio para la comprobación completa"),
    DEEP_CHECK_STALE("La comprobación terminó sobre otro destino o otra cabeza: no se anota"),
    WORKER_FAILED("Falla no prevista de la sonda de la cabeza"),
}

/**
 * Abre la base y mira las precondiciones de la sonda y de la comprobación: sin restauración, con un
 * destino conectado y con la política aceptada.
 *
 * @param access abre la base para un trabajo, sin migrar.
 */
internal suspend fun probeAccess(
    restorePending: suspend () -> Boolean,
    policyAccepted: suspend () -> Boolean,
    access: suspend () -> WorkAccess,
): ProbeAccess {
    if (restorePending()) return ProbeAccess.Waiting
    val state = when (val opened = access()) {
        is DataAccess.Ready -> opened.ports.backupState
        WorkAccess.MigrationPending, WorkAccess.RestorePending -> return ProbeAccess.Waiting
        is DataAccess.Rescue -> return ProbeAccess.Nothing
    }
    val current = state.get() ?: return ProbeAccess.Nothing
    val link = connectedOf(current) ?: return ProbeAccess.Nothing
    if (!policyAccepted()) return ProbeAccess.Nothing
    return ProbeAccess.Ready(state, link)
}

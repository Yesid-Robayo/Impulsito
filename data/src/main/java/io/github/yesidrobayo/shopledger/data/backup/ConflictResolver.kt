package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.destination.BackupDestination
import io.github.yesidrobayo.shopledger.data.destination.ConnectedDestination
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
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupLinkState
import io.github.yesidrobayo.shopledger.domain.port.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Lo que la persona puede hacer cuando el respaldo se detuvo por un conflicto con el destino.
 *
 * Son tres opciones:
 *
 * - **Restaurar la copia de la nube:** es la restauración de siempre, con su copia de seguridad
 *   de los datos locales. No vive aquí.
 * - **Conservar este teléfono:** [keepThisPhone]. Nada se borra: la copia nueva va por encima de
 *   la cabeza del destino, y la cabeza queda protegida antes de seguir.
 * - **Decidir después:** no cambia nada. El enlace sigue en conflicto, sin subir, y el aviso a la
 *   vista. No hace falta llamar a nada.
 *
 * Corre bajo el mismo cerrojo que el respaldo: nunca decide a la vez que una subida.
 *
 * @param pinner fija las copias de la cabeza con WebDAV. Sin él, conservar este teléfono con
 *   WebDAV no se confirma: sin fijar la cabeza, la poda podría borrarla.
 * @param mutex el cerrojo del respaldo, uno por proceso.
 */
internal class ConflictResolver(
    private val state: BackupStateDao,
    private val destinations: DestinationOpener,
    private val deviceId: suspend () -> String,
    private val clock: Clock,
    private val schemaVersion: Int,
    private val mutex: Mutex,
    private val pinner: HeadPinner = HeadPinner.Unavailable,
) {
    /**
     * Lo que el diálogo del conflicto compara: los datos de cada lado. Lee el destino, así que
     * necesita red con WebDAV.
     */
    suspend fun describe(control: TransferControl): ConflictLook = mutex.withLock {
        when (val read = read(control)) {
            is Read.Done -> read.look
            is Read.Ready -> ConflictLook.Shown(viewOf(read))
        }
    }

    /**
     * Conserva este teléfono por encima de la cabeza del destino.
     *
     * Si la base tiene menos registros o menos catálogo que la cabeza, pide antes que la persona
     * lo confirme, con los números de cada lado. Con WebDAV fija la cabeza, y solo si eso funciona
     * deja de esperar. Con ranuras, la regla de turnos ya conserva la ranura con más datos cuando
     * la cabeza es más nueva o tiene más; si otro teléfono cambió una ranura, o las tres están
     * bloqueadas, hace falta un archivo nuevo para esa ranura, y sin él no se confirma.
     *
     * @param fewerRecordsConfirmed si la persona ya confirmó quedarse con menos datos que el destino.
     */
    suspend fun keepThisPhone(
        fewerRecordsConfirmed: Boolean,
        control: TransferControl,
    ): KeepOutcome = mutex.withLock {
        val read =
            when (val result = read(control)) {
                is Read.Ready -> result
                is Read.Done -> return@withLock outcomeOf(result.look)
            }
        val head = read.head
        if (head.isEmpty()) {
            // La persona borró las copias: no queda cabeza que proteger.
            val rows = state.reopenAfterConflict(clock.nowEpochMillis(), read.link.destinationId)
            return@withLock if (rows == 0) KeepOutcome.NotInConflict else KeepOutcome.Kept
        }
        val top = head.first()
        val expected = read.current.conflictGeneration
        if (expected != null && expected != top.generation) {
            // La cabeza cambió desde que se mostró el conflicto: la persona decide otra vez.
            return@withLock KeepOutcome.CloudChanged(viewOf(read))
        }
        val view = viewOf(read)
        if (view.localHasFewer && !fewerRecordsConfirmed) {
            return@withLock KeepOutcome.NeedsConfirmation(view)
        }
        if (read.link.kind == BackupDestinationKind.WEBDAV) {
            when (val pinned = pinner.pin(read.destination, head.map(KnownCopy::ref), control)) {
                PinOutcome.Pinned -> Unit
                PinOutcome.TooManyPinned -> return@withLock KeepOutcome.TooManyPinned
                PinOutcome.Unavailable -> return@withLock KeepOutcome.PinUnavailable
                is PinOutcome.Failed -> return@withLock KeepOutcome.Unreachable(pinned.failure)
            }
        }
        // La cabeza que conoce la base es la del otro teléfono si la hay: así el empate queda
        // aceptado, y la copia nueva sube por encima.
        val shown = head.firstOrNull { it.deviceId != read.device } ?: top
        val rows =
            state.keepThisPhone(
                generation = top.generation,
                deviceId = shown.deviceId,
                fileRef = shown.ref.value,
                now = clock.nowEpochMillis(),
                destinationId = read.link.destinationId,
            )
        if (rows == 0) KeepOutcome.NotInConflict else KeepOutcome.Kept
    }

    /** Lo leído para decidir, o por qué no se puede seguir. */
    private sealed interface Read {
        class Ready(
            val current: BackupStateEntity,
            val kind: BackupConflictKind,
            val link: ConnectedDestination,
            val destination: BackupDestination,
            val device: String,
            val local: LocalCounts,
            val head: List<KnownCopy>,
        ) : Read

        class Done(val look: ConflictLook) : Read
    }

    private suspend fun read(control: TransferControl): Read {
        val current = state.get() ?: return Read.Done(ConflictLook.NotInConflict)
        if (current.linkState != BackupLinkState.CONFLICT.name) {
            return Read.Done(ConflictLook.NotInConflict)
        }
        val kind =
            BackupConflictKind.entries.firstOrNull { it.name == current.conflictKind }
                ?: return Read.Done(ConflictLook.NotInConflict)
        val link = destinationOf(current) ?: return Read.Done(ConflictLook.NotInConflict)
        // Conservar este teléfono no se ofrece con la base vacía: no hay nada que conservar.
        if (state.isEmpty()) return Read.Done(ConflictLook.EmptyDatabase)
        if (link.kind == BackupDestinationKind.DOCUMENT_SLOTS && needsNewSlotFile(kind, current)) {
            // Ese lado no tiene datos que mostrar: la app no sabe qué tiene ese archivo.
            return Read.Done(
                ConflictLook.NewSlotFileRequired(
                    current.conflictFileRef?.takeIf { it.length <= CopyRef.MAX_LENGTH }
                        ?.takeIf(String::isNotEmpty)?.let(::CopyRef),
                ),
            )
        }
        val destination =
            when (val opened = destinations.open(link)) {
                is DestinationOpen.Opened -> opened.destination

                DestinationOpen.PermissionLost ->
                    return Read.Done(
                        ConflictLook.Unreachable(DestinationFailure.PermissionLost()),
                    )

                DestinationOpen.RetryLater ->
                    return Read.Done(ConflictLook.Unreachable(DestinationFailure.Incomplete))
            }
        val listing =
            when (val listed = destination.listCopies(control)) {
                is DestinationResult.Success -> listed.value

                is DestinationResult.Failure -> return Read.Done(
                    ConflictLook.Unreachable(listed.failure),
                )
            }
        val device = deviceId()
        val local =
            LocalCounts(
                isEmpty = false,
                records = state.recordCount(),
                catalogCount = state.catalogCount(),
                pendingChanges = current.changeSeq - current.verifiedSeq,
            )
        val input =
            GuardInput(
                copies = knownCopies(listing),
                deviceId = device,
                headGeneration = current.headGeneration,
                headDeviceId = current.headDeviceId,
                pendingGeneration = current.pendingGeneration,
                appSchemaVersion = schemaVersion,
                local = local,
                shrinkAcceptedGeneration = current.shrinkAcceptedGeneration,
            )
        return Read.Ready(
            current,
            kind,
            link,
            destination,
            device,
            local,
            BackupGuard.headOf(input),
        )
    }

    /**
     * Con ranuras, un archivo que cambió por fuera no se puede contar ni leer, y con las tres
     * ranuras bloqueadas no hay dónde escribir: en los dos casos, solo un archivo nuevo deja seguir
     * sin destruir nada. Las tres bloqueadas se reconocen porque ese conflicto no guarda generación.
     */
    private fun needsNewSlotFile(kind: BackupConflictKind, current: BackupStateEntity): Boolean =
        when (kind) {
            BackupConflictKind.OTHER_DEVICE -> true
            BackupConflictKind.LOCAL_SHRUNK -> current.conflictGeneration == null
            BackupConflictKind.CLOUD_AHEAD -> false
        }

    private fun viewOf(read: Read.Ready): ConflictView {
        val head = read.head
        return ConflictView(
            kind = read.kind,
            localRecords = read.local.records,
            localCatalogCount = read.local.catalogCount,
            pendingChanges = read.local.pendingChanges,
            cloudGeneration = head.firstOrNull()?.generation,
            cloudRecords = head.maxOfOrNull(KnownCopy::records),
            cloudCatalogCount = head.maxOfOrNull(KnownCopy::catalogCount),
            cloudFromThisPhone = head.isNotEmpty() && head.all { it.deviceId == read.device },
        )
    }

    private fun outcomeOf(look: ConflictLook): KeepOutcome = when (look) {
        ConflictLook.NotInConflict -> KeepOutcome.NotInConflict
        ConflictLook.EmptyDatabase -> KeepOutcome.EmptyDatabase
        is ConflictLook.NewSlotFileRequired -> KeepOutcome.NewSlotFileRequired(look.slot)
        is ConflictLook.Unreachable -> KeepOutcome.Unreachable(look.failure)
        is ConflictLook.Shown -> KeepOutcome.NotInConflict
    }
}

/**
 * Los dos lados de un conflicto, en conteos neutros: nunca nombres, montos ni la dirección del
 * servicio.
 *
 * @property cloudGeneration la generación de la cabeza del destino, o nula si ya no hay cabeza.
 * @property cloudFromThisPhone si toda la cabeza la escribió este mismo teléfono, con una base que
 *   ya no es esta.
 */
internal data class ConflictView(
    val kind: BackupConflictKind,
    val localRecords: Long,
    val localCatalogCount: Long,
    val pendingChanges: Long,
    val cloudGeneration: Long?,
    val cloudRecords: Long?,
    val cloudCatalogCount: Long?,
    val cloudFromThisPhone: Boolean,
) {
    /** Si conservar este teléfono deja menos datos que la cabeza: pide confirmación reforzada. */
    val localHasFewer: Boolean
        get() =
            (cloudRecords != null && localRecords < cloudRecords) ||
                (cloudCatalogCount != null && localCatalogCount < cloudCatalogCount)
}

/** Lo que el diálogo del conflicto puede mostrar. */
internal sealed interface ConflictLook {
    data class Shown(val view: ConflictView) : ConflictLook

    /** El respaldo no está detenido por un conflicto. */
    data object NotInConflict : ConflictLook

    /** La base no tiene nada del negocio: solo se ofrece restaurar. */
    data object EmptyDatabase : ConflictLook

    /**
     * Con ranuras, la de [slot] no se puede leer ni contar: la salida es un archivo nuevo para
     * ella, o abrir ese archivo para restaurarlo.
     */
    data class NewSlotFileRequired(val slot: CopyRef?) : ConflictLook

    /** El destino no respondió: sin él no se decide. */
    data class Unreachable(val failure: DestinationFailure) : ConflictLook
}

/** Cómo terminó [ConflictResolver.keepThisPhone]. Salvo [Kept], el conflicto sigue igual. */
internal sealed interface KeepOutcome {
    /** El enlace volvió a subir: la copia siguiente va por encima de la cabeza, que queda. */
    data object Kept : KeepOutcome

    /** La base tiene menos que la cabeza: hace falta que la persona lo confirme. */
    data class NeedsConfirmation(val view: ConflictView) : KeepOutcome

    /** La cabeza cambió desde que se mostró el conflicto: hay que mostrarlo otra vez. */
    data class CloudChanged(val view: ConflictView) : KeepOutcome

    /** Con ranuras, hace falta un archivo nuevo para la ranura [slot] antes de seguir. */
    data class NewSlotFileRequired(val slot: CopyRef?) : KeepOutcome

    /** Ya hay tres copias fijadas: la persona libera una antes de seguir. */
    data object TooManyPinned : KeepOutcome

    /** Esta versión no sabe fijar en este destino: sin fijar, no se confirma. */
    data object PinUnavailable : KeepOutcome

    /** El destino no respondió, o falló al fijar: sin red no se confirma. */
    data class Unreachable(val failure: DestinationFailure) : KeepOutcome

    /** No se ofrece con la base vacía. */
    data object EmptyDatabase : KeepOutcome

    /** El respaldo ya no estaba en conflicto con ese destino. */
    data object NotInConflict : KeepOutcome
}

/**
 * Fija las copias de una cabeza, para que la poda nunca las borre, antes de una operación que deja
 * la base con menos datos que ellas. Solo con WebDAV: las ranuras no se fijan.
 */
internal fun interface HeadPinner {
    suspend fun pin(
        destination: BackupDestination,
        refs: List<CopyRef>,
        control: TransferControl,
    ): PinOutcome

    companion object {
        /** No sabe fijar: quien la use no confirma nada que necesite una copia fijada. */
        val Unavailable = HeadPinner { _, _, _ -> PinOutcome.Unavailable }
    }
}

/** Cómo terminó [HeadPinner.pin]. */
internal sealed interface PinOutcome {
    /** Todas quedaron fijadas, releídas en el destino. */
    data object Pinned : PinOutcome

    /** Fijarlas pasaría del máximo de copias fijadas. No se fijó ninguna. */
    data object TooManyPinned : PinOutcome

    /** No hay cómo fijar en este destino. */
    data object Unavailable : PinOutcome

    /** El destino falló al fijar. */
    data class Failed(val failure: DestinationFailure) : PinOutcome
}

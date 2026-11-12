package io.github.yesidrobayo.shopledger.data.destination.slots

import android.net.Uri
import io.github.yesidrobayo.shopledger.data.DataAccess
import io.github.yesidrobayo.shopledger.data.backup.slotConflictNeedsNewFile
import io.github.yesidrobayo.shopledger.data.destination.ChangeOutcome
import io.github.yesidrobayo.shopledger.data.destination.DestinationLink
import io.github.yesidrobayo.shopledger.data.destination.DestinationLinkStore
import io.github.yesidrobayo.shopledger.data.local.backup.BackupStateDao
import io.github.yesidrobayo.shopledger.data.local.backup.BackupStateEntity
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupConflictKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupLinkState
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupSlot
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotFileOutcome
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotFileRejection
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotFileStatus
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotSetupConfirm
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotSetupFile
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotSetupLook
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.SlotFilesSetupPort
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Los pasos de la confirmación, en orden. Las pruebas cortan tras cada uno. */
internal enum class SlotConfirmStep {
    /** La cabeza ya no apunta a una ranura que va a tener un archivo nuevo. */
    HEAD_FORGOTTEN,

    /** `slots.json` describe las ranuras nuevas. */
    SLOTS_SAVED,

    /** La base tiene las ranuras como destino. */
    DESTINATION_CONNECTED,

    /** `link.json` refleja el destino. */
    LINK_SAVED,
}

/**
 * Prepara las ranuras de la nube del teléfono con los documentos que la persona crea en el selector
 * del sistema, uno por uno.
 *
 * - **Solo nubes de la lista fija, y todas en la misma:** un documento de otra autoridad, o de otra
 *   nube que la de las ranuras que ya hay, se rechaza sin tomar su permiso.
 *   Tampoco sirve uno que no se puede escribir, que es el de otra ranura, sin tamaño o con datos: se
 *   suelta su permiso, y nunca se escribe ni se borra.
 * - **El avance sobrevive:** cada archivo que sirve queda en `slots-pending.json`, y al volver se
 *   comprueba otra vez, con su permiso y con 0 bytes.
 * - **Reconectar no rehace lo que sirve:** de las ranuras que había, las que siguen abriendo quedan
 *   como están, con sus copias. Solo se crean las que fallaron.
 * - **Cambiar de destino tiene su propio camino:** con otro destino guardado, conectado o no,
 *   confirmar pasa por [change], que suelta el destino anterior bajo el cerrojo, con toda su
 *   limpieza. Sin él, los archivos se crean pero no se confirman.
 * - **Un conflicto que pide un archivo nuevo** deja esa ranura por crear, aunque su archivo abra:
 *   otro teléfono lo cambió, o las tres ranuras están protegidas. El archivo anterior queda en la
 *   nube sin tocar, y confirmar es conservar este teléfono: el conflicto termina y sale una copia.
 *
 * La confirmación corre bajo el cerrojo del respaldo, para que ninguna copia escriba a la vez, y en
 * este orden, pensado para que un corte entre dos pasos no deje nada inseguro:
 *
 * 1. Si una ranura nueva tenía la cabeza, se olvida en qué ranura está, y se fuerza una subida. Va
 *    antes que lo demás: así ninguna cabeza apunta a un archivo vacío.
 * 2. `slots.json`, con las ranuras que siguen y las nuevas, sin copias, y sin primera entrega: la
 *    cuenta de días para revisar la nube empieza con la próxima.
 * 3. La base: el destino conectado, la revisión de la persona olvidada y una subida forzada.
 * 4. `link.json`. Si no queda, repetir la confirmación lo escribe.
 * 5. Se borra el avance, y se sueltan los permisos de los documentos reemplazados.
 *
 * Tras un corte, abrir la pantalla otra vez muestra las ranuras de `slots.json` que abren como
 * listas, y confirmar termina lo que faltó.
 *
 * @param open abre la base de la app, o dice que no se puede.
 * @param mutex el cerrojo del respaldo, uno por proceso.
 * @param documents lo que el respaldo usa para saber si una ranura sigue abriendo.
 * @param afterConfirm pide la primera copia si hay datos, o programa el respaldo si no.
 * @param providers las nubes aprobadas. Solo las pruebas la cambian.
 * @param checkpoint lo que corre tras cada paso de la confirmación. Solo las pruebas lo usan, para
 *   cortar ahí.
 * @param change cambia a las ranuras de un estado ya armado, desde otro destino. Nulo donde no se
 *   ofrece.
 */
internal class SlotFilesSetup(
    private val open: suspend () -> DataAccess,
    private val mutex: Mutex,
    private val slots: SlotStateStore,
    private val pending: SlotPendingStore,
    private val picked: SlotFileDocuments,
    private val documents: DocumentAccess,
    private val links: DestinationLinkStore,
    private val afterConfirm: suspend (hasData: Boolean) -> Unit,
    private val clock: Clock,
    private val ioContext: CoroutineContext,
    private val providers: List<SlotProvider> = SLOT_PROVIDERS,
    private val checkpoint: suspend (SlotConfirmStep) -> Unit = {},
    private val change: (suspend (SlotState) -> ChangeOutcome)? = null,
) : SlotFilesSetupPort {
    override suspend fun look(): SlotSetupLook? {
        val state = backupState() ?: return null
        return plan(state).toLook()
    }

    override suspend fun addFile(slot: BackupSlot, uri: String): SlotFileOutcome {
        val state = backupState() ?: return SlotFileOutcome.Unavailable
        val plan = plan(state)
        require(slot !in plan.kept) { "Esa ranura ya sirve" }
        // Otra nube o una carpeta del teléfono: ni siquiera se toma el permiso.
        val authority = Uri.parse(uri).authority
        val provider =
            slotProviderOf(authority, providers)
                ?: return SlotFileOutcome.Rejected(SlotFileRejection.OUTSIDE_PROVIDERS)
        // Las tres ranuras van en la misma nube: la que ya tiene las otras.
        val others =
            BackupSlot.entries.filter { it != slot }.mapNotNull { plan.uriOf(it, replaced = null) }
        if (others.any { Uri.parse(it).authority != authority }) {
            return SlotFileOutcome.Rejected(SlotFileRejection.OUTSIDE_PROVIDERS)
        }
        // Ese permiso lo usa otra ranura: no se toca.
        plan.uriOwner(uri, except = slot)?.let {
            return SlotFileOutcome.Rejected(SlotFileRejection.SAME_FILE, sameAs = it)
        }
        val inspected =
            withContext(ioContext) {
                if (picked.takePermission(uri)) picked.inspect(uri) else null
            }
        val rejection =
            if (inspected == null) SlotFileRejection.NO_ACCESS else rejectionOf(inspected)
        if (rejection != null) {
            release(listOf(uri))
            return SlotFileOutcome.Rejected(rejection)
        }
        val stored = pending.load()
        val previous = stored[slot]?.uri
        val entry = PendingSlotFile(uri, provider.label, inspected?.displayName)
        try {
            pending.save(stored + (slot to entry))
        } catch (_: IOException) {
            release(listOf(uri))
            return SlotFileOutcome.Unavailable
        }
        // El archivo que esta ranura tenía antes ya no se usa.
        if (previous != null && previous != uri && plan.uriOwner(previous, except = slot) == null) {
            release(listOf(previous))
        }
        return SlotFileOutcome.Accepted(plan(state).toLook())
    }

    override suspend fun confirm(): SlotSetupConfirm {
        changeIfOtherDestination()?.let { return it }
        val outcome =
            mutex.withLock {
                val state = backupState() ?: return@withLock null
                confirmLocked(state)
            } ?: return SlotSetupConfirm.Failed
        if (outcome is ConfirmOutcome.Connected) afterConfirm(outcome.hasData)
        return outcome.result
    }

    /**
     * Con otro destino guardado y los tres archivos, cambia a las ranuras. Devuelve nulo si no hay
     * otro destino, o si mientras tanto dejó de haberlo: entonces se confirma como siempre.
     */
    private suspend fun changeIfOtherDestination(): SlotSetupConfirm? {
        val switch = change ?: return null
        val dao = backupState() ?: return SlotSetupConfirm.Failed
        val plan = plan(dao)
        if (!plan.otherDestination) return null
        if (BackupSlot.entries.any { it !in plan.created }) return SlotSetupConfirm.Incomplete
        val state = plan.newState(BackupSlot.entries)
        return when (switch(state)) {
            ChangeOutcome.CHANGED -> {
                val hasData = !dao.isEmpty()
                afterConfirm(hasData)
                SlotSetupConfirm.Done(hasData)
            }

            ChangeOutcome.FAILED -> SlotSetupConfirm.Failed

            ChangeOutcome.NOT_A_CHANGE -> null
        }
    }

    private suspend fun confirmLocked(dao: BackupStateDao): ConfirmOutcome {
        val plan = plan(dao)
        if (plan.otherDestination) return ConfirmOutcome.Stopped(SlotSetupConfirm.OtherDestination)
        val replaced = BackupSlot.entries.filter { it !in plan.kept }
        if (replaced.any { it !in plan.created }) {
            return ConfirmOutcome.Stopped(SlotSetupConfirm.Incomplete)
        }
        if (replaced.isEmpty() && plan.connected) {
            // Nada cambió: solo queda terminar lo que un corte dejó atrás, sin olvidar la revisión
            // de la persona ni forzar otra subida.
            if (!finish(plan, emptyList())) {
                return ConfirmOutcome.Stopped(SlotSetupConfirm.Failed)
            }
            return ConfirmOutcome.Stopped(SlotSetupConfirm.Done(hasData = !dao.isEmpty()))
        }
        val now = clock.now().toEpochMilliseconds()
        val head = BackupSlot.fromFileRefOrNull(plan.row.headFileRef)
        if (plan.row.destinationId == DOCUMENT_SLOTS_DESTINATION_ID && head in replaced) {
            dao.forgetHeadFile(requireNotNull(head).name, now, DOCUMENT_SLOTS_DESTINATION_ID)
        }
        checkpoint(SlotConfirmStep.HEAD_FORGOTTEN)
        if (replaced.isNotEmpty()) {
            val saved =
                try {
                    slots.save(plan.newState(replaced))
                    true
                } catch (_: IOException) {
                    false
                } catch (_: IllegalArgumentException) {
                    false
                }
            if (!saved) return ConfirmOutcome.Stopped(SlotSetupConfirm.Failed)
        }
        checkpoint(SlotConfirmStep.SLOTS_SAVED)
        val connected =
            if (plan.conflictSlot != null) {
                // Conservar este teléfono con un archivo nuevo: el conflicto termina aquí.
                dao.leaveConflictWithNewFile(now, DOCUMENT_SLOTS_DESTINATION_ID) == 1
            } else {
                dao.connectDestination(
                    kind = BackupDestinationKind.DOCUMENT_SLOTS.name,
                    account = null,
                    destinationId = DOCUMENT_SLOTS_DESTINATION_ID,
                    folder = null,
                    now = now,
                ) == 1
            }
        if (!connected) return ConfirmOutcome.Stopped(SlotSetupConfirm.Failed)
        checkpoint(SlotConfirmStep.DESTINATION_CONNECTED)
        // Si `link.json` o el avance no quedan, el destino ya está conectado: repetir lo termina.
        finish(plan, replaced)
        return ConfirmOutcome.Connected(hasData = !dao.isEmpty())
    }

    /**
     * Escribe `link.json`, borra el avance y suelta los permisos de los documentos que tenían las
     * ranuras [replaced]. Devuelve `false` si `link.json` no quedó.
     */
    private suspend fun finish(plan: Plan, replaced: List<BackupSlot>): Boolean {
        val written =
            try {
                links.write(
                    DestinationLink(
                        kind = BackupDestinationKind.DOCUMENT_SLOTS,
                        account = null,
                        destinationId = DOCUMENT_SLOTS_DESTINATION_ID,
                        folderUrl = null,
                        connectedAtMillis = clock.now().toEpochMilliseconds(),
                    ),
                )
                true
            } catch (_: IOException) {
                false
            }
        if (!written) return false
        checkpoint(SlotConfirmStep.LINK_SAVED)
        pending.delete()
        val inUse = BackupSlot.entries.mapNotNull { plan.uriOf(it, replaced) }.toSet()
        release(replaced.mapNotNull { plan.current?.get(it)?.uri }.filterNot { it in inUse })
        return true
    }

    private suspend fun release(uris: List<String>) {
        if (uris.isEmpty()) return
        withContext(ioContext) { uris.distinct().forEach(picked::releasePermission) }
    }

    private suspend fun backupState(): BackupStateDao? = when (val access = open()) {
        is DataAccess.Ready -> access.ports.backupState
        is DataAccess.Rescue -> null
    }

    /**
     * Lo que hay ahora: la fila del respaldo, las ranuras de `slots.json` que siguen abriendo y los
     * archivos del avance que siguen sirviendo.
     */
    private suspend fun plan(dao: BackupStateDao): Plan {
        val row = requireNotNull(dao.get()) { "Falta la fila del respaldo" }
        val other = row.destinationId != null && row.destinationId != DOCUMENT_SLOTS_DESTINATION_ID
        val current = (slots.load() as? SlotStateLoad.Loaded)?.state
        val conflictSlot = conflictSlotOf(row).takeIf { current != null && !other }
        val kept =
            if (current == null || other) {
                emptySet()
            } else {
                BackupSlot.entries.filter { it != conflictSlot && opens(current[it]) }.toSet()
            }
        val keptUris = kept.mapNotNull { current?.get(it)?.uri }.toSet()
        val created = LinkedHashMap<BackupSlot, PendingSlotFile>()
        for ((slot, entry) in pending.load()) {
            val repeated = entry.uri in keptUris || created.values.any { it.uri == entry.uri }
            if (slot !in kept && !repeated && stillEmpty(entry)) created[slot] = entry
        }
        return Plan(row, current, kept, created, other, conflictSlot)
    }

    /**
     * La ranura que necesita un archivo nuevo para salir del conflicto, o nula si las copias no
     * están detenidas por uno así: otro teléfono cambió esa ranura, o las tres están protegidas.
     */
    private fun conflictSlotOf(row: BackupStateEntity): BackupSlot? {
        if (row.linkState != BackupLinkState.CONFLICT.name) return null
        if (row.destinationId != DOCUMENT_SLOTS_DESTINATION_ID) return null
        val kind =
            BackupConflictKind.entries.firstOrNull { it.name == row.conflictKind } ?: return null
        if (!slotConflictNeedsNewFile(kind, row.conflictGeneration)) return null
        return BackupSlot.fromFileRefOrNull(row.conflictFileRef)
    }

    /**
     * Si una ranura que ya existía sigue sirviendo: su nube sigue aprobada, la app conserva su
     * permiso y el documento responde. Si la nube pide iniciar sesión, también sirve: el archivo
     * sigue ahí, y crearlo de nuevo dejaría atrás sus copias sin necesidad. Un tamaño distinto del
     * esperado no se mira aquí: es un cambio desde otro teléfono, y lo resuelve el conflicto.
     */
    private suspend fun opens(entry: SlotEntry): Boolean {
        if (slotProviderOf(Uri.parse(entry.uri).authority, providers) == null) return false
        if (!documents.isPersisted(entry.uri)) return false
        return when (val result = documents.query(entry.uri)) {
            is DocumentResult.Ok -> true

            is DocumentResult.Error ->
                when (result.error) {
                    DocumentError.AUTHENTICATION_REQUIRED -> true

                    DocumentError.SECURITY,
                    DocumentError.NOT_FOUND_OR_UNSUPPORTED_MODE,
                    DocumentError.IO,
                    DocumentError.REJECTED,
                    -> false
                }
        }
    }

    /** Si un archivo del avance sigue sirviendo: aprobado, con su permiso y todavía con 0 bytes. */
    private suspend fun stillEmpty(entry: PendingSlotFile): Boolean {
        if (slotProviderOf(Uri.parse(entry.uri).authority, providers) == null) return false
        if (!documents.isPersisted(entry.uri)) return false
        return when (val result = documents.query(entry.uri)) {
            is DocumentResult.Ok -> result.value.sizeBytes == 0L
            is DocumentResult.Error -> false
        }
    }

    /**
     * @property row la fila del respaldo.
     * @property current las ranuras de `slots.json`, o nulas si no hay o no se lee.
     * @property kept las ranuras de [current] que siguen abriendo.
     * @property created los archivos nuevos que sirven, para las ranuras que no siguen.
     * @property otherDestination si hay otro destino guardado.
     * @property conflictSlot la ranura que necesita un archivo nuevo para salir de un conflicto.
     */
    private inner class Plan(
        val row: BackupStateEntity,
        val current: SlotState?,
        val kept: Set<BackupSlot>,
        val created: Map<BackupSlot, PendingSlotFile>,
        val otherDestination: Boolean,
        val conflictSlot: BackupSlot?,
    ) {
        /** Si las ranuras ya son el destino conectado, o detenido por un conflicto. */
        val connected: Boolean =
            row.destinationId == DOCUMENT_SLOTS_DESTINATION_ID &&
                (
                    row.linkState == BackupLinkState.CONNECTED.name ||
                        row.linkState == BackupLinkState.CONFLICT.name
                    )

        /** La ranura que ya usa [uri], salvo [except], o nula. */
        fun uriOwner(uri: String, except: BackupSlot): BackupSlot? =
            BackupSlot.entries.firstOrNull { it != except && uriOf(it, replaced = null) == uri }

        /**
         * El documento de [slot]: el de `slots.json` si sigue, o el nuevo. Con [replaced], las
         * ranuras que se reemplazan toman el nuevo.
         */
        fun uriOf(slot: BackupSlot, replaced: List<BackupSlot>?): String? =
            if (slot in kept && (replaced == null || slot !in replaced)) {
                current?.get(slot)?.uri
            } else {
                created[slot]?.uri
            }

        /**
         * `slots.json` con las ranuras que siguen como están y archivos nuevos en [replaced]. Un
         * archivo nuevo no hereda la primera entrega: la cuenta para revisar empieza de cero.
         */
        fun newState(replaced: List<BackupSlot>): SlotState {
            val entries =
                BackupSlot.entries.associateWith { slot ->
                    val kept = current?.get(slot)
                    if (slot in replaced || kept == null) {
                        val file = requireNotNull(created[slot]) { "Falta un archivo" }
                        SlotEntry(file.uri, file.providerLabel, file.displayName, null, null)
                    } else {
                        kept
                    }
                }
            val allNew = replaced.size == BackupSlot.entries.size
            return SlotState(
                slots = entries,
                firstHandoffAt = null,
                writeMode = if (allNew) WriteMode.WT else current?.writeMode ?: WriteMode.WT,
                sessionRequired = false,
            )
        }

        fun toLook(): SlotSetupLook {
            val files =
                BackupSlot.entries.map { slot ->
                    when {
                        slot in kept ->
                            SlotSetupFile(
                                slot,
                                SlotFileStatus.READY,
                                current?.get(slot)?.displayName,
                            )

                        slot in created ->
                            SlotSetupFile(slot, SlotFileStatus.CREATED, created[slot]?.displayName)

                        else -> SlotSetupFile(slot, SlotFileStatus.MISSING, null)
                    }
                }
            val label =
                kept.firstNotNullOfOrNull { current?.get(it)?.providerLabel }
                    ?: created.values.firstOrNull()?.providerLabel
                    ?: providers.first().label
            return SlotSetupLook(
                providerLabel = label,
                files = files,
                otherDestination = otherDestination,
                otherDestinationLabel =
                    row.destinationFolder?.takeIf { otherDestination }?.let(::hostOf),
                done = connected && kept.size == BackupSlot.entries.size,
                conflictSlot = conflictSlot,
                conflictFileName = conflictSlot?.let { current?.get(it)?.displayName },
            )
        }
    }

    private sealed interface ConfirmOutcome {
        val result: SlotSetupConfirm

        /** Terminó sin conectar nada nuevo, o se detuvo antes. */
        data class Stopped(override val result: SlotSetupConfirm) : ConfirmOutcome

        /** Las ranuras quedaron conectadas: falta pedir la copia, ya fuera del cerrojo. */
        data class Connected(val hasData: Boolean) : ConfirmOutcome {
            override val result: SlotSetupConfirm get() = SlotSetupConfirm.Done(hasData)
        }
    }
}

/** El servidor de una carpeta WebDAV, sin usuario, puerto, ruta ni parámetros, o nulo. */
private fun hostOf(folder: String): String? = try {
    java.net.URI(folder).host
} catch (_: java.net.URISyntaxException) {
    null
}

package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.destination.BackupDestination
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
import io.github.yesidrobayo.shopledger.data.destination.identity
import io.github.yesidrobayo.shopledger.data.destination.slots.SlotState
import io.github.yesidrobayo.shopledger.data.destination.slots.SlotStateLoad
import io.github.yesidrobayo.shopledger.data.destination.slots.SlotStateStore
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupCopyList
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupCopySummary
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationInfo
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.CopyOrigin
import io.github.yesidrobayo.shopledger.domain.model.backup.HandoffCopy
import io.github.yesidrobayo.shopledger.domain.port.BackupDestinationSource
import java.net.URI
import java.net.URISyntaxException
import kotlin.time.Instant

/**
 * Lo que la pantalla del respaldo lee del destino conectado: su nombre, sin red, y sus copias.
 *
 * - **WebDAV:** el usuario guardado y el servidor de la carpeta, y la lista que da el servicio, con
 *   la fecha del nombre de cada copia y lo que dicen sus metadatos. Sin una red comprobada no se
 *   pregunta: la lista pide internet, y la pantalla lo dice.
 * - **Ranuras:** el nombre de la nube y las tres ranuras según `slots.json`, sin red: la app no le
 *   pregunta a la otra app qué hay.
 *
 * Nunca escribe, sube, borra ni fija nada, y ningún texto del servicio sale de aquí.
 *
 * @param saved el destino conectado: de la fila del respaldo, o de `link.json` en el rescate. Nulo
 *   sin destino.
 * @param destinations abre el destino conectado, igual que para el respaldo.
 * @param slots el estado de las ranuras.
 * @param deviceId el identificador de este teléfono, para decir qué copias son suyas.
 * @param hasNetwork si hay una red que el sistema comprobó.
 */
internal class BackupCopiesReader(
    private val saved: suspend () -> SavedDestination?,
    private val destinations: DestinationOpener,
    private val slots: SlotStateStore,
    private val deviceId: suspend () -> String,
    private val hasNetwork: () -> Boolean,
) : BackupDestinationSource {
    override suspend fun describe(): BackupDestinationInfo? {
        val row = saved() ?: return null
        return when (row.kind) {
            BackupDestinationKind.WEBDAV ->
                BackupDestinationInfo.WebDav(row.account, hostOf(row.folder))

            BackupDestinationKind.DOCUMENT_SLOTS ->
                BackupDestinationInfo.Slots(loadedSlots()?.let(::providerLabelOf))

            null -> null
        }
    }

    override suspend fun listCopies(): BackupCopyList {
        val row = saved() ?: return BackupCopyList.NotConnected
        return when (row.kind) {
            BackupDestinationKind.WEBDAV -> webDavCopies(row)
            BackupDestinationKind.DOCUMENT_SLOTS -> slotCopies()
            null -> BackupCopyList.NotConnected
        }
    }

    override fun hasNetwork(): Boolean = hasNetwork.invoke()

    override suspend fun latestHandoff(): HandoffCopy? {
        val row = saved() ?: return null
        if (row.kind != BackupDestinationKind.DOCUMENT_SLOTS) return null
        val loaded = loadedSlots() ?: return null
        val latest =
            loaded.slots.values
                .mapNotNull { entry -> entry.copy?.let { entry to it } }
                .maxByOrNull { (_, record) -> record.generation } ?: return null
        val (entry, record) = latest
        return HandoffCopy(
            fileName = entry.displayName,
            sizeBytes = record.sizeBytes,
            writtenAt = Instant.fromEpochMilliseconds(record.writtenAt),
            generation = record.generation,
            documentUri = entry.uri,
            lastCheckAt = row.lastHumanCheckAt?.let(Instant::fromEpochMilliseconds),
        )
    }

    private suspend fun webDavCopies(row: SavedDestination): BackupCopyList {
        if (!hasNetwork()) return BackupCopyList.NoInternet
        val link = row.connected() ?: return BackupCopyList.NotConnected
        val destination: BackupDestination =
            when (val opened = destinations.open(link)) {
                is DestinationOpen.Opened -> opened.destination

                DestinationOpen.PermissionLost, DestinationOpen.RetryLater ->
                    return BackupCopyList.Failed
            }
        return when (val listed = destination.listCopies(TransferControl.Unbounded)) {
            is DestinationResult.Success -> {
                val own = deviceId()
                val copies = listed.value.copies.map { summaryOf(it, own) }
                BackupCopyList.Listed(newestFirst(copies))
            }

            is DestinationResult.Failure ->
                // Sin red a mitad de la lista, lo que falta es la conexión; lo demás es un fallo, y
                // nunca una lista vacía.
                if (listed.failure is DestinationFailure.Transient && !hasNetwork()) {
                    BackupCopyList.NoInternet
                } else {
                    BackupCopyList.Failed
                }
        }
    }

    private fun summaryOf(copy: ListedCopy, ownDeviceId: String): BackupCopySummary {
        val meta = metaOf(copy)
        return BackupCopySummary(
            key = copy.ref.value,
            createdAt = copy.name?.createdAt?.let { Instant.fromEpochSeconds(it.epochSeconds) },
            sizeBytes = copy.sizeBytes,
            origin = originOf(meta, ownDeviceId),
            slot = null,
            records = meta?.records,
            newest = false,
            pinned = meta?.pinned == true,
            // Un tamaño que no es el de sus metadatos tampoco cuenta para podar.
            withDifferences = meta != null && (!meta.valid || meta.fileSize != copy.sizeBytes),
            restorable = meta?.fatal != true,
        )
    }

    private fun metaOf(copy: ListedCopy): SlMeta? {
        val bytes = (copy.meta as? ListedMeta.Present)?.bytes ?: return null
        val decoded =
            copy.name?.let { SlMetaCodec.decode(bytes, it.identity()) }
                ?: SlMetaCodec.decodeUnnamed(bytes)
        return (decoded as? CopyMetadata.Valid)?.meta
    }

    private suspend fun slotCopies(): BackupCopyList {
        val loaded = loadedSlots() ?: return BackupCopyList.Failed
        val own = deviceId()
        val copies =
            loaded.slots.entries.sortedBy { it.key }.mapNotNull { (slot, entry) ->
                val record = entry.copy ?: return@mapNotNull null
                val meta =
                    (SlMetaCodec.decodeUnnamed(record.metaBytes) as? CopyMetadata.Valid)?.meta
                BackupCopySummary(
                    key = slot.name,
                    createdAt = Instant.fromEpochMilliseconds(record.writtenAt),
                    sizeBytes = record.sizeBytes,
                    origin = originOf(meta, own),
                    slot = slot,
                    records = record.facts.records,
                    newest = false,
                    // Las ranuras no se fijan: la reserva conserva la que haga falta.
                    pinned = false,
                    withDifferences = !record.facts.valid,
                    restorable = true,
                )
            }
        return BackupCopyList.Listed(newestFirst(copies))
    }

    /** De la más nueva a la más vieja, con la primera marcada; sin fecha, al final. */
    private fun newestFirst(copies: List<BackupCopySummary>): List<BackupCopySummary> {
        val sorted =
            copies.sortedWith(
                compareByDescending<BackupCopySummary> { it.createdAt }.thenBy { it.key },
            )
        return sorted.mapIndexed { index, copy ->
            if (index ==
                0
            ) {
                copy.copy(newest = true)
            } else {
                copy
            }
        }
    }

    private fun originOf(meta: SlMeta?, ownDeviceId: String): CopyOrigin = when {
        meta == null -> CopyOrigin.UNKNOWN
        meta.deviceId == ownDeviceId -> CopyOrigin.THIS_PHONE
        else -> CopyOrigin.OTHER_PHONE
    }

    private suspend fun loadedSlots(): SlotState? = (slots.load() as? SlotStateLoad.Loaded)?.state

    private fun providerLabelOf(state: SlotState): String? =
        state.slots.values.firstOrNull()?.providerLabel

    /** El servidor de la carpeta, sin usuario, puerto, ruta ni parámetros. */
    private fun hostOf(folder: String?): String? {
        if (folder == null) return null
        return try {
            URI(folder).host
        } catch (_: URISyntaxException) {
            null
        }
    }
}

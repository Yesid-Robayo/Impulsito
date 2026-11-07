package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.destination.BackupDestination
import io.github.yesidrobayo.shopledger.data.destination.BackupFileFormat
import io.github.yesidrobayo.shopledger.data.destination.BackupFileName
import io.github.yesidrobayo.shopledger.data.destination.CopyListing
import io.github.yesidrobayo.shopledger.data.destination.CopyMetadata
import io.github.yesidrobayo.shopledger.data.destination.CopyRef
import io.github.yesidrobayo.shopledger.data.destination.DestinationFailure
import io.github.yesidrobayo.shopledger.data.destination.DestinationResult
import io.github.yesidrobayo.shopledger.data.destination.ListedCopy
import io.github.yesidrobayo.shopledger.data.destination.ListedMeta
import io.github.yesidrobayo.shopledger.data.destination.OutgoingCopy
import io.github.yesidrobayo.shopledger.data.destination.PendingCopy
import io.github.yesidrobayo.shopledger.data.destination.PendingLocation
import io.github.yesidrobayo.shopledger.data.destination.SHA256_HEX
import io.github.yesidrobayo.shopledger.data.destination.SlMeta
import io.github.yesidrobayo.shopledger.data.destination.SlMetaCodec
import io.github.yesidrobayo.shopledger.data.destination.StoredCopy
import io.github.yesidrobayo.shopledger.data.destination.TransferControl
import io.github.yesidrobayo.shopledger.data.destination.UploadPlan
import io.github.yesidrobayo.shopledger.data.destination.identity
import io.github.yesidrobayo.shopledger.data.destination.newSha256
import io.github.yesidrobayo.shopledger.data.destination.toLowerHex
import io.github.yesidrobayo.shopledger.data.local.backup.BackupStateDao
import io.github.yesidrobayo.shopledger.data.local.backup.BackupStateEntity
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.CopyInstant
import io.github.yesidrobayo.shopledger.domain.model.backup.NewCopyMarks
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotCandidate
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.LogEvent
import io.github.yesidrobayo.shopledger.domain.port.LogField
import io.github.yesidrobayo.shopledger.domain.port.info
import io.github.yesidrobayo.shopledger.domain.port.warn
import java.io.File
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext

/**
 * Lo que una ejecución entrega al destino a partir del paso 10: una copia recién armada que hay que
 * subir, o una subida anterior que la recuperación retomó.
 *
 * @property generation la generación de la copia.
 * @property changeSeq el `change_seq` que trae la copia, el que la confirmación deja como
 *   verificado.
 * @property createdAtMillis desde cuándo cuentan los cambios que la copia no trae, si llegaron
 *   otros mientras tanto. Una copia retomada ya no tiene el instante de su instantánea: usa el de
 *   esta ejecución, que es posterior y nunca da por respaldado un cambio que no lo está.
 * @property marks lo que dijo el validador al crearla, sacado de sus metadatos si se retomó.
 * @property localFile el archivo del teléfono, para la lectura por muestra, o nulo si ya no está.
 */
internal sealed interface Delivery {
    val generation: Long
    val changeSeq: Long
    val createdAtMillis: Long
    val marks: NewCopyMarks
    val localFile: File?

    /** Falta subirla: una copia nueva, o una retomada que no llegó al destino. */
    class Upload(
        val copy: OutgoingCopy,
        override val generation: Long,
        override val changeSeq: Long,
        override val createdAtMillis: Long,
    ) : Delivery {
        override val marks: NewCopyMarks get() = copy.candidate.marks
        override val localFile: File get() = copy.file
    }

    /** Ya está en el destino, entera y con sus metadatos: falta comprobarla y confirmarla. */
    class Verify(
        val stored: StoredCopy,
        override val generation: Long,
        override val changeSeq: Long,
        override val createdAtMillis: Long,
        override val marks: NewCopyMarks,
        override val localFile: File?,
    ) : Delivery
}

/** Lo que decidió la recuperación del paso 5. */
internal sealed interface Recovery {
    /**
     * No hay nada que retomar, o se descartó: la ejecución sigue con la guarda y una copia nueva.
     *
     * @property rejected las generaciones rechazadas al empezar esta ejecución o durante ella: la
     *   guarda no las cuenta y la generación nueva no las repite. Incluye las que esta ejecución ya
     *   borró, porque su lista se leyó antes y todavía las muestra.
     * @property pendingGeneration la subida pendiente que queda en el estado, o nula si se limpió.
     */
    data class StartOver(val rejected: Set<Long>, val pendingGeneration: Long?) : Recovery

    /** Se retoma la subida pendiente, sin generar otra copia. */
    class Resume(val delivery: Delivery) : Recovery

    /** El destino falló al buscarla o al completarla. La intención sigue para el próximo intento. */
    data class Failed(val failure: DestinationFailure) : Recovery

    /** Falló el disco del teléfono al leer o escribir lo rechazado. Nada se borró del destino. */
    data object LocalFailure : Recovery
}

/**
 * El paso 5 del respaldo: retoma una subida que quedó sin confirmar, por su nombre en el destino, y
 * descarta lo que nunca se confirmó, sin tocar nunca la copia anterior.
 *
 * Con WebDAV, y la intención de subir la generación `G`:
 *
 * - **`G` está rechazada:** borra esa copia, limpia la intención y empieza de nuevo. Nunca la
 *   adopta, aunque tenga el tamaño y los metadatos correctos.
 * - **Está, con el tamaño esperado y sus metadatos correctos:** ya subió. Salta a comprobarla.
 * - **Está, con el tamaño esperado y sin metadatos:** sube los que esperan en la carpeta de
 *   preparación y salta a comprobarla. Si ya no están, empieza de nuevo, y esa copia queda en el
 *   destino sin metadatos: no decide ni se poda.
 * - **Está con otro tamaño, o con metadatos que no son los suyos:** la rechaza, en disco, y solo
 *   entonces la borra. Empieza de nuevo.
 * - **No está, y el archivo sigue en el teléfono:** lo sube otra vez, desde el comienzo y con el
 *   mismo archivo, sin generar otra copia.
 * - **No está, y el archivo ya no está:** empieza de nuevo.
 *
 * Antes que nada, reintenta borrar lo que siga rechazado, sin detener la copia si falla.
 *
 * Con ranuras todavía no retoma: la intención queda, y la copia nueva sube con una generación mayor.
 *
 * Solo registra códigos y generaciones, nunca un nombre de archivo.
 *
 * @param rejected las generaciones rechazadas, en `no_backup`.
 */
internal class UploadRecovery(
    private val state: BackupStateDao,
    private val staging: BackupStaging,
    private val rejected: RejectedGenerationStore,
    private val clock: Clock,
    private val log: EventLog,
    private val ioContext: CoroutineContext,
) {
    /**
     * Borra los temporales de este teléfono que quedaron de una subida cortada. Los de otro no se
     * tocan, y un borrado que falla no detiene la copia: se intenta en la próxima.
     */
    suspend fun removeOwnTemporaries(
        destination: BackupDestination,
        listing: CopyListing,
        device: String,
        control: TransferControl,
    ) {
        val prefix = device.take(DEVICE_PREFIX_LENGTH)
        for (temporary in listing.temporaries) {
            if (temporary.devicePrefix != prefix) continue
            val deleted = destination.delete(temporary.ref, control)
            if (deleted is DestinationResult.Failure) {
                log.warn(RecoveryEvent.TEMPORARY_NOT_DELETED, failureField(deleted.failure))
            }
        }
    }

    /** Decide qué hacer con la subida que el estado recuerda, si hay una. */
    suspend fun recover(
        destination: BackupDestination,
        listing: CopyListing,
        current: BackupStateEntity,
        device: String,
        control: TransferControl,
    ): Recovery {
        val before = loadRejected() ?: return Recovery.LocalFailure
        val webDav = destination.kind == BackupDestinationKind.WEBDAV
        // Solo WebDAV rechaza y borra: las ranuras no se borran nunca.
        if (webDav) retryRejected(destination, listing, device, before, control)
        val rejectedThisRun = before
        val generation = current.pendingGeneration
        if (generation == null || !webDav) {
            return Recovery.StartOver(rejectedThisRun, generation)
        }
        val intent = intentOf(current)
        if (generation in before || intent == null) {
            return startOver(rejectedThisRun, generation, RecoveryEvent.DISCARDED)
        }
        val matches = listing.copies.filter { isOwn(it, generation, device) }
        val name =
            when (matches.size) {
                0 -> return reupload(generation, intent, device, rejectedThisRun)

                1 -> requireNotNull(matches.single().name)

                // Dos copias de la misma subida no deberían existir: ninguna se confirmó, y
                // ninguna se adopta.
                else -> return reject(
                    destination,
                    generation,
                    matches.map(ListedCopy::ref),
                    rejectedThisRun,
                    control,
                )
            }
        val location =
            when (
                val located =
                    destination.locatePending(
                        PendingCopy(name, intent.sizeBytes, intent.sha256),
                        control,
                    )
            ) {
                is DestinationResult.Success -> located.value
                is DestinationResult.Failure -> return Recovery.Failed(located.failure)
            }
        return when (location) {
            PendingLocation.Absent -> reupload(generation, intent, device, rejectedThisRun)

            is PendingLocation.WrongSize ->
                reject(destination, generation, listOf(location.ref), rejectedThisRun, control)

            is PendingLocation.Uploaded ->
                adopt(destination, location, name, intent, device, rejectedThisRun, control)

            // Solo las ranuras responden así, y con ellas no se llega aquí.
            is PendingLocation.Confirmed, PendingLocation.Stale ->
                startOver(rejectedThisRun, generation, RecoveryEvent.DISCARDED)
        }
    }

    /** La intención guardada, completa y con su forma, o nula si no se puede usar. */
    private class Intent(
        val generation: Long,
        val seq: Long,
        val sha256: String,
        val sizeBytes: Long,
    )

    private fun intentOf(current: BackupStateEntity): Intent? {
        val generation = current.pendingGeneration ?: return null
        val seq = current.pendingSeq ?: return null
        val sha256 = current.pendingSha256?.takeIf(SHA256_HEX::matches) ?: return null
        val size = current.pendingSizeBytes?.takeIf { it > 0 } ?: return null
        return Intent(generation, seq, sha256, size)
    }

    /** Está en el destino con el tamaño esperado: se retoma si sus metadatos son los suyos. */
    private suspend fun adopt(
        destination: BackupDestination,
        location: PendingLocation.Uploaded,
        name: BackupFileName,
        intent: Intent,
        device: String,
        rejectedThisRun: Set<Long>,
        control: TransferControl,
    ): Recovery {
        val ref = location.copy.ref
        val stored =
            when (val meta = location.meta) {
                is ListedMeta.Present -> {
                    // Metadatos que no describen esta subida: esa copia nunca se confirmó.
                    if (checkedMeta(meta.bytes, name, intent, device) == null) {
                        return reject(
                            destination,
                            intent.generation,
                            listOf(ref),
                            rejectedThisRun,
                            control,
                        )
                    }
                    location.copy
                }

                ListedMeta.Invalid ->
                    return reject(
                        destination,
                        intent.generation,
                        listOf(ref),
                        rejectedThisRun,
                        control,
                    )

                ListedMeta.Missing -> {
                    val staged = stagedMeta(intent.generation)
                    val decoded = staged?.let { checkedMeta(it, name, intent, device) }
                    if (staged == null || decoded == null) {
                        // Sin los metadatos de esta subida, la copia queda en el destino sin
                        // ellos, y no decide ni se poda. La siguiente no repite su generación.
                        return startOver(
                            rejectedThisRun,
                            intent.generation,
                            RecoveryEvent.WITHOUT_META,
                        )
                    }
                    // Los metadatos solo usan el nombre: el archivo no se lee, y puede no estar.
                    val outgoing =
                        OutgoingCopy(
                            file = staging.compressedFile(intent.generation),
                            name = name,
                            sizeBytes = intent.sizeBytes,
                            sha256 = intent.sha256,
                            metaBytes = staged,
                            candidate = candidateOf(decoded),
                        )
                    when (val sent = destination.uploadMeta(UploadPlan(outgoing, ref), control)) {
                        is DestinationResult.Success -> sent.value
                        is DestinationResult.Failure -> return Recovery.Failed(sent.failure)
                    }
                }
            }
        val meta =
            requireNotNull(checkedMeta(stored.metaBytes, name, intent, device)) {
                "Los metadatos retomados ya se comprobaron"
            }
        log.info(RecoveryEvent.RESUMED, LogField.id("generación", intent.generation))
        return Recovery.Resume(
            Delivery.Verify(
                stored = stored,
                generation = intent.generation,
                changeSeq = intent.seq,
                createdAtMillis = clock.nowEpochMillis(),
                marks = NewCopyMarks(meta.valid, meta.fatal),
                localFile = localFileFor(intent),
            ),
        )
    }

    /**
     * No está en el destino: se sube otra vez con el mismo archivo, si sigue en el teléfono con la
     * suma de la intención. El nombre lleva el instante de este intento: la intención no guarda el
     * del primero, y el nombre solo informa.
     */
    private suspend fun reupload(
        generation: Long,
        intent: Intent,
        device: String,
        rejectedThisRun: Set<Long>,
    ): Recovery {
        val file = localFileFor(intent)
        val metaBytes = stagedMeta(generation)
        val decoded =
            (metaBytes?.let(SlMetaCodec::decodeUnnamed) as? CopyMetadata.Valid)?.meta
        val createdAt = CopyInstant.of(clock.now())
        if (file == null || metaBytes == null || decoded == null || createdAt == null ||
            decoded.format != PLAIN_FORMAT || decoded.schemaVersion > Int.MAX_VALUE
        ) {
            return startOver(rejectedThisRun, generation, RecoveryEvent.NO_LOCAL_FILE)
        }
        val name =
            BackupFileName(
                generation = generation,
                schemaVersion = decoded.schemaVersion.toInt(),
                createdAt = createdAt,
                devicePrefix = device.take(DEVICE_PREFIX_LENGTH),
                format = BackupFileFormat.GZIP,
            )
        val meta =
            checkedMeta(metaBytes, name, intent, device)
                ?: return startOver(rejectedThisRun, generation, RecoveryEvent.NO_LOCAL_FILE)
        val outgoing =
            OutgoingCopy(
                file = file,
                name = name,
                sizeBytes = intent.sizeBytes,
                sha256 = intent.sha256,
                metaBytes = metaBytes,
                candidate = candidateOf(meta),
            )
        log.info(RecoveryEvent.UPLOAD_AGAIN, LogField.id("generación", generation))
        return Recovery.Resume(
            Delivery.Upload(outgoing, generation, intent.seq, clock.nowEpochMillis()),
        )
    }

    /**
     * Descarta la copia [generation], que nunca se confirmó: primero la anota como rechazada, en
     * disco, y solo después la borra. Si el borrado falla, queda rechazada y la próxima ejecución lo
     * intenta otra vez.
     */
    private suspend fun reject(
        destination: BackupDestination,
        generation: Long,
        refs: List<CopyRef>,
        rejectedThisRun: Set<Long>,
        control: TransferControl,
    ): Recovery {
        try {
            rejected.add(generation)
        } catch (failure: IOException) {
            log.warn(RecoveryEvent.REJECTED_NOT_SAVED, failure)
            return Recovery.LocalFailure
        }
        log.warn(RecoveryEvent.REJECTED, LogField.id("generación", generation))
        if (deleteAll(destination, refs, control)) forget(generation)
        state.clearIntent()
        return Recovery.StartOver(rejectedThisRun + generation, pendingGeneration = null)
    }

    /**
     * Reintenta borrar las copias rechazadas que sigan en el destino. Una generación sale del
     * archivo cuando ya no queda nada suyo; un fallo deja la línea para la próxima ejecución.
     */
    private suspend fun retryRejected(
        destination: BackupDestination,
        listing: CopyListing,
        device: String,
        generations: Set<Long>,
        control: TransferControl,
    ) {
        for (generation in generations) {
            val refs =
                listing.copies.filter { isOwn(it, generation, device) }.map(ListedCopy::ref) +
                    listing.orphanMetas.filter { ref ->
                        BackupFileName.parseMeta(ref.value)?.let {
                            it.generation == generation &&
                                it.devicePrefix == device.take(DEVICE_PREFIX_LENGTH)
                        } == true
                    }
            if (deleteAll(destination, refs, control)) forget(generation)
        }
    }

    /** Borra cada una. Devuelve si todas quedaron borradas. */
    private suspend fun deleteAll(
        destination: BackupDestination,
        refs: List<CopyRef>,
        control: TransferControl,
    ): Boolean {
        var all = true
        for (ref in refs) {
            val deleted = destination.delete(ref, control)
            if (deleted is DestinationResult.Failure) {
                log.warn(RecoveryEvent.REJECTED_NOT_DELETED, failureField(deleted.failure))
                all = false
            }
        }
        return all
    }

    /** Quita [generation] del archivo de rechazos. Si el disco falla, sigue rechazada. */
    private suspend fun forget(generation: Long) {
        try {
            rejected.remove(generation)
        } catch (failure: IOException) {
            log.warn(RecoveryEvent.REJECTED_NOT_SAVED, failure)
        }
    }

    private suspend fun loadRejected(): Set<Long>? = try {
        rejected.load()
    } catch (failure: IOException) {
        log.warn(RecoveryEvent.REJECTED_NOT_READ, failure)
        null
    }

    private suspend fun startOver(
        rejectedThisRun: Set<Long>,
        generation: Long,
        event: RecoveryEvent,
    ): Recovery {
        log.info(event, LogField.id("generación", generation))
        state.clearIntent()
        return Recovery.StartOver(rejectedThisRun, pendingGeneration = null)
    }

    /** Los metadatos de [bytes], si son exactamente los de esta subida y de este teléfono. */
    private fun checkedMeta(
        bytes: ByteArray,
        name: BackupFileName,
        intent: Intent,
        device: String,
    ): SlMeta? {
        val meta = (SlMetaCodec.decode(bytes, name.identity()) as? CopyMetadata.Valid)?.meta
        return meta?.takeIf {
            it.deviceId == device && it.sha256 == intent.sha256 && it.fileSize == intent.sizeBytes
        }
    }

    private fun candidateOf(meta: SlMeta) =
        SlotCandidate(NewCopyMarks(meta.valid, meta.fatal), meta.records, meta.catalogCount)

    /** Los metadatos que esperan en la carpeta de preparación, si están y caben en su tope. */
    private suspend fun stagedMeta(generation: Long): ByteArray? = withContext(ioContext) {
        val file = staging.metaFile(generation)
        try {
            file.takeIf { it.isFile && it.length() <= SlMetaCodec.MAX_BYTES }?.readBytes()
        } catch (_: IOException) {
            null
        }
    }

    /**
     * El archivo de la subida, si sigue en el teléfono con el tamaño y la suma de la intención. Si
     * no, nulo: la muestra no puede compararse con un archivo que no es el que se subió.
     */
    private suspend fun localFileFor(intent: Intent): File? = withContext(ioContext) {
        val file = staging.compressedFile(intent.generation)
        if (!file.isFile || file.length() != intent.sizeBytes) return@withContext null
        try {
            val digest = newSha256()
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            file.takeIf { digest.digest().toLowerHex() == intent.sha256 }
        } catch (_: IOException) {
            null
        }
    }

    private fun failureField(failure: DestinationFailure): LogField =
        LogField.state("fallo", failureName(failure))

    private companion object {
        /** Las copias van sin cifrar hasta que exista el cifrado opcional. */
        const val PLAIN_FORMAT = 1
        const val BUFFER_BYTES = 64 * 1024
    }
}

/**
 * Si [copy] es la copia [generation] de este teléfono: por su nombre y, si sus metadatos se leen,
 * por el identificador completo. Ocho caracteres podrían repetirse en otro teléfono.
 */
private fun isOwn(copy: ListedCopy, generation: Long, device: String): Boolean {
    val name = copy.name ?: return false
    if (name.generation != generation || name.devicePrefix != device.take(DEVICE_PREFIX_LENGTH)) {
        return false
    }
    val bytes = (copy.meta as? ListedMeta.Present)?.bytes ?: return true
    val meta = (SlMetaCodec.decode(bytes, name.identity()) as? CopyMetadata.Valid)?.meta
    return meta == null || meta.deviceId == device
}

private const val DEVICE_PREFIX_LENGTH = 8

/** Los eventos de la recuperación. Solo llevan códigos y generaciones. */
internal enum class RecoveryEvent(override val text: String) : LogEvent {
    RESUMED("Subida retomada: ya estaba en el destino"),
    UPLOAD_AGAIN("Subida repetida con el mismo archivo"),
    DISCARDED("Subida pendiente descartada"),
    WITHOUT_META("Copia pendiente sin metadatos: queda sin decidir"),
    NO_LOCAL_FILE("Subida pendiente sin su archivo: se genera otra copia"),
    REJECTED("Copia sin confirmar rechazada"),
    REJECTED_NOT_DELETED("No se pudo borrar una copia rechazada: se intenta en la próxima"),
    REJECTED_NOT_SAVED("No se pudo guardar el archivo de copias rechazadas"),
    REJECTED_NOT_READ("No se pudo leer el archivo de copias rechazadas"),
    TEMPORARY_NOT_DELETED("No se pudo borrar un temporal propio: se intenta en la próxima"),
}

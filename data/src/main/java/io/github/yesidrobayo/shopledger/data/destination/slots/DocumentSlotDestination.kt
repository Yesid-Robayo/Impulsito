package io.github.yesidrobayo.shopledger.data.destination.slots

import io.github.yesidrobayo.shopledger.data.destination.BackupDestination
import io.github.yesidrobayo.shopledger.data.destination.CopyListing
import io.github.yesidrobayo.shopledger.data.destination.CopyRef
import io.github.yesidrobayo.shopledger.data.destination.DestinationFailure
import io.github.yesidrobayo.shopledger.data.destination.DestinationResult
import io.github.yesidrobayo.shopledger.data.destination.DownloadedCopy
import io.github.yesidrobayo.shopledger.data.destination.InterruptReason
import io.github.yesidrobayo.shopledger.data.destination.ListedCopy
import io.github.yesidrobayo.shopledger.data.destination.ListedMeta
import io.github.yesidrobayo.shopledger.data.destination.OutgoingCopy
import io.github.yesidrobayo.shopledger.data.destination.PendingCopy
import io.github.yesidrobayo.shopledger.data.destination.PendingLocation
import io.github.yesidrobayo.shopledger.data.destination.StoredCopy
import io.github.yesidrobayo.shopledger.data.destination.TransferControl
import io.github.yesidrobayo.shopledger.data.destination.TransientKind
import io.github.yesidrobayo.shopledger.data.destination.UnsupportedReason
import io.github.yesidrobayo.shopledger.data.destination.UploadPlan
import io.github.yesidrobayo.shopledger.data.destination.VerifiedCopy
import io.github.yesidrobayo.shopledger.data.destination.VerifyMode
import io.github.yesidrobayo.shopledger.data.destination.newSha256
import io.github.yesidrobayo.shopledger.data.destination.toLowerHex
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupSlot
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotChoice
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.rule.backup.SlotPlan
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Las copias en tres documentos de la nube del teléfono, que la app reescribe por turnos y que la
 * app de esa nube sube por su cuenta.
 *
 * Abrir un documento con `wt` lo vacía, así que el orden importa:
 *
 * 1. [planUpload] elige la ranura con la regla de turnos, sin tocar nada.
 * 2. [upload] repasa el tamaño del documento elegido, marca la ranura como a medias en el estado,
 *    de forma durable, y solo entonces abre y escribe. Si la app muere después de la marca, el
 *    estado ya no afirma la copia vieja, que el documento quizá ya no tiene.
 * 3. [verify] relee el documento por la otra app, hasta un byte de más, y compara la suma.
 * 4. [confirm] anota la copia en la ranura, antes de que el motor la confirme en la base.
 *
 * Una ranura a medias sigue así hasta que otra escritura la complete: la regla de turnos la usa
 * primero, porque ya no tiene nada que perder. Ninguna ranura se borra, y la app nunca sabe si la
 * otra app subió la copia: entregada no es confirmada.
 *
 * @param store el estado de las ranuras. Si falta o no se lee, nada se escribe.
 * @param documents el acceso a los documentos, solo a los de las ranuras.
 * @param clock el reloj, para la edad del ancla y la primera entrega.
 * @param ioContext el contexto de las lecturas y escrituras, fuera del hilo principal.
 */
internal class DocumentSlotDestination(
    private val store: SlotStateStore,
    private val documents: DocumentAccess,
    private val clock: Clock,
    private val ioContext: CoroutineContext,
) : BackupDestination {
    override val kind: BackupDestinationKind get() = BackupDestinationKind.DOCUMENT_SLOTS

    /**
     * Una sola operación a la vez sobre el estado: cada una lo lee, decide y lo reescribe entero.
     */
    private val mutex = Mutex()

    override suspend fun listCopies(control: TransferControl): DestinationResult<CopyListing> =
        mutex.withLock {
            // Solo lee: el nombre visible se refresca al escribir, que ya guarda el estado.
            val state = loadState() ?: return@withLock lost()
            for (slot in BackupSlot.entries) {
                val entry = state[slot]
                // La ranura a medias no tiene tamaño esperado: su documento pudo quedar con
                // cualquier cosa.
                val expected = entry.expectedSize ?: continue
                val info = when (val result = query(entry.uri)) {
                    is DocumentResult.Error -> return@withLock failureOf(result.error, state)
                    is DocumentResult.Ok -> result.value
                }
                checkSize(info, expected, slot)?.let {
                    return@withLock DestinationResult.Failure(it)
                }
            }
            val copies = BackupSlot.entries.mapNotNull { slot ->
                val record = state[slot].copy ?: return@mapNotNull null
                ListedCopy(
                    ref = CopyRef(slot.name),
                    name = null,
                    sizeBytes = record.sizeBytes,
                    meta = ListedMeta.Present(record.metaBytes.copyOf()),
                )
            }
            DestinationResult.Success(CopyListing(copies, emptyList(), emptyList(), null))
        }

    override suspend fun planUpload(
        copy: OutgoingCopy,
        headFileRef: CopyRef?,
    ): DestinationResult<UploadPlan> = mutex.withLock {
        val state = loadState() ?: return@withLock lost()
        val candidate = copy.candidate
        val head = BackupSlot.fromFileRefOrNull(headFileRef?.value)
        when (val choice = SlotPlan.choose(state.toTable(), candidate, clock.now(), head)) {
            is SlotChoice.Write -> DestinationResult.Success(
                UploadPlan(copy, CopyRef(choice.slot.name)),
            )

            is SlotChoice.AllLocked -> DestinationResult.Failure(
                DestinationFailure.NoSafeSlot(choice.conflictSlot?.let { CopyRef(it.name) }),
            )

            // El motor nunca pide lugar para una copia fatal, y si lo hiciera no se escribe.
            SlotChoice.FatalCopy -> DestinationResult.Failure(
                DestinationFailure.Unsupported(UnsupportedReason.INVALID_RESPONSE),
            )
        }
    }

    override suspend fun upload(
        plan: UploadPlan,
        control: TransferControl,
    ): DestinationResult<StoredCopy> = mutex.withLock {
        val slot = slotOf(plan.ref)
        val copy = plan.copy
        require(!copy.candidate.marks.fatal) { "Una copia fatal nunca se escribe en una ranura" }
        val state = loadState() ?: return@withLock lost()
        val entry = state[slot]
        stopReason(control)?.let { return@withLock interrupted(it) }
        // El documento elegido debe seguir siendo el que la app escribió: si otro lo cambió, no se
        // pisa. La ranura a medias no tiene tamaño esperado, pero su documento debe responder.
        val info = when (val result = query(entry.uri)) {
            is DocumentResult.Error -> return@withLock failureOf(result.error, state)
            is DocumentResult.Ok -> result.value
        }
        entry.expectedSize?.let { expected ->
            checkSize(info, expected, slot)?.let { return@withLock DestinationResult.Failure(it) }
        }
        val record = SlotRecord(
            generation = copy.name.generation,
            writtenAt = copy.name.createdAt.epochSeconds * MILLIS_PER_SECOND,
            sizeBytes = copy.sizeBytes,
            sha256 = copy.sha256,
            facts = SlotCopyFacts(
                copy.candidate.marks.valid,
                copy.candidate.records,
                copy.candidate.catalogCount,
            ),
            metaBytes = copy.metaBytes.copyOf(),
        )
        // Marca antes de abrir, y durable: desde que se abre, la copia anterior de esta ranura se
        // da por perdida. La marca lleva también el nombre visible recién leído, porque la persona
        // puede renombrar el documento.
        val writingEntry = entry.copy(
            displayName = info.displayName,
            copy = null,
            writing = WritingMark(record.generation, record),
        )
        var marked = state.with(slot, writingEntry)
        saveOrFail<StoredCopy>(marked)?.let { return@withLock it }
        val opened = when (val result = openForWrite(entry.uri, marked.writeMode)) {
            is OpenOutcome.Opened -> result
            is OpenOutcome.Failed -> return@withLock failureOf(result.error, marked)
            OpenOutcome.Rejected -> return@withLock unsupported()
        }
        if (opened.mode != marked.writeMode) {
            marked = marked.copy(writeMode = opened.mode)
            saveOrFail<StoredCopy>(marked)?.let {
                closeQuietly(opened.document)
                return@withLock it
            }
        }
        writeFile<StoredCopy>(copy.file, opened.document, control)?.let { return@withLock it }
        DestinationResult.Success(
            StoredCopy(plan.ref, copy.sizeBytes, copy.sha256, copy.metaBytes.copyOf()),
        )
    }

    override suspend fun uploadMeta(
        plan: UploadPlan,
        control: TransferControl,
    ): DestinationResult<StoredCopy> = notForSlots()

    override suspend fun verify(
        copy: StoredCopy,
        mode: VerifyMode,
        localFile: File?,
        control: TransferControl,
    ): DestinationResult<VerifiedCopy> = mutex.withLock {
        val state = loadState() ?: return@withLock lost()
        val entry = state[slotOf(copy.ref)]
        val read = when (
            val result = readDocument(
                entry.uri,
                copy.sizeBytes + 1,
                control,
                sink = null,
            )
        ) {
            is ReadOutcome.Done -> result
            is ReadOutcome.Failed -> return@withLock failureOf(result.error, state)
            is ReadOutcome.Stopped -> return@withLock interrupted(result.reason)
            ReadOutcome.Rejected -> return@withLock unsupported()
        }
        // Un byte de más también es distinto: un proveedor pudo no vaciar el documento al abrirlo.
        if (read.sizeBytes != copy.sizeBytes || read.sha256 != copy.sha256) {
            return@withLock DestinationResult.Failure(DestinationFailure.VerifyMismatch)
        }
        DestinationResult.Success(VerifiedCopy(fullyRead = true))
    }

    override suspend fun confirm(copy: StoredCopy): DestinationResult<Unit> = mutex.withLock {
        val state = loadState() ?: return@withLock lost()
        val slot = slotOf(copy.ref)
        val entry = state[slot]
        val meta = entry.writing?.meta
        // Solo se confirma lo que la marca describe: la misma suma y el mismo tamaño que se
        // escribieron.
        if (meta == null || meta.sha256 != copy.sha256 || meta.sizeBytes != copy.sizeBytes) {
            return@withLock DestinationResult.Failure(DestinationFailure.VerifyMismatch)
        }
        val confirmed = state
            .with(slot, entry.copy(copy = meta, writing = null))
            .copy(firstHandoffAt = state.firstHandoffAt ?: clock.nowEpochMillis())
        saveOrFail<Unit>(confirmed)?.let { return@withLock it }
        DestinationResult.Success(Unit)
    }

    override suspend fun locatePending(
        pending: PendingCopy,
        control: TransferControl,
    ): DestinationResult<PendingLocation> = mutex.withLock {
        val state = loadState() ?: return@withLock lost()
        val generation = pending.name.generation
        for (slot in BackupSlot.entries) {
            val entry = state[slot]
            val ref = CopyRef(slot.name)
            val record = entry.copy
            if (record != null && record.generation == generation &&
                record.sha256 == pending.sha256
            ) {
                val stored =
                    StoredCopy(ref, record.sizeBytes, record.sha256, record.metaBytes.copyOf())
                return@withLock DestinationResult.Success(PendingLocation.Confirmed(stored))
            }
            val writing = entry.writing ?: continue
            if (writing.generation != generation) continue
            val meta = writing.meta
            // Una marca sin datos, o con los de otro archivo, no describe esta subida: el proceso
            // murió entre la intención y la marca nueva.
            if (meta == null || meta.sha256 != pending.sha256 ||
                meta.sizeBytes != pending.sizeBytes
            ) {
                return@withLock DestinationResult.Success(PendingLocation.Stale)
            }
            val stored = StoredCopy(ref, meta.sizeBytes, meta.sha256, meta.metaBytes.copyOf())
            return@withLock DestinationResult.Success(
                PendingLocation.Uploaded(stored, ListedMeta.Present(meta.metaBytes.copyOf())),
            )
        }
        DestinationResult.Success(PendingLocation.Absent)
    }

    override suspend fun download(
        ref: CopyRef,
        sink: OutputStream,
        maxBytes: Long,
        control: TransferControl,
    ): DestinationResult<DownloadedCopy> = mutex.withLock {
        val state = loadState() ?: return@withLock lost()
        val entry = state[slotOf(ref)]
        val read = when (val result = readDocument(entry.uri, maxBytes + 1, control, sink)) {
            is ReadOutcome.Done -> result
            is ReadOutcome.Failed -> return@withLock failureOf(result.error, state)
            is ReadOutcome.Stopped -> return@withLock interrupted(result.reason)
            ReadOutcome.Rejected -> return@withLock unsupported()
        }
        if (read.sizeBytes > maxBytes) {
            return@withLock DestinationResult.Failure(
                DestinationFailure.Unsupported(UnsupportedReason.TOO_LARGE),
            )
        }
        DestinationResult.Success(DownloadedCopy(read.sizeBytes, read.sha256))
    }

    override suspend fun readMeta(
        ref: CopyRef,
        control: TransferControl,
    ): DestinationResult<ListedMeta> = mutex.withLock {
        val state = loadState() ?: return@withLock lost()
        val meta = state[slotOf(ref)].copy?.metaBytes
        DestinationResult.Success(
            meta?.let {
                ListedMeta.Present(it.copyOf())
            } ?: ListedMeta.Missing,
        )
    }

    override suspend fun delete(ref: CopyRef, control: TransferControl): DestinationResult<Unit> =
        notForSlots()

    private suspend fun loadState(): SlotState? = when (val load = store.load()) {
        is SlotStateLoad.Loaded -> load.state
        SlotStateLoad.Missing, SlotStateLoad.Unreadable -> null
    }

    /** Guarda [state], o devuelve el fallo: el estado anterior sigue en el disco. */
    private suspend fun <T> saveOrFail(state: SlotState): DestinationResult<T>? = try {
        store.save(state)
        null
    } catch (_: IOException) {
        DestinationResult.Failure(DestinationFailure.LocalStorage)
    }

    private suspend fun query(uri: String): DocumentResult<DocumentInfo> = withContext(ioContext) {
        documents.query(uri)
    }

    /** El fallo del tamaño de un documento, o nulo si es el que la app espera. */
    private fun checkSize(
        info: DocumentInfo,
        expected: Long,
        slot: BackupSlot,
    ): DestinationFailure? = when (info.sizeBytes) {
        null -> DestinationFailure.Unsupported(UnsupportedReason.PROVIDER_REJECTED)
        expected -> null
        else -> DestinationFailure.ChangedOutside(CopyRef(slot.name))
    }

    /**
     * Abre para escribir. Si el proveedor rechaza el modo, y el documento sigue con su permiso y
     * responde, reintenta una sola vez con `rwt`: un proveedor puede no admitir `wt`. Si no
     * responde, el permiso se perdió.
     */
    private suspend fun openForWrite(uri: String, mode: WriteMode): OpenOutcome =
        withContext(ioContext) {
            when (val first = documents.openForWrite(uri, mode)) {
                is DocumentResult.Ok -> OpenOutcome.Opened(first.value, mode)

                is DocumentResult.Error -> {
                    if (first.error !=
                        DocumentError.NOT_FOUND_OR_UNSUPPORTED_MODE
                    ) {
                        return@withContext OpenOutcome.Failed(first.error)
                    }
                    val stillThere =
                        documents.isPersisted(uri) && documents.query(uri) is DocumentResult.Ok
                    when {
                        !stillThere -> OpenOutcome.Failed(DocumentError.SECURITY)

                        mode == WriteMode.RWT -> OpenOutcome.Rejected

                        else -> when (val second = documents.openForWrite(uri, WriteMode.RWT)) {
                            is DocumentResult.Ok -> OpenOutcome.Opened(second.value, WriteMode.RWT)

                            is DocumentResult.Error ->
                                if (second.error == DocumentError.NOT_FOUND_OR_UNSUPPORTED_MODE) {
                                    OpenOutcome.Rejected
                                } else {
                                    OpenOutcome.Failed(second.error)
                                }
                        }
                    }
                }
            }
        }

    /** Escribe [file] en [document], lo lleva al disco si se puede y lo cierra. */
    private suspend fun <T> writeFile(
        file: File,
        document: WritableDocument,
        control: TransferControl,
    ): DestinationResult<T>? = withContext(ioContext) {
        try {
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    stopReason(control)?.let {
                        closeQuietly(document)
                        return@withContext interrupted(it)
                    }
                    val count = input.read(buffer)
                    if (count < 0) break
                    document.stream.write(buffer, 0, count)
                }
            }
            document.stream.flush()
            // Un proveedor que entrega una tubería no admite llevarla al disco: no es un fallo, y
            // la relectura decide.
            document.sync()
            document.close()
            null
        } catch (error: CancellationException) {
            closeQuietly(document)
            throw error
        } catch (_: IOException) {
            closeQuietly(document)
            DestinationResult.Failure(DestinationFailure.Transient(TransientKind.NETWORK))
        } catch (_: RuntimeException) {
            closeQuietly(document)
            unsupported()
        }
    }

    /**
     * Lee [uri] hasta [limit] bytes, calculando la suma, y copia lo leído a [sink] si hay uno. Se
     * detiene en [limit] aunque el documento siga: así un documento enorme nunca se lee entero.
     */
    private suspend fun readDocument(
        uri: String,
        limit: Long,
        control: TransferControl,
        sink: OutputStream?,
    ): ReadOutcome = withContext(ioContext) {
        val input: InputStream = when (val opened = documents.openForRead(uri)) {
            is DocumentResult.Ok -> opened.value
            is DocumentResult.Error -> return@withContext ReadOutcome.Failed(opened.error)
        }
        try {
            input.use {
                val digest = newSha256()
                val buffer = ByteArray(BUFFER_BYTES)
                var total = 0L
                while (total < limit) {
                    stopReason(control)?.let { return@withContext ReadOutcome.Stopped(it) }
                    val count = input.read(
                        buffer,
                        0,
                        minOf(buffer.size.toLong(), limit - total).toInt(),
                    )
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                    sink?.write(buffer, 0, count)
                    total += count
                }
                ReadOutcome.Done(total, digest.digest().toLowerHex())
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            ReadOutcome.Failed(DocumentError.IO)
        } catch (_: RuntimeException) {
            ReadOutcome.Rejected
        }
    }

    /**
     * El fallo que corresponde a [error]. Si la otra app pide iniciar sesión, lo anota en el
     * estado: la salida es abrir esa app, no volver a elegir los documentos.
     */
    private suspend fun <T> failureOf(
        error: DocumentError,
        state: SlotState,
    ): DestinationResult<T> = when (error) {
        DocumentError.AUTHENTICATION_REQUIRED -> {
            if (!state.sessionRequired) {
                saveOrFail<T>(state.copy(sessionRequired = true))?.let {
                    return it
                }
            }
            DestinationResult.Failure(DestinationFailure.PermissionLost(sessionRequired = true))
        }

        DocumentError.SECURITY, DocumentError.NOT_FOUND_OR_UNSUPPORTED_MODE -> lost()

        DocumentError.IO -> DestinationResult.Failure(
            DestinationFailure.Transient(TransientKind.NETWORK),
        )

        DocumentError.REJECTED -> unsupported()
    }

    private fun stopReason(control: TransferControl): InterruptReason? = when {
        control.isStopped() -> InterruptReason.STOPPED
        (control.remainingMillis() ?: Long.MAX_VALUE) <= 0 -> InterruptReason.OUT_OF_TIME
        else -> null
    }

    private fun closeQuietly(document: WritableDocument) {
        try {
            document.close()
        } catch (_: IOException) {
            // Ya se informa el fallo que llevó a cerrar.
        } catch (_: RuntimeException) {
            // Igual.
        }
    }

    private fun slotOf(ref: CopyRef): BackupSlot =
        requireNotNull(BackupSlot.fromFileRefOrNull(ref.value)) { "La referencia no es una ranura" }

    private fun <T> lost(): DestinationResult<T> =
        DestinationResult.Failure(DestinationFailure.PermissionLost())

    private fun <T> unsupported(): DestinationResult<T> = DestinationResult.Failure(
        DestinationFailure.Unsupported(UnsupportedReason.PROVIDER_REJECTED),
    )

    private fun <T> interrupted(reason: InterruptReason): DestinationResult<T> =
        DestinationResult.Failure(DestinationFailure.Interrupted(reason))

    /** Las ranuras no tienen metadatos aparte ni se borran. */
    private fun <T> notForSlots(): DestinationResult<T> = DestinationResult.Failure(
        DestinationFailure.Unsupported(UnsupportedReason.METHOD_NOT_ALLOWED),
    )

    private sealed interface OpenOutcome {
        data class Opened(val document: WritableDocument, val mode: WriteMode) : OpenOutcome

        data class Failed(val error: DocumentError) : OpenOutcome

        /** El proveedor no admite escribir el documento, ni con `rwt`. */
        data object Rejected : OpenOutcome
    }

    private sealed interface ReadOutcome {
        data class Done(val sizeBytes: Long, val sha256: String) : ReadOutcome

        data class Failed(val error: DocumentError) : ReadOutcome

        data class Stopped(val reason: InterruptReason) : ReadOutcome

        data object Rejected : ReadOutcome
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        const val MILLIS_PER_SECOND = 1_000L
    }
}

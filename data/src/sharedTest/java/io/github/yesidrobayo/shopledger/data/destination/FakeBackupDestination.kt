package io.github.yesidrobayo.shopledger.data.destination

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import java.io.File
import java.io.OutputStream

/**
 * Un destino de copias en memoria, para probar el motor de respaldo sin red ni documentos.
 *
 * Se comporta como cualquiera de los dos destinos, según [kind]:
 *
 * - **WebDAV:** una carpeta de archivos por nombre. Subir pasa por un nombre temporal y después al
 *   final sin pisar nada, y luego los metadatos al lado. Hay lista, borrado y poda.
 * - **Ranuras:** tres lugares fijos, `A`, `B` y `C`, que se reescriben. [slotChooser] hace de regla
 *   de turnos. No hay borrado: cada ranura guarda sus metadatos dentro del estado, sin archivo al
 *   lado, y confirmar la anota como comprobada.
 *
 * Cualquier operación puede fallar a pedido con [failNext], una o varias veces seguidas, y
 * [crashUploadAfter] simula una muerte del proceso en medio de una subida. [calls] anota cada
 * operación en orden. Nada de esto toca el disco salvo leer el archivo que se sube.
 */
internal class FakeBackupDestination(
    override val kind: BackupDestinationKind = BackupDestinationKind.WEBDAV,
    private val slotChooser: (
        OutgoingCopy,
        Map<CopyRef, FakeSlot>,
        CopyRef?,
    ) -> CopyRef? = ::firstFreeSlot,
) : BackupDestination {
    /** Las operaciones del destino, para inyectar fallos y revisar el orden. */
    enum class Operation {
        LIST,
        PLAN,
        UPLOAD,
        UPLOAD_META,
        VERIFY,
        CONFIRM,
        LOCATE,
        DOWNLOAD,
        READ_META,
        REPLACE_META,
        DELETE,
    }

    /** Dónde se corta una subida, como si el proceso muriera ahí. */
    enum class UploadStep {
        /** El archivo quedó con su nombre temporal. */
        AFTER_TEMPORARY_FILE,

        /** El archivo quedó con su nombre final, sin metadatos. */
        AFTER_FILE,

        /** Los metadatos quedaron con su nombre temporal. */
        AFTER_TEMPORARY_META,
    }

    /** Una ranura: su contenido, sus metadatos y si la marca dice que está a medias. */
    class FakeSlot(
        val bytes: ByteArray,
        val meta: ByteArray?,
        val writingSha256: String?,
        val confirmed: Boolean,
    )

    private val files = LinkedHashMap<String, ByteArray>()
    private val slots = LinkedHashMap<CopyRef, FakeSlot>()
    private val failures = HashMap<Operation, ArrayDeque<DestinationFailure>>()
    private val recorded = ArrayList<Operation>()

    /** Espacio libre que informa la lista, o nulo si no hay tope conocido. */
    var quotaAvailableBytes: Long? = null

    /** Si no es nulo, la próxima subida se corta en ese paso, con [InterruptReason.STOPPED]. */
    var crashUploadAfter: UploadStep? = null

    /** Las operaciones pedidas, en orden. */
    val calls: List<Operation> get() = recorded.toList()

    /** Los nombres de los archivos, en el orden en que se escribieron. Solo con WebDAV. */
    val fileNames: List<String> get() = files.keys.toList()

    /** El contenido de un archivo, o nulo si no está. */
    fun file(name: String): ByteArray? = files[name]?.copyOf()

    /** La ranura [ref], o nula si nunca se escribió. Solo con ranuras. */
    fun slot(ref: CopyRef): FakeSlot? = slots[ref]

    /** Agrega un archivo como si otro teléfono, o la persona, lo hubiera puesto ahí. */
    fun putFile(name: String, bytes: ByteArray) {
        files[name] = bytes.copyOf()
    }

    /** Quita un archivo como si la persona lo hubiera borrado en el servicio. */
    fun removeFile(name: String) {
        files.remove(name)
    }

    /** Reemplaza una ranura como si otro teléfono la hubiera escrito. */
    fun putSlot(ref: CopyRef, slot: FakeSlot) {
        slots[ref] = slot
    }

    /** Las próximas [times] llamadas a [operation] fallan con [failure]. */
    fun failNext(operation: Operation, failure: DestinationFailure, times: Int = 1) {
        val queue = failures.getOrPut(operation) { ArrayDeque() }
        repeat(times) { queue.addLast(failure) }
    }

    override suspend fun listCopies(control: TransferControl): DestinationResult<CopyListing> {
        injected(Operation.LIST)?.let { return it }
        return when (kind) {
            BackupDestinationKind.WEBDAV -> DestinationResult.Success(listFiles())
            BackupDestinationKind.DOCUMENT_SLOTS -> DestinationResult.Success(listSlots())
        }
    }

    /** La última cabeza que recibió [planUpload], para revisar que el motor la pasa. */
    var lastHeadFileRef: CopyRef? = null
        private set

    override suspend fun planUpload(
        copy: OutgoingCopy,
        headFileRef: CopyRef?,
    ): DestinationResult<UploadPlan> {
        lastHeadFileRef = headFileRef
        injected(Operation.PLAN)?.let { return it }
        return when (kind) {
            BackupDestinationKind.WEBDAV ->
                DestinationResult.Success(UploadPlan(copy, CopyRef(copy.name.fileName)))

            BackupDestinationKind.DOCUMENT_SLOTS -> {
                val ref =
                    slotChooser(copy, slots.toMap(), headFileRef)
                        ?: return DestinationResult.Failure(DestinationFailure.NoSafeSlot(null))
                DestinationResult.Success(UploadPlan(copy, ref))
            }
        }
    }

    override suspend fun upload(
        plan: UploadPlan,
        control: TransferControl,
    ): DestinationResult<StoredCopy> {
        injected(Operation.UPLOAD)?.let { return it }
        stopped(control)?.let { return it }
        val copy = plan.copy
        val bytes = copy.file.readBytes()
        return when (kind) {
            BackupDestinationKind.WEBDAV -> uploadFile(plan, bytes)

            BackupDestinationKind.DOCUMENT_SLOTS -> {
                slots[plan.ref] = FakeSlot(ByteArray(0), copy.metaBytes, copy.sha256, false)
                if (crashUploadAfter != null) return crashed()
                slots[plan.ref] = FakeSlot(bytes, copy.metaBytes, copy.sha256, false)
                DestinationResult.Success(stored(plan))
            }
        }
    }

    override suspend fun uploadMeta(
        plan: UploadPlan,
        control: TransferControl,
    ): DestinationResult<StoredCopy> {
        injected(Operation.UPLOAD_META)?.let { return it }
        if (kind != BackupDestinationKind.WEBDAV) return unsupported()
        stopped(control)?.let { return it }
        val metaName = plan.copy.name.metaFileName
        if (metaName in files) {
            return DestinationResult.Failure(DestinationFailure.Incomplete)
        }
        files[metaName] = plan.copy.metaBytes.copyOf()
        return DestinationResult.Success(stored(plan))
    }

    override suspend fun verify(
        copy: StoredCopy,
        mode: VerifyMode,
        localFile: File?,
        control: TransferControl,
    ): DestinationResult<VerifiedCopy> {
        injected(Operation.VERIFY)?.let { return it }
        require(mode != VerifyMode.SAMPLE || localFile != null) {
            "La muestra necesita el archivo local"
        }
        stopped(control)?.let { return it }
        val mismatch = DestinationResult.Failure(DestinationFailure.VerifyMismatch)
        return when (kind) {
            BackupDestinationKind.WEBDAV -> {
                val bytes = files[copy.ref.value] ?: return mismatch
                val meta = files[copy.ref.value + BackupFileName.META_SUFFIX] ?: return mismatch
                if (bytes.size.toLong() != copy.sizeBytes) return mismatch
                if (!meta.contentEquals(copy.metaBytes)) return mismatch
                when (mode) {
                    VerifyMode.FULL ->
                        if (sha256(bytes) == copy.sha256) {
                            DestinationResult.Success(VerifiedCopy(fullyRead = true))
                        } else {
                            mismatch
                        }

                    VerifyMode.SAMPLE ->
                        if (bytes.contentEquals(requireNotNull(localFile).readBytes())) {
                            DestinationResult.Success(VerifiedCopy(fullyRead = false))
                        } else {
                            mismatch
                        }

                    VerifyMode.SIZE_AND_META ->
                        DestinationResult.Success(VerifiedCopy(fullyRead = false))
                }
            }

            BackupDestinationKind.DOCUMENT_SLOTS -> {
                val slot = slots[copy.ref] ?: return mismatch
                if (slot.bytes.size.toLong() != copy.sizeBytes) return mismatch
                if (sha256(slot.bytes) != copy.sha256) return mismatch
                DestinationResult.Success(VerifiedCopy(fullyRead = true))
            }
        }
    }

    override suspend fun confirm(copy: StoredCopy): DestinationResult<Unit> {
        injected(Operation.CONFIRM)?.let { return it }
        if (kind == BackupDestinationKind.DOCUMENT_SLOTS) {
            val slot =
                slots[copy.ref]
                    ?: return DestinationResult.Failure(DestinationFailure.VerifyMismatch)
            slots[copy.ref] = FakeSlot(slot.bytes, copy.metaBytes, null, true)
        }
        return DestinationResult.Success(Unit)
    }

    override suspend fun locatePending(
        pending: PendingCopy,
        control: TransferControl,
    ): DestinationResult<PendingLocation> {
        injected(Operation.LOCATE)?.let { return it }
        return when (kind) {
            BackupDestinationKind.WEBDAV -> {
                val name = pending.name.fileName
                val bytes =
                    files[name] ?: return DestinationResult.Success(PendingLocation.Absent)
                if (bytes.size.toLong() != pending.sizeBytes) {
                    return DestinationResult.Success(PendingLocation.WrongSize(CopyRef(name)))
                }
                val meta = files[pending.name.metaFileName]
                val listed = meta?.let { ListedMeta.Present(it.copyOf()) } ?: ListedMeta.Missing
                val stored =
                    StoredCopy(
                        CopyRef(name),
                        pending.sizeBytes,
                        pending.sha256,
                        meta ?: ByteArray(0),
                    )
                DestinationResult.Success(PendingLocation.Uploaded(stored, listed))
            }

            BackupDestinationKind.DOCUMENT_SLOTS -> DestinationResult.Success(locateSlot(pending))
        }
    }

    override suspend fun download(
        ref: CopyRef,
        sink: OutputStream,
        maxBytes: Long,
        control: TransferControl,
    ): DestinationResult<DownloadedCopy> {
        injected(Operation.DOWNLOAD)?.let { return it }
        stopped(control)?.let { return it }
        val bytes =
            when (kind) {
                BackupDestinationKind.WEBDAV -> files[ref.value]
                BackupDestinationKind.DOCUMENT_SLOTS -> slots[ref]?.bytes
            } ?: return DestinationResult.Failure(DestinationFailure.VerifyMismatch)
        if (bytes.size.toLong() > maxBytes) {
            return DestinationResult.Failure(
                DestinationFailure.Unsupported(UnsupportedReason.TOO_LARGE),
            )
        }
        sink.write(bytes)
        return DestinationResult.Success(DownloadedCopy(bytes.size.toLong(), sha256(bytes)))
    }

    /**
     * Cuántos reemplazos de metadatos se cortan tras borrar los anteriores, como un servidor que
     * falla a mitad del movimiento con reemplazo.
     */
    var crashReplaceMeta: Int = 0

    override suspend fun replaceMeta(
        ref: CopyRef,
        metaBytes: ByteArray,
        control: TransferControl,
    ): DestinationResult<Unit> {
        injected(Operation.REPLACE_META)?.let { return it }
        if (kind != BackupDestinationKind.WEBDAV) return unsupported()
        val metaName = ref.value + BackupFileName.META_SUFFIX
        if (crashReplaceMeta > 0) {
            crashReplaceMeta--
            files.remove(metaName)
            files[metaName + BackupFileName.TEMPORARY_SUFFIX] = metaBytes.copyOf()
            return DestinationResult.Failure(DestinationFailure.Incomplete)
        }
        files[metaName] = metaBytes.copyOf()
        return DestinationResult.Success(Unit)
    }

    override suspend fun readMeta(
        ref: CopyRef,
        control: TransferControl,
    ): DestinationResult<ListedMeta> {
        injected(Operation.READ_META)?.let { return it }
        val meta =
            when (kind) {
                BackupDestinationKind.WEBDAV -> files[ref.value + BackupFileName.META_SUFFIX]
                BackupDestinationKind.DOCUMENT_SLOTS -> slots[ref]?.meta
            }
        return DestinationResult.Success(
            meta?.let { ListedMeta.Present(it.copyOf()) } ?: ListedMeta.Missing,
        )
    }

    override suspend fun delete(ref: CopyRef, control: TransferControl): DestinationResult<Unit> {
        injected(Operation.DELETE)?.let { return it }
        if (kind != BackupDestinationKind.WEBDAV) return unsupported()
        files.remove(ref.value)
        if (BackupFileName.parse(ref.value) != null) {
            files.remove(ref.value + BackupFileName.META_SUFFIX)
        }
        return DestinationResult.Success(Unit)
    }

    private fun uploadFile(plan: UploadPlan, bytes: ByteArray): DestinationResult<StoredCopy> {
        val name = plan.copy.name.fileName
        val metaName = plan.copy.name.metaFileName
        val temporary = name + BackupFileName.TEMPORARY_SUFFIX
        files[temporary] = bytes
        if (crashUploadAfter == UploadStep.AFTER_TEMPORARY_FILE) return crashed()
        // Nunca pisa una copia: como un MOVE sin reemplazo que encuentra el destino ocupado.
        if (name in files) return DestinationResult.Failure(DestinationFailure.Incomplete)
        files.remove(temporary)
        files[name] = bytes
        if (crashUploadAfter == UploadStep.AFTER_FILE) return crashed()
        val temporaryMeta = metaName + BackupFileName.TEMPORARY_SUFFIX
        files[temporaryMeta] = plan.copy.metaBytes.copyOf()
        if (crashUploadAfter == UploadStep.AFTER_TEMPORARY_META) return crashed()
        if (metaName in files) return DestinationResult.Failure(DestinationFailure.Incomplete)
        files.remove(temporaryMeta)
        files[metaName] = plan.copy.metaBytes.copyOf()
        return DestinationResult.Success(stored(plan))
    }

    private fun listFiles(): CopyListing {
        val copies = ArrayList<ListedCopy>()
        val orphans = ArrayList<CopyRef>()
        val temporaries = ArrayList<TemporaryFile>()
        for ((name, bytes) in files) {
            val copyName = BackupFileName.parse(name)
            val metaOf = BackupFileName.parseMeta(name)
            when {
                copyName != null -> {
                    val meta =
                        files[copyName.metaFileName]?.let { ListedMeta.Present(it.copyOf()) }
                            ?: ListedMeta.Missing
                    copies += ListedCopy(CopyRef(name), copyName, bytes.size.toLong(), meta)
                }

                metaOf != null -> if (metaOf.fileName !in files) orphans += CopyRef(name)

                name.endsWith(BackupFileName.TEMPORARY_SUFFIX) -> {
                    val base = name.removeSuffix(BackupFileName.TEMPORARY_SUFFIX)
                    val owner = BackupFileName.parse(base) ?: BackupFileName.parseMeta(base)
                    temporaries += TemporaryFile(CopyRef(name), owner?.devicePrefix)
                }
            }
        }
        return CopyListing(copies, orphans, temporaries, quotaAvailableBytes)
    }

    private fun listSlots(): CopyListing {
        val copies =
            slots
                .filter { (_, slot) -> slot.confirmed }
                .map { (ref, slot) ->
                    val meta =
                        slot.meta?.let { ListedMeta.Present(it.copyOf()) } ?: ListedMeta.Missing
                    ListedCopy(ref, null, slot.bytes.size.toLong(), meta)
                }
        return CopyListing(copies, emptyList(), emptyList(), null)
    }

    private fun locateSlot(pending: PendingCopy): PendingLocation {
        for ((ref, slot) in slots) {
            val meta = slot.meta ?: continue
            val stored = StoredCopy(ref, pending.sizeBytes, pending.sha256, meta)
            if (slot.confirmed && sha256(slot.bytes) == pending.sha256) {
                return PendingLocation.Confirmed(stored)
            }
            if (!slot.confirmed && slot.writingSha256 != null) {
                return if (slot.writingSha256 == pending.sha256) {
                    PendingLocation.Uploaded(stored, ListedMeta.Present(meta.copyOf()))
                } else {
                    PendingLocation.Stale
                }
            }
        }
        return PendingLocation.Absent
    }

    private fun stored(plan: UploadPlan) =
        StoredCopy(plan.ref, plan.copy.sizeBytes, plan.copy.sha256, plan.copy.metaBytes.copyOf())

    private fun crashed(): DestinationResult<Nothing> {
        crashUploadAfter = null
        return DestinationResult.Failure(DestinationFailure.Interrupted(InterruptReason.STOPPED))
    }

    private fun stopped(control: TransferControl): DestinationResult<Nothing>? = when {
        control.isStopped() ->
            DestinationResult.Failure(DestinationFailure.Interrupted(InterruptReason.STOPPED))

        control.remainingMillis()?.let { it <= 0 } == true ->
            DestinationResult.Failure(DestinationFailure.Interrupted(InterruptReason.OUT_OF_TIME))

        else -> null
    }

    private fun unsupported(): DestinationResult<Nothing> = DestinationResult.Failure(
        DestinationFailure.Unsupported(UnsupportedReason.METHOD_NOT_ALLOWED),
    )

    private fun injected(operation: Operation): DestinationResult<Nothing>? {
        recorded += operation
        val failure = failures[operation]?.removeFirstOrNull() ?: return null
        return DestinationResult.Failure(failure)
    }

    companion object {
        /** Las tres ranuras, en orden. */
        val SLOT_REFS = listOf(CopyRef("A"), CopyRef("B"), CopyRef("C"))

        /**
         * La regla de turnos más simple: la primera ranura que nunca se escribió, o la primera que
         * no es la cabeza. Nunca una copia fatal.
         */
        fun firstFreeSlot(
            copy: OutgoingCopy,
            slots: Map<CopyRef, FakeSlot>,
            headFileRef: CopyRef?,
        ): CopyRef? = when {
            copy.candidate.marks.fatal -> null

            else -> SLOT_REFS.firstOrNull { it !in slots }
                ?: SLOT_REFS.firstOrNull { it != headFileRef }
        }

        fun sha256(bytes: ByteArray): String = newSha256().digest(bytes).toLowerHex()
    }
}

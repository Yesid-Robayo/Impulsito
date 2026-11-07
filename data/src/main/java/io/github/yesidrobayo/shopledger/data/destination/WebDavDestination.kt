package io.github.yesidrobayo.shopledger.data.destination

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import kotlin.random.Random
import kotlinx.coroutines.delay

/**
 * Las copias en una carpeta de un servicio WebDAV, según la tabla de respuestas por método.
 *
 * - **Subir:** el archivo a `<nombre>.sltmp`, en flujo, y `MOVE` a su nombre con `Overwrite: F`.
 *   Después, lo mismo con sus metadatos. Un corte antes del `MOVE` nunca deja un nombre final.
 * - **Reintentos dentro de la ejecución:** solo ante `423`, `429`, `5xx` o una conexión caída, hasta
 *   [MAX_RETRIES] veces, esperando 1, 2 y 4 s más un poco de azar, o lo que pida `Retry-After` si
 *   cabe en el tiempo que queda. Un error de certificado no se reintenta: no se arregla en segundos.
 *   En red medida, un `PUT` que ya envió el archivo entero nunca se repite: gastaría datos otra vez.
 * - **Una carpeta que falta** se crea con `MKCOL`, y la petición se repite una vez. Si falta un
 *   ancestro, la dirección base no sirve, y no hay bucle.
 * - **Un `MOVE` sin respuesta** se repite como cualquier conexión caída. Si el repetido da `404` o
 *   `412`, quizá el primero se aplicó: se busca la copia por su nombre, y vale solo si tiene el
 *   tamaño que se subió.
 *
 * @param folder la carpeta de las copias, relativa a la dirección guardada.
 * @param sleep la espera entre reintentos. Las pruebas la reemplazan para no esperar de verdad.
 * @param jitterMillis el azar que se suma a cada espera, para que varios teléfonos no reintenten a
 *   la vez.
 */
internal class WebDavDestination(
    private val client: WebDavClient,
    private val folder: WebDavPath,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val jitterMillis: () -> Long = { Random.nextLong(MAX_JITTER_MILLIS + 1) },
) : BackupDestination {
    init {
        require(folder.name == null) { "La carpeta de copias debe ser una carpeta" }
    }

    override val kind: BackupDestinationKind = BackupDestinationKind.WEBDAV

    override suspend fun listCopies(control: TransferControl): DestinationResult<CopyListing> {
        val listing =
            when (val found = listFolder(control)) {
                is Outcome.Ok -> found.value
                is Outcome.Failed -> return found.result
            }
        if (!listing.complete) return failure(DestinationFailure.Incomplete)
        val files = listing.children.filterNot { it.isCollection }
        val sizes = files.associate { it.name to it.contentLength }
        val copies = ArrayList<ListedCopy>()
        val orphans = ArrayList<CopyRef>()
        val temporaries = ArrayList<TemporaryFile>()
        for (entry in files) {
            val name = entry.name
            val copyName = BackupFileName.parse(name)
            val metaOf = BackupFileName.parseMeta(name)
            when {
                copyName != null -> {
                    val size = entry.contentLength ?: return failure(DestinationFailure.Incomplete)
                    val metaName = copyName.metaFileName
                    val meta =
                        if (metaName !in sizes) {
                            ListedMeta.Missing
                        } else {
                            when (val read = readMetaFile(metaName, sizes[metaName], control)) {
                                is Outcome.Ok -> read.value
                                is Outcome.Failed -> return read.result
                            }
                        }
                    copies += ListedCopy(CopyRef(name), copyName, size, meta)
                }

                metaOf != null -> if (metaOf.fileName !in sizes) orphans += CopyRef(name)

                name.endsWith(BackupFileName.TEMPORARY_SUFFIX) -> {
                    val base = name.removeSuffix(BackupFileName.TEMPORARY_SUFFIX)
                    val owner = BackupFileName.parse(base) ?: BackupFileName.parseMeta(base)
                    temporaries += TemporaryFile(CopyRef(name), owner?.devicePrefix)
                }

                // Cualquier otro archivo de la carpeta no es de la app: se ignora y nunca se toca.
                else -> Unit
            }
        }
        return DestinationResult.Success(
            CopyListing(copies, orphans, temporaries, listing.quotaAvailableBytes),
        )
    }

    // Con WebDAV la cabeza no cambia el lugar: cada copia tiene su propio nombre.
    override suspend fun planUpload(
        copy: OutgoingCopy,
        headFileRef: CopyRef?,
    ): DestinationResult<UploadPlan> =
        DestinationResult.Success(UploadPlan(copy, CopyRef(copy.name.fileName)))

    override suspend fun upload(
        plan: UploadPlan,
        control: TransferControl,
    ): DestinationResult<StoredCopy> {
        val copy = plan.copy
        val name = copy.name.fileName
        when (
            val sent =
                putThenMove(name, copy.sizeBytes, control) { path ->
                    client.putFile(path, copy.file, copy.name.format.contentType, control)
                }
        ) {
            is Outcome.Ok -> Unit
            is Outcome.Failed -> return sent.result
        }
        return uploadMeta(plan, control)
    }

    override suspend fun uploadMeta(
        plan: UploadPlan,
        control: TransferControl,
    ): DestinationResult<StoredCopy> {
        val copy = plan.copy
        val metaBytes = copy.metaBytes
        when (
            val sent =
                putThenMove(copy.name.metaFileName, metaBytes.size.toLong(), control) { path ->
                    client.putBytes(path, metaBytes, META_CONTENT_TYPE, control)
                }
        ) {
            is Outcome.Ok -> Unit
            is Outcome.Failed -> return sent.result
        }
        return DestinationResult.Success(
            StoredCopy(plan.ref, copy.sizeBytes, copy.sha256, metaBytes.copyOf()),
        )
    }

    override suspend fun verify(
        copy: StoredCopy,
        mode: VerifyMode,
        localFile: File?,
        control: TransferControl,
    ): DestinationResult<VerifiedCopy> {
        require(mode != VerifyMode.SAMPLE || localFile != null) {
            "La muestra necesita el archivo local"
        }
        val name = requireCopyName(copy.ref)
        when (val size = sizeOf(name.fileName, control)) {
            is Outcome.Ok -> if (size.value != copy.sizeBytes) return mismatch()
            is Outcome.Failed -> return size.result
        }
        val fullyRead =
            when (mode) {
                VerifyMode.FULL ->
                    when (val full = readWhole(name.fileName, copy, control)) {
                        is FullRead.Matched -> true

                        is FullRead.Failed -> return full.result

                        // Sin tiempo para leer entera: la muestra, si hay con qué compararla.
                        FullRead.OutOfTime -> {
                            if (localFile == null) {
                                return failure(
                                    DestinationFailure.Interrupted(InterruptReason.OUT_OF_TIME),
                                )
                            }
                            sample(name.fileName, copy.sizeBytes, localFile, control)
                                ?.let { return it }
                            false
                        }
                    }

                VerifyMode.SAMPLE -> {
                    sample(name.fileName, copy.sizeBytes, requireNotNull(localFile), control)
                        ?.let { return it }
                    false
                }

                VerifyMode.SIZE_AND_META -> false
            }
        when (val meta = sizeOf(name.metaFileName, control)) {
            is Outcome.Ok -> if (meta.value != copy.metaBytes.size.toLong()) return mismatch()
            is Outcome.Failed -> return meta.result
        }
        return when (val meta = readMetaFile(name.metaFileName, null, control)) {
            is Outcome.Ok -> {
                val bytes = (meta.value as? ListedMeta.Present)?.bytes
                if (bytes != null && bytes.contentEquals(copy.metaBytes)) {
                    DestinationResult.Success(VerifiedCopy(fullyRead))
                } else {
                    mismatch()
                }
            }

            is Outcome.Failed -> meta.result
        }
    }

    override suspend fun confirm(copy: StoredCopy): DestinationResult<Unit> =
        DestinationResult.Success(Unit)

    override suspend fun locatePending(
        pending: PendingCopy,
        control: TransferControl,
    ): DestinationResult<PendingLocation> {
        val name = pending.name.fileName
        val size =
            when (val found = sizeOf(name, control)) {
                is Outcome.Ok -> found.value

                is Outcome.Failed ->
                    return if (found.result.failure == DestinationFailure.VerifyMismatch) {
                        DestinationResult.Success(PendingLocation.Absent)
                    } else {
                        found.result
                    }
            }
        if (size != pending.sizeBytes) {
            return DestinationResult.Success(PendingLocation.WrongSize(CopyRef(name)))
        }
        val meta =
            when (val read = readMetaFile(pending.name.metaFileName, null, control)) {
                is Outcome.Ok -> read.value
                is Outcome.Failed -> return read.result
            }
        val metaBytes = (meta as? ListedMeta.Present)?.bytes ?: ByteArray(0)
        val stored = StoredCopy(CopyRef(name), pending.sizeBytes, pending.sha256, metaBytes)
        return DestinationResult.Success(PendingLocation.Uploaded(stored, meta))
    }

    override suspend fun download(
        ref: CopyRef,
        sink: OutputStream,
        maxBytes: Long,
        control: TransferControl,
    ): DestinationResult<DownloadedCopy> {
        val name = requireCopyName(ref)
        // Un reintento después de escribir algo dejaría la descarga con bytes repetidos: solo se
        // reintenta si todavía no llegó nada.
        val counted = CountingStream(sink)
        val result =
            retryingWith(control, isFullySent = { counted.count > 0 }, alwaysStop = true) {
                client.get(folder.file(name.fileName), counted, maxBytes, control)
            }
        return when (result) {
            is GetResult.Downloaded -> DestinationResult.Success(
                DownloadedCopy(result.sizeBytes, result.sha256),
            )

            GetResult.NotFound -> mismatch()

            GetResult.TooLarge -> failure(
                DestinationFailure.Unsupported(UnsupportedReason.TOO_LARGE),
            )

            is GetResult.Failed -> failure(result.failure.toDestination())
        }
    }

    override suspend fun replaceMeta(
        ref: CopyRef,
        metaBytes: ByteArray,
        control: TransferControl,
    ): DestinationResult<Unit> {
        val name = requireCopyName(ref)
        val metaName = name.metaFileName
        val temporary = folder.file(metaName + BackupFileName.TEMPORARY_SUFFIX)
        when (
            val sent =
                retryingPut(control) {
                    client.putBytes(temporary, metaBytes, META_CONTENT_TYPE, control)
                }
        ) {
            is PutResult.Stored -> Unit

            // La copia está en su carpeta: si la carpeta falta, la copia tampoco está.
            is PutResult.MissingFolder -> return failure(DestinationFailure.Incomplete)

            is PutResult.Failed -> return failure(sent.failure.toDestination())
        }
        // El único reemplazo que hace la app: los metadatos nuevos tapan a los anteriores. Si el
        // servicio borra los anteriores y falla al mover, la copia queda sin metadatos: no cuenta
        // ni se poda, y el intento siguiente repite.
        return when (
            val moved =
                retrying(control) {
                    client.move(
                        temporary,
                        folder.file(metaName),
                        overwrite = true,
                        control = control,
                    )
                }
        ) {
            is MoveResult.Moved -> DestinationResult.Success(Unit)

            // Quien llama relee los metadatos: solo eso dice si quedaron.
            MoveResult.NotFound, MoveResult.TargetExists, MoveResult.Conflict ->
                failure(DestinationFailure.Incomplete)

            is MoveResult.Failed -> failure(moved.failure.toDestination())
        }
    }

    override suspend fun readMeta(
        ref: CopyRef,
        control: TransferControl,
    ): DestinationResult<ListedMeta> {
        val name = requireCopyName(ref)
        return when (val read = readMetaFile(name.metaFileName, null, control)) {
            is Outcome.Ok -> DestinationResult.Success(read.value)
            is Outcome.Failed -> read.result
        }
    }

    override suspend fun delete(ref: CopyRef, control: TransferControl): DestinationResult<Unit> {
        val name = ref.value
        val copyName = BackupFileName.parse(name)
        val temporaryOwner =
            name
                .takeIf { it.endsWith(BackupFileName.TEMPORARY_SUFFIX) }
                ?.removeSuffix(BackupFileName.TEMPORARY_SUFFIX)
                ?.let { BackupFileName.parse(it) ?: BackupFileName.parseMeta(it) }
        // Solo lo que la app escribe: copias, metadatos y temporales con el nombre exacto.
        require(
            copyName != null || BackupFileName.parseMeta(name) != null || temporaryOwner != null,
        ) {
            "Solo se borran archivos de copia"
        }
        deleteFile(name, control)?.let { return it }
        // Primero la copia y después sus metadatos: un corte entre los dos deja unos metadatos
        // sin copia, que la lista reconoce y la poda siguiente borra.
        if (copyName != null) deleteFile(copyName.metaFileName, control)?.let { return it }
        return DestinationResult.Success(Unit)
    }

    private suspend fun deleteFile(
        name: String,
        control: TransferControl,
    ): DestinationResult<Nothing>? =
        when (val result = retrying(control) { client.delete(folder.file(name), control) }) {
            is DeleteResult.Deleted -> null
            is DeleteResult.Failed -> failure(result.failure.toDestination())
        }

    /**
     * Sube a `<name>.sltmp` y lo pasa a [name] sin pisar nada. Si falta la carpeta, la crea y repite
     * una vez.
     */
    private suspend fun putThenMove(
        name: String,
        expectedSize: Long,
        control: TransferControl,
        put: suspend (WebDavPath) -> PutResult,
    ): Outcome<Unit> {
        val temporary = folder.file(name + BackupFileName.TEMPORARY_SUFFIX)
        var createdFolder = false
        while (true) {
            when (val result = retryingPut(control) { put(temporary) }) {
                is PutResult.Stored -> break

                is PutResult.MissingFolder -> {
                    if (createdFolder) return Outcome.failed(UnsupportedReason.BASE_URL_INVALID)
                    createFolder(control)?.let { return Outcome.Failed(it) }
                    createdFolder = true
                    // En red medida no se repite la subida dentro de esta ejecución.
                    if (control.isMetered()) {
                        return Outcome.Failed(
                            failure(DestinationFailure.Interrupted(InterruptReason.METERED)),
                        )
                    }
                }

                is PutResult.Failed -> return Outcome.Failed(
                    failure(result.failure.toDestination()),
                )
            }
        }
        var retriedConflict = false
        while (true) {
            when (
                val moved =
                    retrying(control) {
                        client.move(temporary, folder.file(name), control = control)
                    }
            ) {
                is MoveResult.Moved -> return Outcome.Ok(Unit)

                // Quizá un MOVE anterior se aplicó y se perdió su respuesta: vale si está con el
                // tamaño que se subió. Si no, la recuperación por nombre decide en la próxima.
                MoveResult.NotFound, MoveResult.TargetExists ->
                    return when (val size = sizeOf(name, control)) {
                        is Outcome.Ok ->
                            if (size.value == expectedSize) {
                                Outcome.Ok(Unit)
                            } else {
                                Outcome.Failed(failure(DestinationFailure.Incomplete))
                            }

                        is Outcome.Failed ->
                            if (size.result.failure == DestinationFailure.VerifyMismatch) {
                                Outcome.Failed(failure(DestinationFailure.Incomplete))
                            } else {
                                size
                            }
                    }

                MoveResult.Conflict -> {
                    if (retriedConflict) return Outcome.failed(UnsupportedReason.UNEXPECTED_STATUS)
                    createFolder(control)?.let { return Outcome.Failed(it) }
                    retriedConflict = true
                }

                is MoveResult.Failed -> return Outcome.Failed(
                    failure(moved.failure.toDestination()),
                )
            }
        }
    }

    private suspend fun createFolder(control: TransferControl): DestinationResult.Failure? =
        when (val made = retrying(control) { client.mkcol(folder, control) }) {
            MkcolResult.Created, MkcolResult.AlreadyExists -> null

            MkcolResult.MissingAncestor -> failure(
                DestinationFailure.Unsupported(UnsupportedReason.BASE_URL_INVALID),
            )

            is MkcolResult.Failed -> failure(made.failure.toDestination())
        }

    /** La carpeta en su primer nivel. Si no existe, la crea y la da vacía. */
    private suspend fun listFolder(control: TransferControl): Outcome<WebDavListing> {
        when (
            val found = retrying(control) {
                client.propfind(folder, WebDavClient.Depth.ONE, control)
            }
        ) {
            is PropfindResult.Listed -> return Outcome.Ok(found.listing)

            is PropfindResult.Failed -> return Outcome.Failed(
                failure(found.failure.toDestination()),
            )

            PropfindResult.NotFound -> Unit
        }
        createFolder(control)?.let { return Outcome.Failed(it) }
        return Outcome.Ok(
            WebDavListing(
                self = null,
                children = emptyList(),
                complete = true,
                quotaAvailableBytes = null,
            ),
        )
    }

    /**
     * El tamaño de [name] con un `PROPFIND` de profundidad 0. Si no está, el resultado es
     * [DestinationFailure.VerifyMismatch]: no está lo que se subió.
     */
    private suspend fun sizeOf(name: String, control: TransferControl): Outcome<Long> = when (
        val found =
            retrying(control) {
                client.propfind(folder.file(name), WebDavClient.Depth.ZERO, control)
            }
    ) {
        is PropfindResult.Listed -> {
            val self = found.listing.self
            when {
                !found.listing.complete || self == null -> Outcome.Failed(
                    failure(DestinationFailure.Incomplete),
                )

                self.isCollection -> Outcome.Failed(mismatch())

                else -> self.contentLength?.let { Outcome.Ok(it) }
                    ?: Outcome.Failed(failure(DestinationFailure.Incomplete))
            }
        }

        PropfindResult.NotFound -> Outcome.Failed(mismatch())

        is PropfindResult.Failed -> Outcome.Failed(failure(found.failure.toDestination()))
    }

    /**
     * Los metadatos de una copia, hasta [MAX_META_BYTES]. [listedSize] es lo que dijo la lista: si
     * ya pasa del tope, ni se piden.
     */
    private suspend fun readMetaFile(
        name: String,
        listedSize: Long?,
        control: TransferControl,
    ): Outcome<ListedMeta> {
        if (listedSize != null && listedSize > MAX_META_BYTES) return Outcome.Ok(ListedMeta.Invalid)
        val sink = ByteArrayOutputStream()
        return when (
            val got =
                retrying(control) {
                    sink.reset()
                    client.get(folder.file(name), sink, MAX_META_BYTES, control)
                }
        ) {
            is GetResult.Downloaded -> Outcome.Ok(ListedMeta.Present(sink.toByteArray()))

            GetResult.NotFound -> Outcome.Ok(ListedMeta.Missing)

            GetResult.TooLarge -> Outcome.Ok(ListedMeta.Invalid)

            is GetResult.Failed ->
                when (val failure = got.failure.toDestination()) {
                    // Ni la credencial, ni una respuesta inválida, ni detenerse cambian de clase.
                    is DestinationFailure.PermissionLost,
                    is DestinationFailure.Transient,
                    is DestinationFailure.Interrupted,
                    -> Outcome.Failed(failure(failure))

                    is DestinationFailure.Unsupported ->
                        if (failure.reason == UnsupportedReason.INVALID_RESPONSE) {
                            Outcome.Failed(failure(failure))
                        } else {
                            // Cualquier otro error al leer metadatos deja la lista incompleta.
                            Outcome.Failed(failure(DestinationFailure.Incomplete))
                        }

                    else -> Outcome.Failed(failure(DestinationFailure.Incomplete))
                }
        }
    }

    private sealed interface FullRead {
        data object Matched : FullRead

        data object OutOfTime : FullRead

        data class Failed(val result: DestinationResult.Failure) : FullRead
    }

    /**
     * Baja la copia entera, sin guardarla, y compara su suma. Deja [FULL_READ_RESERVE_MILLIS] del
     * tiempo de la ejecución sin usar: si la lectura completa no alcanza, ese resto sirve para la
     * muestra.
     */
    private suspend fun readWhole(
        name: String,
        copy: StoredCopy,
        control: TransferControl,
    ): FullRead {
        val reserved = ReservedTime(control, FULL_READ_RESERVE_MILLIS)
        val result =
            retrying(reserved) {
                client.get(folder.file(name), DiscardingStream, copy.sizeBytes, reserved)
            }
        return when (result) {
            is GetResult.Downloaded ->
                if (result.sizeBytes == copy.sizeBytes && result.sha256 == copy.sha256) {
                    FullRead.Matched
                } else {
                    FullRead.Failed(mismatch())
                }

            // Un byte de más, o la copia que ya no está, también es no coincidir.
            GetResult.NotFound, GetResult.TooLarge -> FullRead.Failed(mismatch())

            is GetResult.Failed -> {
                val failure = result.failure
                if (failure is WebDavFailure.Interrupted &&
                    failure.reason == InterruptReason.OUT_OF_TIME
                ) {
                    FullRead.OutOfTime
                } else {
                    FullRead.Failed(failure(failure.toDestination()))
                }
            }
        }
    }

    /**
     * Compara el comienzo y el final de la copia con [localFile], en dos `GET` separados. Si el
     * servicio ignora `Range`, solo el comienzo: el segundo traería otra vez lo mismo. Devuelve nulo
     * si coincide.
     */
    private suspend fun sample(
        name: String,
        size: Long,
        localFile: File,
        control: TransferControl,
    ): DestinationResult.Failure? {
        if (size == 0L) return null
        val span = minOf(SAMPLE_BYTES, size)
        val ranges = if (size <= SAMPLE_BYTES) listOf(0L) else listOf(0L, size - span)
        for (first in ranges) {
            val last = first + span - 1
            val expected = readLocal(localFile, first, span.toInt()) ?: return mismatch()
            val result =
                retrying(control) { client.getRange(folder.file(name), first, last, size, control) }
            when (result) {
                is RangeResult.Partial -> if (!result.bytes.contentEquals(
                        expected,
                    )
                ) {
                    return mismatch()
                }

                is RangeResult.Ignored -> {
                    // El servidor mandó el archivo desde el comienzo: solo vale para el comienzo.
                    val head = readLocal(localFile, 0, span.toInt()) ?: return mismatch()
                    return if (result.bytes.contentEquals(head)) null else mismatch()
                }

                RangeResult.NotFound -> return mismatch()

                is RangeResult.Failed -> return failure(result.failure.toDestination())
            }
        }
        return null
    }

    private fun readLocal(file: File, offset: Long, length: Int): ByteArray? {
        if (file.length() < offset + length) return null
        return RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            ByteArray(length).also { raf.readFully(it) }
        }
    }

    /**
     * Repite [call] ante un fallo pasajero, con esperas crecientes, mientras quede tiempo. Antes de
     * cada intento revisa si debe detenerse.
     */
    private suspend fun <R> retrying(control: TransferControl, call: suspend () -> R): R =
        retryingWith(control, isFullySent = { false }, call = call)

    /**
     * Igual que [retrying], pero en red medida no repite un `PUT` que ya envió el cuerpo entero.
     */
    private suspend fun retryingPut(
        control: TransferControl,
        call: suspend () -> PutResult,
    ): PutResult = retryingWith(
        control,
        isFullySent = { (it as? PutResult.Failed)?.bodySent == true },
        call = call,
    )

    /**
     * @param isFullySent si el intento fallido ya gastó lo que no conviene gastar dos veces. En red
     *   medida, o siempre con [alwaysStop], eso corta los reintentos.
     */
    private suspend fun <R> retryingWith(
        control: TransferControl,
        isFullySent: (R) -> Boolean,
        alwaysStop: Boolean = false,
        call: suspend () -> R,
    ): R {
        var attempt = 0
        while (true) {
            // El cliente revisa si debe detenerse antes de enviar: una detención vuelve como fallo,
            // y no se reintenta.
            val result = call()
            val wait = retryDelay(failureOf(result), attempt) ?: return result
            if ((alwaysStop || control.isMetered()) && isFullySent(result)) return result
            val remaining = control.remainingMillis()
            if (remaining != null && wait >= remaining) return result
            sleep(wait)
            attempt++
        }
    }

    /** Lo que espera antes del siguiente intento, o nulo si no se reintenta. */
    private fun retryDelay(failure: WebDavFailure?, attempt: Int): Long? {
        if (attempt >= MAX_RETRIES) return null
        val retryAfterSeconds =
            when (failure) {
                is WebDavFailure.Transient -> failure.retryAfterSeconds

                // Un certificado que no se pudo comprobar no se arregla en segundos.
                is WebDavFailure.Unreachable ->
                    if (failure.kind == TransportFailure.TLS) return null else null

                else -> return null
            }
        val backoff = (BASE_DELAY_MILLIS shl attempt) + jitterMillis()
        return retryAfterSeconds?.let { maxOf(it * MILLIS_PER_SECOND, backoff) } ?: backoff
    }

    private fun failureOf(result: Any?): WebDavFailure? = when (result) {
        is PropfindResult.Failed -> result.failure
        is MkcolResult.Failed -> result.failure
        is PutResult.Failed -> result.failure
        is MoveResult.Failed -> result.failure
        is GetResult.Failed -> result.failure
        is RangeResult.Failed -> result.failure
        is DeleteResult.Failed -> result.failure
        else -> null
    }

    private fun requireCopyName(ref: CopyRef): BackupFileName =
        requireNotNull(BackupFileName.parse(ref.value)) { "La referencia no es una copia" }

    private fun mismatch(): DestinationResult.Failure = failure(DestinationFailure.VerifyMismatch)

    private fun failure(failure: DestinationFailure) = DestinationResult.Failure(failure)

    private sealed interface Outcome<out T> {
        data class Ok<T>(val value: T) : Outcome<T>

        data class Failed(val result: DestinationResult.Failure) : Outcome<Nothing>

        companion object {
            fun failed(reason: UnsupportedReason) =
                Failed(DestinationResult.Failure(DestinationFailure.Unsupported(reason)))
        }
    }

    /** [control], con [reserveMillis] menos de tiempo. */
    private class ReservedTime(
        private val control: TransferControl,
        private val reserveMillis: Long,
    ) : TransferControl by control {
        override fun remainingMillis(): Long? = control.remainingMillis()?.let {
            it - reserveMillis
        }
    }

    /** Cuenta lo que pasa hacia [out]. */
    private class CountingStream(private val out: OutputStream) : OutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }
    }

    /** Un destino de descarga que no guarda nada: la lectura completa solo necesita la suma. */
    private object DiscardingStream : OutputStream() {
        override fun write(b: Int) = Unit

        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }

    companion object {
        /** Reintentos dentro de una ejecución, tras el primer intento. */
        const val MAX_RETRIES = 3

        /** La primera espera. Las siguientes la duplican: 1, 2 y 4 s. */
        const val BASE_DELAY_MILLIS = 1_000L

        /** El azar que se suma a cada espera, como máximo. */
        const val MAX_JITTER_MILLIS = 500L

        /** Tope de los metadatos de una copia, contados al leer. */
        const val MAX_META_BYTES = 16L * 1024

        /** El tiempo que la lectura completa deja para la muestra, si no le alcanza. */
        const val FULL_READ_RESERVE_MILLIS = 15_000L

        /** Lo que compara la muestra en cada extremo. */
        const val SAMPLE_BYTES = 64L * 1024

        const val META_CONTENT_TYPE = "application/json; charset=utf-8"

        private const val MILLIS_PER_SECOND = 1_000L
    }
}

/** Traduce el fallo de una petición a lo que el motor hace con él. */
internal fun WebDavFailure.toDestination(): DestinationFailure = when (this) {
    is WebDavFailure.PermissionLost -> DestinationFailure.PermissionLost()

    is WebDavFailure.Unsupported -> DestinationFailure.Unsupported(reason)

    is WebDavFailure.QuotaExceeded -> DestinationFailure.QuotaExceeded

    is WebDavFailure.Transient ->
        DestinationFailure.Transient(
            if (status == 423 ||
                status == 429
            ) {
                TransientKind.RATE_LIMIT
            } else {
                TransientKind.SERVER_ERROR
            },
        )

    is WebDavFailure.Unreachable ->
        DestinationFailure.Transient(
            when (kind) {
                TransportFailure.TLS -> TransientKind.TLS
                TransportFailure.NETWORK, TransportFailure.TIMEOUT -> TransientKind.NETWORK
            },
        )

    is WebDavFailure.Interrupted -> DestinationFailure.Interrupted(reason)
}

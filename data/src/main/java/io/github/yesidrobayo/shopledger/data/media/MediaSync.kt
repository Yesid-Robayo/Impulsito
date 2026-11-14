package io.github.yesidrobayo.shopledger.data.media

import android.graphics.BitmapFactory
import io.github.yesidrobayo.shopledger.data.backup.PackEncryption
import io.github.yesidrobayo.shopledger.data.crypto.BackupHeader
import io.github.yesidrobayo.shopledger.data.crypto.BackupKey
import io.github.yesidrobayo.shopledger.data.crypto.DurableFile
import io.github.yesidrobayo.shopledger.data.crypto.EncryptedCopy
import io.github.yesidrobayo.shopledger.data.crypto.EncryptedReadResult
import io.github.yesidrobayo.shopledger.data.destination.BackupDestination
import io.github.yesidrobayo.shopledger.data.destination.ConnectedDestination
import io.github.yesidrobayo.shopledger.data.destination.DestinationFailure
import io.github.yesidrobayo.shopledger.data.destination.DestinationResult
import io.github.yesidrobayo.shopledger.data.destination.TransferControl
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.catalog.StoredImage
import io.github.yesidrobayo.shopledger.domain.port.EventLog
import io.github.yesidrobayo.shopledger.domain.port.LogEvent
import io.github.yesidrobayo.shopledger.domain.port.LogField
import io.github.yesidrobayo.shopledger.domain.port.info
import io.github.yesidrobayo.shopledger.domain.port.warn
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.sync.Mutex

/**
 * El canal de las fotos, `media-sync`. En cada ejecución, en este orden:
 *
 * 1. **Borra del servicio** las fotos que la limpieza ya borró del teléfono, con su `.slmeta`.
 * 2. **Baja las fotos en uso que faltan en el teléfono,** como tras restaurar en otro teléfono. Cada
 *    una se lee hasta 2 MiB, se descifra si está cifrada, y solo queda si su suma es su nombre y se
 *    decodifica como imagen. Si el servicio no la tiene, o la tiene dañada, queda anotada como no
 *    disponible: la pantalla dice `Foto no disponible`, sin error. Restaurar nunca espera a esto.
 * 3. **Sube las fotos en uso** que el servicio todavía no tiene en la forma vigente, una a una, y
 *    verifica cada una bajándola antes de darla por guardada.
 *
 * Reglas:
 *
 * - **Solo con WebDAV.** Con ranuras las fotos no salen del teléfono.
 * - **Cede siempre a la copia de la base.** Toma el cerrojo del respaldo para cada foto, sin
 *   esperarlo, y lo suelta entre una y otra: una copia que llega espera a lo sumo una foto, de
 *   300 KiB como mucho. Si el cerrojo está tomado, sale: el respaldo vuelve a encolarla al terminar.
 * - **Solo con red no medida,** salvo que la persona permita fotos con datos móviles.
 * - **Se detiene si en el servicio caben menos de 3 copias** de la base, y lo anota para decirlo:
 *   las fotos nunca desplazan a la base ni empujan a pagar más espacio. Sin tope informado, sigue.
 *   Bajar y borrar no ocupan espacio, y siguen.
 * - **Con el cifrado activo, nada sube en claro.** Cada foto sube como `.slmd`, con la clave y el
 *   formato de las copias cifradas. Sin la clave no sube nada. Al activar, cambiar el código o
 *   apagar el cifrado, cada foto sube otra vez en la forma nueva, se verifica y solo entonces se
 *   borra la anterior.
 * - **No toca la base ni el estado del respaldo:** ni `change_seq`, ni `verified_seq`, ni nada que
 *   la pantalla del respaldo cuente como copia.
 *
 * Solo registra conteos y códigos: nunca una suma, un nombre ni una dirección.
 *
 * @param prepare abre la base y mira las precondiciones, como la sonda de la cabeza.
 * @param open abre el destino conectado y su canal de fotos.
 * @param mediaDirectory la carpeta local de las fotos.
 * @param mutex el cerrojo del respaldo, el mismo de cada copia y de cada restauración.
 * @param encryption si las fotos salen cifradas y con qué código, igual que las copias.
 * @param deviceId el identificador de la instalación, para la cabecera de una foto cifrada.
 * @param isImage si unos bytes bajados se decodifican como imagen.
 * @param nowUtcMillis el instante de la cabecera de una foto cifrada.
 */
internal class MediaSync(
    private val prepare: suspend () -> MediaSyncAccess,
    private val open: suspend (ConnectedDestination) -> MediaOpen,
    private val store: suspend () -> MediaSyncStore,
    private val mediaDirectory: () -> File,
    private val mutex: Mutex,
    private val log: EventLog,
    private val encryption: suspend () -> PackEncryption = { PackEncryption.Plain },
    private val deviceId: suspend () -> String = { UUID(0, 0).toString() },
    private val isImage: (ByteArray) -> Boolean = ::decodesAsImage,
    private val nowUtcMillis: () -> Long = System::currentTimeMillis,
) {
    /** Corre una vez. */
    suspend fun run(control: TransferControl): MediaSyncRun {
        val ready =
            when (val access = prepare()) {
                is MediaSyncAccess.Ready -> access
                MediaSyncAccess.Waiting -> return MediaSyncRun.RETRY
                MediaSyncAccess.Nothing -> return MediaSyncRun.DONE
            }
        if (ready.link.kind != BackupDestinationKind.WEBDAV) return MediaSyncRun.DONE
        if (control.isMetered() && !ready.mediaOnMetered) return MediaSyncRun.DONE
        val directory = mediaDirectory()
        val (local, missing) =
            ready.photos.partition { File(directory, StoredImage.fileNameOf(it.sha256)).isFile }
        val states = store()
        val inUse = ready.photos.mapTo(HashSet()) { it.sha256 }
        val deletions = states.deletions().filterNotTo(HashSet()) { it in inUse }
        if (local.isEmpty() && missing.isEmpty() && deletions.isEmpty()) return MediaSyncRun.DONE
        val mode =
            when (val chosen = encryption()) {
                PackEncryption.Plain -> Mode(MediaForm.Plain, null)

                is PackEncryption.Encrypt -> {
                    val key = BackupKey.from(chosen.code)
                    Mode(MediaForm.Sealed(key.keyIdHex), key)
                }

                // Con el cifrado activo y sin la clave, nada sube: nunca una foto en claro.
                PackEncryption.KeyUnavailable -> Mode(null, null)

                PackEncryption.RetryLater -> return MediaSyncRun.RETRY
            }
        val start = locked { start(ready.link, states, control) } ?: return MediaSyncRun.DONE
        val session =
            when (start) {
                is Start.Go -> start.session
                is Start.Finished -> return start.run
            }
        val tally = Tally()
        try {
            removeDeleted(session, states, deletions, control)?.let { return it }
            fetchMissing(session, states, missing, directory, mode, tally, control)
                ?.let { return it }
            if (mode.form == null) {
                if (local.any { it.sha256 !in session.verified }) log.warn(MediaSyncEvent.NO_KEY)
                return MediaSyncRun.DONE
            }
            if (!session.uploadsAllowed) return MediaSyncRun.DONE
            return sendLocal(session, states, local, directory, mode, tally, control)
        } finally {
            log.info(
                MediaSyncEvent.SYNCED,
                LogField.count("subidas", tally.uploaded.toLong()),
                LogField.count("bajadas", tally.downloaded.toLong()),
                LogField.count("no disponibles", tally.unavailable.toLong()),
            )
        }
    }

    /** Corre [block] con el cerrojo del respaldo, o devuelve `null` si estaba tomado. */
    private suspend fun <T> locked(block: suspend () -> T): T? {
        if (!mutex.tryLock()) {
            log.info(MediaSyncEvent.BACKUP_BUSY)
            return null
        }
        return try {
            block()
        } finally {
            mutex.unlock()
        }
    }

    /** Abre el destino, mira el espacio y sabe qué fotos ya están verificadas y cómo. */
    private suspend fun start(
        link: ConnectedDestination,
        states: MediaSyncStore,
        control: TransferControl,
    ): Start {
        val opened =
            when (val result = open(link)) {
                is MediaOpen.Opened -> result
                MediaOpen.Unavailable -> return Start.Finished(MediaSyncRun.DONE)
                MediaOpen.RetryLater -> return Start.Finished(MediaSyncRun.RETRY)
            }
        val copies =
            when (val listed = opened.destination.listCopies(control)) {
                is DestinationResult.Success -> listed.value

                is DestinationResult.Failure -> return Start.Finished(
                    finish(listed.failure, states),
                )
            }
        val media =
            when (val listed = opened.media.list(control)) {
                is DestinationResult.Success -> listed.value

                is DestinationResult.Failure -> return Start.Finished(
                    finish(listed.failure, states),
                )
            }
        val copyBytes = copies.copies.maxOfOrNull { it.sizeBytes }
        val available = media.quotaAvailableBytes ?: copies.quotaAvailableBytes
        val uploadsAllowed = !lacksSpace(available, copyBytes)
        if (uploadsAllowed) {
            states.setStop(null)
        } else {
            states.setStop(MediaSyncStop.SERVICE_SPACE)
            log.warn(MediaSyncEvent.SERVICE_SPACE)
        }
        val verified =
            states.verifiedForms()
                ?: media.plainSums().associateWith<String, MediaForm> { MediaForm.Plain }
                    .also { states.saveVerifiedForms(it) }
        return Start.Go(Session(opened.media, media, verified.toMutableMap(), uploadsAllowed))
    }

    /** Paso 1: borra del servicio lo que la limpieza borró del teléfono. */
    private suspend fun removeDeleted(
        session: Session,
        states: MediaSyncStore,
        deletions: Set<String>,
        control: TransferControl,
    ): MediaSyncRun? {
        // Lo que volvió a usarse antes de borrarse del servicio ya no se borra.
        val pending = deletions.toHashSet()
        if (pending != states.deletions()) states.saveDeletions(pending)
        for (sha in deletions) {
            if (control.isStopped()) return MediaSyncRun.RETRY
            val names = session.listing.namesOf(sha) + temporaries(session, sha)
            val removal = locked { Removal(removeAll(session, names)) } ?: return MediaSyncRun.DONE
            removal.failure?.let { return finish(it, states) }
            pending -= sha
            session.verified -= sha
            states.saveDeletions(pending)
            states.saveVerifiedForms(session.verified)
        }
        return null
    }

    /** Los temporales de [sha] que quedaron de una subida cortada. */
    private fun temporaries(session: Session, sha: String): List<String> =
        listOf(MediaNames.photo(sha), MediaNames.sealed(sha))
            .map(MediaNames::temporaryOf)
            .filter { it in session.listing.files }

    /** Borra cada archivo de [names] con su `.slmeta`. Devuelve la falla, o `null`. */
    private suspend fun removeAll(session: Session, names: List<String>): DestinationFailure? {
        for (name in names) {
            for (target in listOf(name, MediaNames.metaOf(name))) {
                if (target !in session.listing.files) continue
                val deleted = session.channel.delete(target, TransferControl.Unbounded)
                if (deleted is DestinationResult.Failure) return deleted.failure
            }
        }
        return null
    }

    /** Paso 2: baja las fotos en uso que faltan en el teléfono. */
    private suspend fun fetchMissing(
        session: Session,
        states: MediaSyncStore,
        missing: List<StoredImage>,
        directory: File,
        mode: Mode,
        tally: Tally,
        control: TransferControl,
    ): MediaSyncRun? {
        val wanted = missing.mapTo(HashSet()) { it.sha256 }
        val unavailable = states.unavailable().filterTo(HashSet()) { it in wanted }
        try {
            for (photo in missing) {
                if (control.isStopped()) return MediaSyncRun.RETRY
                val step =
                    locked { fetch(session, photo.sha256, directory, mode, control) }
                        ?: return MediaSyncRun.DONE
                when (step) {
                    is Fetch.Saved -> {
                        unavailable -= photo.sha256
                        session.verified[photo.sha256] = step.form
                        tally.downloaded++
                    }

                    Fetch.Unavailable -> {
                        unavailable += photo.sha256
                        tally.unavailable++
                    }

                    Fetch.AlreadyHere -> unavailable -= photo.sha256

                    is Fetch.Stop -> return finish(step.failure, states)
                }
            }
        } finally {
            states.saveUnavailable(unavailable)
            states.saveVerifiedForms(session.verified)
        }
        return null
    }

    /**
     * Baja la foto [sha], primero en la forma vigente, y la deja en [directory] solo si su suma es
     * su nombre y se decodifica como imagen. Una cifrada con otro código, o sin clave, no se abre.
     */
    private suspend fun fetch(
        session: Session,
        sha: String,
        directory: File,
        mode: Mode,
        control: TransferControl,
    ): Fetch {
        val target = File(directory, StoredImage.fileNameOf(sha))
        // Pudo llegar entre la lista y su turno, por ejemplo por una importación.
        if (target.isFile) return Fetch.AlreadyHere
        val preferred = mode.form?.let { MediaNames.fileOf(sha, it) }
        val names = session.listing.namesOf(sha).sortedBy { it != preferred }
        for (name in names) {
            val buffer = ByteArrayOutputStream()
            val got =
                when (val result = session.channel.download(name, buffer, control)) {
                    is DestinationResult.Success -> result.value ?: continue
                    is DestinationResult.Failure -> return Fetch.Stop(result.failure)
                }
            val opened = openDownloaded(name, sha, buffer.toByteArray(), got, mode.key) ?: continue
            if (!isImage(opened.plain)) continue
            directory.mkdirs()
            DurableFile.write(target, opened.plain)
            return Fetch.Saved(opened.form)
        }
        log.warn(MediaSyncEvent.PHOTO_UNAVAILABLE)
        return Fetch.Unavailable
    }

    /**
     * La foto en claro de lo bajado como [name], con su forma, o `null` si no es la foto [sha]: una
     * suma que no coincide, otro código o un archivo dañado.
     */
    private fun openDownloaded(
        name: String,
        sha: String,
        bytes: ByteArray,
        got: DownloadedMedia,
        key: BackupKey?,
    ): Opened? {
        if (name == MediaNames.photo(sha)) {
            return Opened(bytes, MediaForm.Plain).takeIf { got.sha256 == sha }
        }
        val plain = openSealed(sha, bytes, key) ?: return null
        return Opened(plain, MediaForm.Sealed(requireNotNull(key).keyIdHex))
    }

    /**
     * La foto en claro de una foto cifrada, o `null` si no abre con [key], está dañada o no es la
     * foto [sha]. Como toda copia, se lee como entrada no confiable y con tope.
     */
    private fun openSealed(sha: String, bytes: ByteArray, key: BackupKey?): ByteArray? {
        val plain = ByteArrayOutputStream()
        val read =
            EncryptedCopy.read(
                ByteArrayInputStream(bytes),
                key,
                sink = plain,
                maxRawBytes = MediaChannel.MAX_DOWNLOAD_BYTES,
            )
        if (read !is EncryptedReadResult.Read || read.content.sha256 != sha) return null
        return plain.toByteArray()
    }

    /** Paso 3: sube y verifica las fotos que no están en la forma vigente. */
    private suspend fun sendLocal(
        session: Session,
        states: MediaSyncStore,
        local: List<StoredImage>,
        directory: File,
        mode: Mode,
        tally: Tally,
        control: TransferControl,
    ): MediaSyncRun {
        val form = requireNotNull(mode.form)
        for (photo in local) {
            if (session.verified[photo.sha256] == form) continue
            if (control.isStopped()) return MediaSyncRun.RETRY
            val file = File(directory, StoredImage.fileNameOf(photo.sha256))
            val step = locked { send(session, photo.sha256, file, mode, control) } ?: break
            when (step) {
                Step.Verified -> {
                    session.verified[photo.sha256] = form
                    states.saveVerifiedForms(session.verified)
                    tally.uploaded++
                }

                Step.Skipped -> Unit

                is Step.Stop -> return finish(step.failure, states)
            }
        }
        return MediaSyncRun.DONE
    }

    /**
     * Sube la foto [sha] en la forma vigente y la verifica, salvo que el servicio ya la tenga bien.
     * Si el servicio tiene ese nombre con otro contenido, como una foto cifrada con el código
     * anterior, la nueva sube a un temporal, se verifica, y solo entonces se borra la anterior y el
     * temporal toma su nombre. Al final borra la foto en la otra forma, si estaba.
     */
    private suspend fun send(
        session: Session,
        sha: String,
        file: File,
        mode: Mode,
        control: TransferControl,
    ): Step {
        // La foto pudo borrarse del teléfono entre la lista y su turno.
        if (!file.isFile) return Step.Skipped
        val form = requireNotNull(mode.form)
        val name = MediaNames.fileOf(sha, form)
        val files = session.listing.files
        val present = name in files
        val already =
            if (present) {
                when (val checked = holds(session.channel, name, sha, mode, control)) {
                    is DestinationResult.Failure -> return Step.Stop(checked.failure)
                    is DestinationResult.Success -> checked.value
                }
            } else {
                false
            }
        val problem =
            if (already) {
                // Ya estaba bien: si faltaba su `.slmeta`, se completa.
                val size = files[name] ?: file.length()
                if (MediaNames.metaOf(name) in files) {
                    null
                } else {
                    put(session, name, MediaMeta.of(sha, form, size), replace = false, control)
                }
            } else {
                val payload = payloadOf(file.readBytes(), mode)
                val meta = MediaMeta.of(sha, form, payload.size.toLong())
                if (present) {
                    replace(session, name, sha, payload, meta, mode, control)
                } else {
                    upload(session, name, sha, payload, meta, mode, control)
                }
            }
        when (problem) {
            null -> Unit
            Problem.Mismatch -> return mismatch()
            is Problem.Failed -> return Step.Stop(problem.failure)
        }
        // La forma anterior se borra solo ahora, con la nueva verificada.
        val others = session.listing.namesOf(sha).filter { it != name }
        removeAll(session, others)?.let { return Step.Stop(it) }
        return Step.Verified
    }

    /** Sube [payload] como [name], nuevo en el servicio, con su `.slmeta`, y lo verifica. */
    private suspend fun upload(
        session: Session,
        name: String,
        sha: String,
        payload: ByteArray,
        meta: ByteArray,
        mode: Mode,
        control: TransferControl,
    ): Problem? {
        val channel = session.channel
        channel.upload(name, payload, control).failureOrNull()?.let { return Problem.Failed(it) }
        val metaPresent = MediaNames.metaOf(name) in session.listing.files
        put(session, name, meta, replace = metaPresent, control)?.let { return it }
        return verified(holds(channel, name, sha, mode, control))
    }

    /**
     * Reemplaza [name], que el servicio tiene con otro contenido: sube [payload] a un temporal, lo
     * verifica, y solo entonces borra el anterior y mueve el temporal a su nombre.
     */
    private suspend fun replace(
        session: Session,
        name: String,
        sha: String,
        payload: ByteArray,
        meta: ByteArray,
        mode: Mode,
        control: TransferControl,
    ): Problem? {
        val channel = session.channel
        channel.uploadTemporary(name, payload, control).failureOrNull()?.let {
            return Problem.Failed(it)
        }
        verified(holds(channel, MediaNames.temporaryOf(name), sha, mode, control))
            ?.let { return it }
        channel.delete(name, control).failureOrNull()?.let { return Problem.Failed(it) }
        channel.promote(name, control).failureOrNull()?.let { return Problem.Failed(it) }
        return put(session, name, meta, replace = true, control)
    }

    private suspend fun put(
        session: Session,
        name: String,
        meta: ByteArray,
        replace: Boolean,
        control: TransferControl,
    ): Problem? =
        session.channel.putMeta(name, meta, replace, control).failureOrNull()?.let(Problem::Failed)

    private fun verified(result: DestinationResult<Boolean>): Problem? = when (result) {
        is DestinationResult.Failure -> Problem.Failed(result.failure)
        is DestinationResult.Success -> if (result.value) null else Problem.Mismatch
    }

    /**
     * Si el archivo [name] del servicio es la foto [sha] en la forma vigente: lo baja, lo descifra
     * si está cifrado, y compara la suma de la foto con su nombre.
     */
    private suspend fun holds(
        channel: MediaChannel,
        name: String,
        sha: String,
        mode: Mode,
        control: TransferControl,
    ): DestinationResult<Boolean> {
        val buffer = ByteArrayOutputStream()
        val got =
            when (val result = channel.download(name, buffer, control)) {
                is DestinationResult.Success ->
                    result.value ?: return DestinationResult.Success(false)

                is DestinationResult.Failure -> return result
            }
        val opened =
            when (mode.form) {
                MediaForm.Plain -> got.sha256 == sha
                is MediaForm.Sealed -> openSealed(sha, buffer.toByteArray(), mode.key) != null
                null -> false
            }
        return DestinationResult.Success(opened)
    }

    private fun mismatch(): Step {
        log.warn(MediaSyncEvent.VERIFY_MISMATCH)
        return Step.Skipped
    }

    /** Los bytes que suben: la foto tal cual en claro, o cifrada con la clave vigente. */
    private suspend fun payloadOf(plain: ByteArray, mode: Mode): ByteArray {
        val key = mode.key ?: return plain
        val header =
            BackupHeader(
                keyId = key.keyId,
                generation = 0,
                schemaVersion = 0,
                createdAtUtcMillis = nowUtcMillis(),
                deviceId = UUID.fromString(deviceId()),
                rawSize = plain.size.toLong(),
            )
        val sealed = ByteArrayOutputStream()
        EncryptedCopy.write(ByteArrayInputStream(plain), sealed, header, key)
        return sealed.toByteArray()
    }

    /** Cómo termina una ejecución por [failure]: reintentar lo pasajero, terminar lo demás. */
    private suspend fun finish(failure: DestinationFailure, states: MediaSyncStore): MediaSyncRun =
        when (failure) {
            is DestinationFailure.Transient,
            is DestinationFailure.Interrupted,
            -> MediaSyncRun.RETRY

            DestinationFailure.QuotaExceeded -> {
                states.setStop(MediaSyncStop.SERVICE_SPACE)
                log.warn(MediaSyncEvent.SERVICE_SPACE)
                MediaSyncRun.DONE
            }

            // El respaldo de la base lo dice y lo resuelve: las fotos esperan a la siguiente.
            is DestinationFailure.PermissionLost,
            is DestinationFailure.Unsupported,
            DestinationFailure.LocalStorage,
            DestinationFailure.VerifyMismatch,
            is DestinationFailure.ChangedOutside,
            is DestinationFailure.NoSafeSlot,
            DestinationFailure.Incomplete,
            -> {
                log.warn(MediaSyncEvent.STOPPED)
                MediaSyncRun.DONE
            }
        }

    /** La forma en que suben las fotos, o `null` si no puede subir ninguna, con su clave. */
    private class Mode(val form: MediaForm?, val key: BackupKey?)

    private class Session(
        val channel: MediaChannel,
        val listing: MediaListing,
        val verified: MutableMap<String, MediaForm>,
        val uploadsAllowed: Boolean,
    )

    private class Tally {
        var uploaded = 0
        var downloaded = 0
        var unavailable = 0
    }

    private class Opened(val plain: ByteArray, val form: MediaForm)

    /** Lo que dio borrar una foto del servicio: la falla, o `null` si quedó borrada. */
    private class Removal(val failure: DestinationFailure?)

    /** Por qué no quedó guardada una foto que se subió. */
    private sealed interface Problem {
        /** El servicio devolvió otra cosa. */
        data object Mismatch : Problem

        class Failed(val failure: DestinationFailure) : Problem
    }

    private sealed interface Start {
        class Go(val session: Session) : Start

        class Finished(val run: MediaSyncRun) : Start
    }

    private sealed interface Fetch {
        class Saved(val form: MediaForm) : Fetch

        /** El servicio no la tiene, o lo que tiene no es la foto. */
        data object Unavailable : Fetch

        /** Ya está en el teléfono: llegó entre la lista y su turno, por ejemplo al importarla. */
        data object AlreadyHere : Fetch

        class Stop(val failure: DestinationFailure) : Fetch
    }

    private sealed interface Step {
        data object Verified : Step

        /** No se subió ahora: ya no está en el teléfono, o el servicio devolvió otra cosa. */
        data object Skipped : Step

        class Stop(val failure: DestinationFailure) : Step
    }

    companion object {
        /** Cuántas copias de la base deben caber en el servicio para que las fotos sigan. */
        const val MIN_COPIES_OF_SPACE = 3L

        /** El muestreo con que se comprueba que una foto bajada se decodifica: basta un esbozo. */
        private const val CHECK_SAMPLE_SIZE = 4

        /**
         * Si en [availableBytes] caben menos de [MIN_COPIES_OF_SPACE] copias de [copyBytes]. Sin
         * tope informado, o sin ninguna copia con que medir, no se detiene.
         */
        fun lacksSpace(availableBytes: Long?, copyBytes: Long?): Boolean {
            if (availableBytes == null || copyBytes == null || copyBytes <= 0) return false
            return availableBytes / copyBytes < MIN_COPIES_OF_SPACE
        }

        /** Si [bytes] se decodifican como imagen. Se decodifica muestreada y se suelta. */
        fun decodesAsImage(bytes: ByteArray): Boolean {
            val options = BitmapFactory.Options().apply { inSampleSize = CHECK_SAMPLE_SIZE }
            val bitmap =
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return false
            bitmap.recycle()
            return true
        }
    }
}

private fun <T> DestinationResult<T>.failureOrNull(): DestinationFailure? =
    (this as? DestinationResult.Failure)?.failure

/** Las precondiciones de `media-sync`. */
internal sealed interface MediaSyncAccess {
    /**
     * La base está abierta, el destino conectado y la política aceptada. [photos] son las fotos en
     * uso, y [mediaOnMetered] si la persona permite subirlas con datos móviles.
     */
    class Ready(
        val link: ConnectedDestination,
        val photos: List<StoredImage>,
        val mediaOnMetered: Boolean,
    ) : MediaSyncAccess

    /** Una restauración, una migración o un reemplazo esperan: hay que volver más tarde. */
    data object Waiting : MediaSyncAccess

    /** No hay nada que subir: sin destino, sin la política aceptada o con la base en rescate. */
    data object Nothing : MediaSyncAccess
}

/** El destino conectado y su canal de fotos, o por qué no abre. */
internal sealed interface MediaOpen {
    class Opened(val destination: BackupDestination, val media: MediaChannel) : MediaOpen

    /** El acceso se perdió, o el destino no lleva fotos: lo dice el respaldo de la base. */
    data object Unavailable : MediaOpen

    /** Un fallo pasajero de Keystore o del disco. */
    data object RetryLater : MediaOpen
}

/** Cómo terminó una ejecución de `media-sync`. */
internal enum class MediaSyncRun {
    /** Terminó, o no le tocaba: la siguiente la encola el respaldo. */
    DONE,

    /** Algo pasajero: el sistema la vuelve a correr. */
    RETRY,
}

/** Los eventos del registro de `media-sync`. Solo llevan conteos y estados. */
internal enum class MediaSyncEvent(override val text: String) : LogEvent {
    UPLOADED("Fotos subidas y verificadas"),
    BACKUP_BUSY("Respaldo o restauración en curso: las fotos ceden el turno"),
    SERVICE_SPACE("En el servicio caben menos de 3 copias: las fotos se detienen"),
    VERIFY_MISMATCH("Una foto del servicio no coincide con la del teléfono: no se da por guardada"),
    STOPPED("Las fotos esperan a que el respaldo resuelva el destino"),
    WORKER_FAILED("Falla no prevista de las fotos"),
    SYNCED("Fotos sincronizadas"),
    PHOTO_UNAVAILABLE("Una foto en uso no está en el servicio, o no se pudo abrir"),
    NO_KEY("Cifrado activo sin la clave: las fotos no suben"),
}

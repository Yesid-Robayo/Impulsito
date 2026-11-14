package io.github.yesidrobayo.shopledger.data.media

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
import java.io.File
import kotlinx.coroutines.sync.Mutex

/**
 * El canal de las fotos, `media-sync`: sube a `media/` las fotos en uso que el servicio todavía no
 * tiene, una a una, y verifica cada una antes de darla por guardada.
 *
 * - **Solo con WebDAV.** Con ranuras las fotos no salen del teléfono.
 * - **Cede siempre a la copia de la base.** Toma el cerrojo del respaldo para cada foto, sin
 *   esperarlo, y lo suelta entre una y otra: una copia que llega espera a lo sumo una foto, de
 *   300 KiB como mucho. Si el cerrojo está tomado, sale: el respaldo vuelve a encolarla al terminar.
 * - **Solo con red no medida,** salvo que la persona permita fotos con datos móviles.
 * - **Se detiene si en el servicio caben menos de 3 copias** de la base, y lo anota para decirlo:
 *   las fotos nunca desplazan a la base ni empujan a pagar más espacio. Sin tope informado, sigue.
 * - **Verifica bajando la foto** y comparando su suma, que es su nombre, y su tamaño. Lo
 *   verificado queda en `media-sync.txt`; si falta, se reconstruye listando `media/`.
 * - **No toca la base ni el estado del respaldo:** ni `change_seq`, ni `verified_seq`, ni nada que
 *   la pantalla del respaldo cuente como copia.
 *
 * Solo registra conteos y códigos: nunca una suma, un nombre ni una dirección.
 *
 * @param prepare abre la base y mira las precondiciones, como la sonda de la cabeza.
 * @param open abre el destino conectado y su canal de fotos.
 * @param mediaDirectory la carpeta local de las fotos.
 * @param mutex el cerrojo del respaldo, el mismo de cada copia y de cada restauración.
 */
internal class MediaSync(
    private val prepare: suspend () -> MediaSyncAccess,
    private val open: suspend (ConnectedDestination) -> MediaOpen,
    private val store: suspend () -> MediaSyncStore,
    private val mediaDirectory: () -> File,
    private val mutex: Mutex,
    private val log: EventLog,
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
        val local =
            ready.photos.filter { File(directory, StoredImage.fileNameOf(it.sha256)).isFile }
        if (local.isEmpty()) return MediaSyncRun.DONE
        val states = store()
        val start = locked { start(ready.link, states, control) } ?: return MediaSyncRun.DONE
        val session =
            when (start) {
                is Start.Go -> start.session
                is Start.Finished -> return start.run
            }
        val verified = session.verified.toHashSet()
        var uploaded = 0
        for (photo in local) {
            if (photo.sha256 in verified) continue
            if (control.isStopped()) return MediaSyncRun.RETRY
            val step =
                locked { send(session, photo, File(directory, photo.fileName), control) }
                    ?: break
            when (step) {
                Step.Verified -> {
                    verified += photo.sha256
                    states.saveVerified(verified)
                    uploaded++
                }

                Step.Skipped -> Unit

                is Step.Stop -> {
                    log.info(MediaSyncEvent.UPLOADED, LogField.count("fotos", uploaded.toLong()))
                    return finish(step.failure, states)
                }
            }
        }
        log.info(MediaSyncEvent.UPLOADED, LogField.count("fotos", uploaded.toLong()))
        return MediaSyncRun.DONE
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

    /** Abre el destino, mira el espacio y sabe qué fotos ya están verificadas. */
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
        if (lacksSpace(available, copyBytes)) {
            states.setStop(MediaSyncStop.SERVICE_SPACE)
            log.warn(MediaSyncEvent.SERVICE_SPACE)
            return Start.Finished(MediaSyncRun.DONE)
        }
        states.setStop(null)
        val verified =
            states.verified() ?: media.photoSums().also { states.saveVerified(it) }
        return Start.Go(Session(opened.media, media, verified))
    }

    /** Sube y verifica [photo], salvo que el servicio ya la tenga con su tamaño. */
    private suspend fun send(
        session: Session,
        photo: StoredImage,
        file: File,
        control: TransferControl,
    ): Step {
        // La foto pudo borrarse del teléfono entre la lista y su turno.
        if (!file.isFile) return Step.Skipped
        val listedSize = session.listing.files[MediaNames.photo(photo.sha256)]
        val already = session.listing.has(photo.sha256) && listedSize == file.length()
        if (!already) {
            when (val sent = session.channel.upload(photo.sha256, file, control)) {
                is DestinationResult.Success -> Unit
                is DestinationResult.Failure -> return Step.Stop(sent.failure)
            }
        }
        return when (val checked = session.channel.verify(photo.sha256, file.length(), control)) {
            is DestinationResult.Success ->
                if (checked.value) {
                    Step.Verified
                } else {
                    log.warn(MediaSyncEvent.VERIFY_MISMATCH)
                    Step.Skipped
                }

            is DestinationResult.Failure -> Step.Stop(checked.failure)
        }
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

    private class Session(
        val channel: MediaChannel,
        val listing: MediaListing,
        val verified: Set<String>,
    )

    private sealed interface Start {
        class Go(val session: Session) : Start

        class Finished(val run: MediaSyncRun) : Start
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

        /**
         * Si en [availableBytes] caben menos de [MIN_COPIES_OF_SPACE] copias de [copyBytes]. Sin
         * tope informado, o sin ninguna copia con que medir, no se detiene.
         */
        fun lacksSpace(availableBytes: Long?, copyBytes: Long?): Boolean {
            if (availableBytes == null || copyBytes == null || copyBytes <= 0) return false
            return availableBytes / copyBytes < MIN_COPIES_OF_SPACE
        }
    }
}

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
}

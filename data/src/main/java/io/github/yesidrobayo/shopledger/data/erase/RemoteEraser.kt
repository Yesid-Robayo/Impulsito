package io.github.yesidrobayo.shopledger.data.erase

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import io.github.yesidrobayo.shopledger.data.backup.destinationOf
import io.github.yesidrobayo.shopledger.data.destination.ConnectedDestination
import io.github.yesidrobayo.shopledger.data.destination.CopyRef
import io.github.yesidrobayo.shopledger.data.destination.DestinationFailure
import io.github.yesidrobayo.shopledger.data.destination.DestinationResult
import io.github.yesidrobayo.shopledger.data.destination.TransferControl
import io.github.yesidrobayo.shopledger.data.destination.UnsupportedReason
import io.github.yesidrobayo.shopledger.data.destination.slots.SlotStateLoad
import io.github.yesidrobayo.shopledger.data.destination.slots.SlotStateStore
import io.github.yesidrobayo.shopledger.data.local.backup.BackupStateEntity
import io.github.yesidrobayo.shopledger.data.media.MediaNames
import io.github.yesidrobayo.shopledger.data.media.MediaOpen
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupLinkState
import io.github.yesidrobayo.shopledger.domain.model.erase.EraseDestination
import io.github.yesidrobayo.shopledger.domain.model.erase.RemoteEraseResult
import java.io.FileNotFoundException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext

/** Cómo respondió la nube del teléfono al pedido de borrar un documento. */
internal enum class SlotDocumentDelete {
    /** Lo aceptó. Si llegó a la nube no se sabe: lo dice la persona mirando allí. */
    DELETED,

    /** El documento ya no estaba. Cuenta como borrado. */
    GONE,

    /** Esa nube no deja borrarlo desde la app. */
    REFUSED,

    /** El permiso se perdió, la sesión se cerró o la app de la nube ya no está. */
    ACCESS_LOST,
}

/** Le pide a la nube del teléfono que borre un documento de las ranuras, sin red de esta app. */
internal fun interface SlotDocumentDeleter {
    fun delete(uri: String): SlotDocumentDelete
}

/**
 * El borrado con `DocumentsContract.deleteDocument`. Una excepción del proveedor se traduce sin
 * leer su mensaje: el texto puede traer nombres de archivos o de la cuenta.
 */
internal class ResolverSlotDocumentDeleter(private val resolver: ContentResolver) :
    SlotDocumentDeleter {
    override fun delete(uri: String): SlotDocumentDelete = try {
        if (DocumentsContract.deleteDocument(resolver, Uri.parse(uri))) {
            SlotDocumentDelete.DELETED
        } else {
            SlotDocumentDelete.REFUSED
        }
    } catch (_: FileNotFoundException) {
        SlotDocumentDelete.GONE
    } catch (_: UnsupportedOperationException) {
        SlotDocumentDelete.REFUSED
    } catch (_: SecurityException) {
        // También la sesión cerrada, que llega como AuthenticationRequiredException.
        SlotDocumentDelete.ACCESS_LOST
    } catch (_: IllegalArgumentException) {
        // Sin proveedor que responda: la app de la nube no está.
        SlotDocumentDelete.ACCESS_LOST
    }
}

/**
 * Borra del destino conectado lo que la app escribió en él, y nada más:
 *
 * - **WebDAV:** cada copia y después su `.slmeta`, los `.slmeta` sueltos, los temporales y, en
 *   cada subcarpeta de `media/`, las fotos con su `.slmeta` y su temporal. Solo los nombres
 *   exactos que la app escribe: un archivo de la persona en la misma carpeta no se toca, ni la
 *   carpeta. Al final vuelve a listar, y si queda alguna copia, no da el borrado por terminado.
 * - **Ranuras:** los tres documentos, con la nube del teléfono, sin red de esta app.
 *
 * Que algo ya no esté es éxito: repetirlo tras un corte sigue donde quedó. Nunca toca nada de este
 * teléfono: los permisos y `slots.json` los suelta el borrado local, después.
 *
 * @param state el estado del respaldo, para saber cuál es el destino conectado.
 * @param openWebDav abre el destino WebDAV con su credencial, con la carpeta de las fotos.
 */
internal class RemoteEraser(
    private val state: suspend () -> BackupStateEntity?,
    private val openWebDav: suspend (ConnectedDestination) -> MediaOpen,
    private val slots: SlotStateStore,
    private val deleter: SlotDocumentDeleter,
    private val ioContext: CoroutineContext,
) {
    suspend fun erase(): RemoteEraseResult {
        val row = state() ?: return RemoteEraseResult.DELETED
        val link = destinationOf(row)
        return when (eraseDestinationOf(row)) {
            // Sin destino conectado no hay copias que esta app alcance.
            EraseDestination.NONE -> RemoteEraseResult.DELETED

            EraseDestination.ACCESS_LOST -> RemoteEraseResult.ACCESS_LOST

            EraseDestination.WEBDAV -> webDav(requireNotNull(link))

            EraseDestination.SLOTS -> slotFiles()
        }
    }

    private suspend fun webDav(link: ConnectedDestination): RemoteEraseResult {
        val opened =
            when (val open = openWebDav(link)) {
                is MediaOpen.Opened -> open
                MediaOpen.Unavailable -> return RemoteEraseResult.ACCESS_LOST
                MediaOpen.RetryLater -> return RemoteEraseResult.UNREACHABLE
            }
        val control = TransferControl.Unbounded
        var deleted = 0
        val listing =
            when (val listed = opened.destination.listCopies(control)) {
                is DestinationResult.Success -> listed.value
                is DestinationResult.Failure -> return classify(listed.failure, deleted)
            }
        val refs: List<CopyRef> =
            listing.copies.map { it.ref } + listing.orphanMetas +
                listing.temporaries.map { it.ref }
        for (ref in refs) {
            when (val result = opened.destination.delete(ref, control)) {
                is DestinationResult.Success -> deleted += 1
                is DestinationResult.Failure -> return classify(result.failure, deleted)
            }
        }
        when (val folder = opened.media.openFolder(control)) {
            is DestinationResult.Success -> Unit
            is DestinationResult.Failure -> return classify(folder.failure, deleted)
        }
        for (shard in SHARDS) {
            val files =
                when (val listed = opened.media.list(shard, control)) {
                    is DestinationResult.Success -> listed.value.files.keys
                    is DestinationResult.Failure -> return classify(listed.failure, deleted)
                }
            // El archivo antes que su `.slmeta`, como al podar.
            for (name in files.sortedBy { it.length }) {
                when (val result = opened.media.delete(name, control)) {
                    is DestinationResult.Success -> deleted += 1
                    is DestinationResult.Failure -> return classify(result.failure, deleted)
                }
            }
        }
        // Lo que se subió mientras tanto, o lo que el servicio no borró de verdad, se ve aquí.
        return when (val again = opened.destination.listCopies(control)) {
            is DestinationResult.Failure -> classify(again.failure, deleted)

            is DestinationResult.Success ->
                if (again.value.copies.isEmpty()) {
                    RemoteEraseResult.DELETED
                } else {
                    RemoteEraseResult.PARTIAL
                }
        }
    }

    private suspend fun slotFiles(): RemoteEraseResult = withContext(ioContext) {
        val loaded = slots.load() as? SlotStateLoad.Loaded
            ?: return@withContext RemoteEraseResult.ACCESS_LOST
        for (entry in loaded.state.slots.values) {
            when (deleter.delete(entry.uri)) {
                SlotDocumentDelete.DELETED, SlotDocumentDelete.GONE -> Unit
                SlotDocumentDelete.REFUSED -> return@withContext RemoteEraseResult.REFUSED
                SlotDocumentDelete.ACCESS_LOST -> return@withContext RemoteEraseResult.ACCESS_LOST
            }
        }
        RemoteEraseResult.DELETED
    }

    private companion object {
        /** Las 256 subcarpetas de `media/`, por los dos primeros caracteres de la suma. */
        val SHARDS: List<String> =
            (0 until SHARD_COUNT).map { it.toString(HEX).padStart(MediaNames.SHARD_LENGTH, '0') }

        /**
         * Qué dice una falla del destino para el borrado: un acceso perdido pide reconectar; un
         * servicio que prohíbe el método no deja borrar; lo demás es no llegar, o llegar a medias.
         */
        fun classify(failure: DestinationFailure, deleted: Int): RemoteEraseResult =
            when (failure) {
                is DestinationFailure.PermissionLost -> RemoteEraseResult.ACCESS_LOST

                is DestinationFailure.Unsupported ->
                    if (failure.reason in REFUSING) {
                        RemoteEraseResult.REFUSED
                    } else {
                        reached(deleted)
                    }

                else -> reached(deleted)
            }

        private fun reached(deleted: Int): RemoteEraseResult =
            if (deleted > 0) RemoteEraseResult.PARTIAL else RemoteEraseResult.UNREACHABLE

        val REFUSING =
            setOf(
                UnsupportedReason.FORBIDDEN,
                UnsupportedReason.METHOD_NOT_ALLOWED,
                UnsupportedReason.NOT_IMPLEMENTED,
                UnsupportedReason.PROVIDER_REJECTED,
            )
    }
}

private const val SHARD_COUNT = 256
private const val HEX = 16

/**
 * El destino que el borrado alcanza: el conectado, aunque esté en conflicto; ninguno si se
 * desconectó, porque sin credencial no se llega a sus copias; o el acceso perdido.
 */
internal fun eraseDestinationOf(row: BackupStateEntity?): EraseDestination {
    row ?: return EraseDestination.NONE
    val link = destinationOf(row) ?: return EraseDestination.NONE
    return when (row.linkState) {
        BackupLinkState.CONNECTED.name, BackupLinkState.CONFLICT.name -> when (link.kind) {
            BackupDestinationKind.WEBDAV -> EraseDestination.WEBDAV
            BackupDestinationKind.DOCUMENT_SLOTS -> EraseDestination.SLOTS
        }

        BackupLinkState.PERMISSION_LOST.name -> EraseDestination.ACCESS_LOST

        else -> EraseDestination.NONE
    }
}

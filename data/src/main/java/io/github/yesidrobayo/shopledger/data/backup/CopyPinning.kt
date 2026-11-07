package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.destination.BackupDestination
import io.github.yesidrobayo.shopledger.data.destination.BackupFileName
import io.github.yesidrobayo.shopledger.data.destination.CopyMetadata
import io.github.yesidrobayo.shopledger.data.destination.CopyRef
import io.github.yesidrobayo.shopledger.data.destination.DestinationFailure
import io.github.yesidrobayo.shopledger.data.destination.DestinationResult
import io.github.yesidrobayo.shopledger.data.destination.ListedMeta
import io.github.yesidrobayo.shopledger.data.destination.SlMetaCodec
import io.github.yesidrobayo.shopledger.data.destination.TransferControl
import io.github.yesidrobayo.shopledger.data.destination.UnsupportedReason
import io.github.yesidrobayo.shopledger.data.destination.identity
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind

/**
 * Fija y libera copias de WebDAV, reemplazando sus metadatos: es el único reemplazo que hace la
 * app. Una copia fijada nunca se poda, y solo se libera por una acción explícita de la persona,
 * nunca por tiempo.
 *
 * Fijar sube los metadatos con la marca a un nombre temporal y los mueve encima de los anteriores.
 * Un corte entre los dos deja la copia sin metadatos: no cuenta ni se poda, así que el fallo deja
 * más protección, no menos, y el siguiente intento repite. La fijación solo cuenta tras releer los
 * metadatos y ver la marca.
 *
 * Hay como mucho [MAX_PINNED] copias fijadas: si haría falta una más, no se fija ninguna y la
 * persona elige cuál liberar.
 */
internal object CopyPinning : HeadPinner {
    /** Las copias fijadas que puede haber a la vez. */
    const val MAX_PINNED = 3

    override suspend fun pin(
        destination: BackupDestination,
        refs: List<CopyRef>,
        control: TransferControl,
    ): PinOutcome {
        if (destination.kind != BackupDestinationKind.WEBDAV) return PinOutcome.Unavailable
        val listing =
            when (val listed = destination.listCopies(control)) {
                is DestinationResult.Success -> listed.value
                is DestinationResult.Failure -> return PinOutcome.Failed(listed.failure)
            }
        val pinned =
            listing.copies.filter { copy ->
                val name = copy.name ?: return@filter false
                val bytes = (copy.meta as? ListedMeta.Present)?.bytes ?: return@filter false
                (SlMetaCodec.decode(bytes, name.identity()) as? CopyMetadata.Valid)?.meta?.pinned ==
                    true
            }.map { it.ref }.toSet()
        val toPin = refs.distinct().filterNot { it in pinned }
        if (pinned.size + toPin.size > MAX_PINNED) return PinOutcome.TooManyPinned
        for (ref in toPin) {
            when (val marked = mark(destination, ref, pinned = true, control)) {
                is DestinationResult.Success -> Unit
                is DestinationResult.Failure -> return PinOutcome.Failed(marked.failure)
            }
        }
        return PinOutcome.Pinned
    }

    /** Libera [ref]: solo por una acción explícita de la persona. */
    suspend fun release(
        destination: BackupDestination,
        ref: CopyRef,
        control: TransferControl,
    ): DestinationResult<Unit> {
        if (destination.kind != BackupDestinationKind.WEBDAV) {
            return DestinationResult.Failure(
                DestinationFailure.Unsupported(UnsupportedReason.METHOD_NOT_ALLOWED),
            )
        }
        return mark(destination, ref, pinned = false, control)
    }

    /** Deja la marca de fijada de [ref] en [pinned], y solo da éxito si al releer está así. */
    private suspend fun mark(
        destination: BackupDestination,
        ref: CopyRef,
        pinned: Boolean,
        control: TransferControl,
    ): DestinationResult<Unit> {
        val name =
            BackupFileName.parse(ref.value)
                ?: return DestinationResult.Failure(
                    DestinationFailure.Unsupported(UnsupportedReason.INVALID_RESPONSE),
                )
        val current =
            when (val read = destination.readMeta(ref, control)) {
                is DestinationResult.Success -> read.value
                is DestinationResult.Failure -> return read
            }
        val bytes =
            (current as? ListedMeta.Present)?.bytes
                ?: return DestinationResult.Failure(DestinationFailure.Incomplete)
        val replaced =
            SlMetaCodec.withPinned(bytes, name.identity(), pinned)
                ?: return DestinationResult.Failure(DestinationFailure.Incomplete)
        if (!replaced.contentEquals(bytes)) {
            // Un servidor que borró los anteriores y falló al mover deja la copia sin metadatos:
            // se repite una vez con los mismos bytes, que quedan en memoria. Si tampoco, la copia
            // queda sin metadatos, que no cuenta ni se poda.
            val written =
                destination.replaceMeta(ref, replaced, control).takeIf {
                    it is DestinationResult.Success
                } ?: destination.replaceMeta(ref, replaced, control)
            if (written is DestinationResult.Failure) return written
        }
        val reread =
            when (val read = destination.readMeta(ref, control)) {
                is DestinationResult.Success -> read.value
                is DestinationResult.Failure -> return read
            }
        val check = (reread as? ListedMeta.Present)?.bytes
        val meta = (SlMetaCodec.decode(check, name.identity()) as? CopyMetadata.Valid)?.meta
        return if (meta?.pinned == pinned) {
            DestinationResult.Success(Unit)
        } else {
            DestinationResult.Failure(DestinationFailure.Incomplete)
        }
    }
}

package io.github.yesidrobayo.shopledger.data.destination

import io.github.yesidrobayo.shopledger.data.media.DownloadedMedia
import io.github.yesidrobayo.shopledger.data.media.MediaChannel
import io.github.yesidrobayo.shopledger.data.media.MediaListing
import io.github.yesidrobayo.shopledger.data.media.MediaNames
import java.io.OutputStream

/**
 * Las fotos en la subcarpeta `media/` de la carpeta de copias, sobre el mismo cliente WebDAV del
 * respaldo y con la misma credencial.
 *
 * Cada archivo sube a un temporal `.sltmp` y se mueve a su nombre sin pisar nada, como una copia:
 * un corte nunca deja una foto a medias con su nombre final. Su `.slmeta` lleva `sl_kind=media`, la
 * suma de la foto, que es su nombre, y su tamaño, y sube igual. Si al mover el nombre ya existe, es
 * la misma foto: el temporal se borra y la foto se verifica igual. El único archivo que se pisa es
 * un `.slmeta`, cuando la foto cifrada cambia de código.
 *
 * @param copies la carpeta de copias: las fotos van en su subcarpeta.
 */
internal class WebDavMediaChannel(private val client: WebDavClient, copies: WebDavPath) :
    MediaChannel {
    private val folder = copies.folder(MediaNames.FOLDER)

    override suspend fun list(control: TransferControl): DestinationResult<MediaListing> {
        val listing =
            when (val found = client.propfind(folder, WebDavClient.Depth.ONE, control)) {
                is PropfindResult.Listed -> found.listing

                is PropfindResult.Failed -> return failure(found.failure.toDestination())

                PropfindResult.NotFound ->
                    return when (val made = client.mkcol(folder, control)) {
                        MkcolResult.Created, MkcolResult.AlreadyExists ->
                            DestinationResult.Success(MediaListing(emptyMap(), null))

                        // Sin la carpeta de copias no hay dónde poner fotos: lo resuelve el respaldo.
                        MkcolResult.MissingAncestor -> failure(
                            DestinationFailure.ChangedOutside(null),
                        )

                        is MkcolResult.Failed -> failure(made.failure.toDestination())
                    }
            }
        if (!listing.complete) return failure(DestinationFailure.Incomplete)
        val files =
            listing.children
                .filterNot { it.isCollection }
                .associate { it.name to it.contentLength }
        return DestinationResult.Success(MediaListing(files, listing.quotaAvailableBytes))
    }

    override suspend fun upload(
        name: String,
        bytes: ByteArray,
        control: TransferControl,
    ): DestinationResult<Unit> = done(
        moveIn(name, overwrite = false, control) { temporary ->
            client.putBytes(temporary, bytes, typeOf(name), control)
        },
    )

    override suspend fun uploadTemporary(
        name: String,
        bytes: ByteArray,
        control: TransferControl,
    ): DestinationResult<Unit> {
        val temporary = folder.file(MediaNames.temporaryOf(name))
        return done(stored(client.putBytes(temporary, bytes, typeOf(name), control)))
    }

    override suspend fun promote(name: String, control: TransferControl): DestinationResult<Unit> {
        val temporary = folder.file(MediaNames.temporaryOf(name))
        return done(moved(client.move(temporary, folder.file(name), overwrite = false, control)))
    }

    override suspend fun putMeta(
        name: String,
        bytes: ByteArray,
        replace: Boolean,
        control: TransferControl,
    ): DestinationResult<Unit> = done(
        moveIn(MediaNames.metaOf(name), overwrite = replace, control) { temporary ->
            client.putBytes(temporary, bytes, META_TYPE, control)
        },
    )

    override suspend fun download(
        name: String,
        sink: OutputStream,
        control: TransferControl,
    ): DestinationResult<DownloadedMedia?> = when (
        val got = client.get(folder.file(name), sink, MediaChannel.MAX_DOWNLOAD_BYTES, control)
    ) {
        is GetResult.Downloaded ->
            DestinationResult.Success(DownloadedMedia(got.sha256, got.sizeBytes))

        GetResult.NotFound, GetResult.TooLarge -> DestinationResult.Success(null)

        is GetResult.Failed -> failure(got.failure.toDestination())
    }

    override suspend fun delete(name: String, control: TransferControl): DestinationResult<Unit> =
        when (val deleted = client.delete(folder.file(name), control)) {
            is DeleteResult.Deleted -> DestinationResult.Success(Unit)
            is DeleteResult.Failed -> failure(deleted.failure.toDestination())
        }

    /**
     * Sube a `<name>.sltmp` con [put] y lo mueve a [name]. Sin [overwrite] no pisa: si [name] ya
     * estaba, es lo mismo, y el temporal sobra. Devuelve la falla, o `null` si [name] quedó.
     */
    private suspend fun moveIn(
        name: String,
        overwrite: Boolean,
        control: TransferControl,
        put: suspend (WebDavPath) -> PutResult,
    ): DestinationFailure? {
        val temporary = folder.file(MediaNames.temporaryOf(name))
        stored(put(temporary))?.let { return it }
        val result = client.move(temporary, folder.file(name), overwrite, control)
        if (result == MoveResult.TargetExists) {
            // El nombre es el contenido: lo que ya está es lo mismo, y el temporal sobra.
            client.delete(temporary, control)
            return null
        }
        return moved(result)
    }

    private fun stored(result: PutResult): DestinationFailure? = when (result) {
        is PutResult.Stored -> null
        is PutResult.MissingFolder -> DestinationFailure.ChangedOutside(null)
        is PutResult.Failed -> result.failure.toDestination()
    }

    private fun moved(result: MoveResult): DestinationFailure? = when (result) {
        is MoveResult.Moved -> null

        // Quien promueve ya borró la versión vieja: si el nombre volvió a aparecer, algo de afuera
        // tocó la carpeta.
        MoveResult.TargetExists, MoveResult.NotFound, MoveResult.Conflict ->
            DestinationFailure.ChangedOutside(null)

        is MoveResult.Failed -> result.failure.toDestination()
    }

    private fun done(failure: DestinationFailure?): DestinationResult<Unit> =
        failure?.let(::failure) ?: DestinationResult.Success(Unit)

    private fun failure(failure: DestinationFailure) = DestinationResult.Failure(failure)

    companion object {
        private const val PHOTO_TYPE = "image/webp"
        private const val SEALED_TYPE = "application/octet-stream"
        private const val META_TYPE = "application/json"

        private fun typeOf(name: String): String {
            val plain = MediaNames.sha256Of(name)?.let(MediaNames::photo) == name
            return if (plain) PHOTO_TYPE else SEALED_TYPE
        }
    }
}

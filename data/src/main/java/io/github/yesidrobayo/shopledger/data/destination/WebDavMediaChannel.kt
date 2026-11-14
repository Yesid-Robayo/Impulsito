package io.github.yesidrobayo.shopledger.data.destination

import io.github.yesidrobayo.shopledger.data.media.DownloadedMedia
import io.github.yesidrobayo.shopledger.data.media.MediaChannel
import io.github.yesidrobayo.shopledger.data.media.MediaFolder
import io.github.yesidrobayo.shopledger.data.media.MediaListing
import io.github.yesidrobayo.shopledger.data.media.MediaNames
import java.io.OutputStream

/**
 * Las fotos en la subcarpeta `media/` de la carpeta de copias, sobre el mismo cliente WebDAV del
 * respaldo y con la misma credencial. Cada foto vive en la subcarpeta de los dos primeros
 * caracteres de su suma, `media/ab/`, con su `.slmeta` y su temporal: así ninguna lista se
 * acerca al tope del XML. La subcarpeta se crea con `MKCOL` antes de la primera subida que la
 * necesita, una sola vez por ejecución, y un `405` porque ya existía también vale.
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

    /** Las subcarpetas que esta ejecución ya sabe que existen: con ellas no hace falta `MKCOL`. */
    private val knownShards = HashSet<String>()

    override suspend fun openFolder(control: TransferControl): DestinationResult<MediaFolder> {
        // Depth 0: solo la carpeta y el espacio libre, sin sus 256 subcarpetas.
        val listing =
            when (val found = client.propfind(folder, WebDavClient.Depth.ZERO, control)) {
                is PropfindResult.Listed -> found.listing

                is PropfindResult.Failed -> return failure(found.failure.toDestination())

                PropfindResult.NotFound ->
                    return when (val made = client.mkcol(folder, control)) {
                        MkcolResult.Created, MkcolResult.AlreadyExists ->
                            DestinationResult.Success(MediaFolder(null))

                        // Sin la carpeta de copias no hay dónde poner fotos: lo resuelve el respaldo.
                        MkcolResult.MissingAncestor -> failure(
                            DestinationFailure.ChangedOutside(null),
                        )

                        is MkcolResult.Failed -> failure(made.failure.toDestination())
                    }
            }
        if (!listing.complete) return failure(DestinationFailure.Incomplete)
        return DestinationResult.Success(MediaFolder(listing.quotaAvailableBytes))
    }

    override suspend fun list(
        shard: String,
        control: TransferControl,
    ): DestinationResult<MediaListing> {
        require(MediaNames.isShard(shard)) { "Subcarpeta de fotos no válida" }
        val path = folder.folder(shard)
        val listing =
            when (val found = client.propfind(path, WebDavClient.Depth.ONE, control)) {
                is PropfindResult.Listed -> found.listing

                is PropfindResult.Failed -> return failure(found.failure.toDestination())

                // Sin la subcarpeta no hay fotos con ese prefijo: se crea al subir la primera.
                PropfindResult.NotFound -> {
                    knownShards -= shard
                    return DestinationResult.Success(MediaListing(emptyMap(), null))
                }
            }
        if (!listing.complete) return failure(DestinationFailure.Incomplete)
        knownShards += shard
        val files =
            listing.children
                .filterNot { it.isCollection }
                .associate { it.name to it.contentLength }
        return DestinationResult.Success(
            MediaListing.ofShard(shard, files, listing.quotaAvailableBytes),
        )
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
        ensureShard(name, control)?.let { return failure(it) }
        val temporary = folderOf(name).file(MediaNames.temporaryOf(name))
        return done(stored(client.putBytes(temporary, bytes, typeOf(name), control)))
    }

    override suspend fun promote(name: String, control: TransferControl): DestinationResult<Unit> {
        val shard = folderOf(name)
        val temporary = shard.file(MediaNames.temporaryOf(name))
        return done(moved(client.move(temporary, shard.file(name), overwrite = false, control)))
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
        val got =
            client.get(folderOf(name).file(name), sink, MediaChannel.MAX_DOWNLOAD_BYTES, control)
    ) {
        is GetResult.Downloaded ->
            DestinationResult.Success(DownloadedMedia(got.sha256, got.sizeBytes))

        GetResult.NotFound, GetResult.TooLarge -> DestinationResult.Success(null)

        is GetResult.Failed -> failure(got.failure.toDestination())
    }

    override suspend fun delete(name: String, control: TransferControl): DestinationResult<Unit> =
        when (val deleted = client.delete(folderOf(name).file(name), control)) {
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
        ensureShard(name, control)?.let { return it }
        val shard = folderOf(name)
        val temporary = shard.file(MediaNames.temporaryOf(name))
        stored(put(temporary))?.let { return it }
        val result = client.move(temporary, shard.file(name), overwrite, control)
        if (result == MoveResult.TargetExists) {
            // El nombre es el contenido: lo que ya está es lo mismo, y el temporal sobra.
            client.delete(temporary, control)
            return null
        }
        return moved(result)
    }

    /**
     * La subcarpeta de [name] dentro de `media/`. Los nombres los arma la app con [MediaNames]:
     * uno que no lo sea es un error del código, nunca algo que llegó del servicio.
     */
    private fun folderOf(name: String): WebDavPath =
        folder.folder(requireNotNull(MediaNames.shardOfName(name)) { "Nombre de foto no válido" })

    /**
     * Crea la subcarpeta de [name] si esta ejecución no sabe que existe. Que ya existiera también
     * vale. Si falta `media/`, algo de afuera tocó la carpeta. Devuelve la falla, o `null`.
     */
    private suspend fun ensureShard(name: String, control: TransferControl): DestinationFailure? {
        val shard = requireNotNull(MediaNames.shardOfName(name)) { "Nombre de foto no válido" }
        if (shard in knownShards) return null
        return when (val made = client.mkcol(folder.folder(shard), control)) {
            MkcolResult.Created, MkcolResult.AlreadyExists -> {
                knownShards += shard
                null
            }

            MkcolResult.MissingAncestor -> DestinationFailure.ChangedOutside(null)

            is MkcolResult.Failed -> made.failure.toDestination()
        }
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

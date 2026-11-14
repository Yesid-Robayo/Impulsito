package io.github.yesidrobayo.shopledger.data.destination

import io.github.yesidrobayo.shopledger.data.media.MediaChannel
import io.github.yesidrobayo.shopledger.data.media.MediaListing
import io.github.yesidrobayo.shopledger.data.media.MediaNames
import java.io.File
import java.io.OutputStream

/**
 * Las fotos en la subcarpeta `media/` de la carpeta de copias, sobre el mismo cliente WebDAV del
 * respaldo y con la misma credencial.
 *
 * Cada foto sube a un temporal `.sltmp` y se mueve a su nombre sin pisar nada, como una copia: un
 * corte nunca deja una foto a medias con su nombre final. Su `.slmeta` lleva `sl_kind=media`, la
 * suma, que es su nombre, y su tamaño, y sube igual. Si al mover el nombre ya existe, es la misma
 * foto: el temporal se borra y la foto se verifica igual.
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
        sha256: String,
        file: File,
        control: TransferControl,
    ): DestinationResult<Unit> {
        val photo = MediaNames.photo(sha256)
        moveIn(photo, control) { temporary ->
            client.putFile(temporary, file, PHOTO_TYPE, control)
        }?.let { return failure(it) }
        val meta = metaOf(sha256, file.length())
        moveIn(MediaNames.meta(sha256), control) { temporary ->
            client.putBytes(temporary, meta, META_TYPE, control)
        }?.let { return failure(it) }
        return DestinationResult.Success(Unit)
    }

    override suspend fun verify(
        sha256: String,
        sizeBytes: Long,
        control: TransferControl,
    ): DestinationResult<Boolean> = when (
        val got =
            client.get(folder.file(MediaNames.photo(sha256)), Discard, MAX_PHOTO_BYTES, control)
    ) {
        is GetResult.Downloaded ->
            DestinationResult.Success(got.sha256 == sha256 && got.sizeBytes == sizeBytes)

        GetResult.NotFound, GetResult.TooLarge -> DestinationResult.Success(false)

        is GetResult.Failed -> failure(got.failure.toDestination())
    }

    /**
     * Sube a `<name>.sltmp` con [put] y lo mueve a [name] sin pisar. Devuelve la falla, o `null` si
     * [name] quedó en su lugar, también si ya estaba.
     */
    private suspend fun moveIn(
        name: String,
        control: TransferControl,
        put: suspend (WebDavPath) -> PutResult,
    ): DestinationFailure? {
        val temporary = folder.file(name + BackupFileName.TEMPORARY_SUFFIX)
        when (val sent = put(temporary)) {
            is PutResult.Stored -> Unit
            is PutResult.MissingFolder -> return DestinationFailure.ChangedOutside(null)
            is PutResult.Failed -> return sent.failure.toDestination()
        }
        return when (
            val moved = client.move(temporary, folder.file(name), overwrite = false, control)
        ) {
            is MoveResult.Moved -> null

            // El nombre es el contenido: lo que ya está es lo mismo, y el temporal sobra.
            MoveResult.TargetExists -> {
                client.delete(temporary, control)
                null
            }

            MoveResult.NotFound, MoveResult.Conflict -> DestinationFailure.ChangedOutside(null)

            is MoveResult.Failed -> moved.failure.toDestination()
        }
    }

    private fun failure(failure: DestinationFailure) = DestinationResult.Failure(failure)

    /** Un sumidero que solo cuenta: la verificación no guarda la foto. */
    private object Discard : OutputStream() {
        override fun write(b: Int) = Unit

        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }

    companion object {
        /** El tope de lectura de una foto del servicio: nada que la app sube pesa más. */
        const val MAX_PHOTO_BYTES: Long = 2L * 1024 * 1024

        private const val PHOTO_TYPE = "image/webp"
        private const val META_TYPE = "application/json"

        /**
         * El `.slmeta` de la foto [sha256] de [sizeBytes]: en claro, sin nada del negocio ni del
         * teléfono. `sl_kind=media` la separa de las copias aunque alguien la mueva de carpeta.
         */
        fun metaOf(sha256: String, sizeBytes: Long): ByteArray = FlatJson.write(
            listOf(
                "sl_kind" to FlatJson.Value.Text("media"),
                "sl_format" to FlatJson.Value.Number("1"),
                "sl_media_sha256" to FlatJson.Value.Text(sha256),
                "sl_sha256" to FlatJson.Value.Text(sha256),
                "sl_file_size" to FlatJson.Value.Number(sizeBytes.toString()),
            ),
        ).encodeToByteArray()
    }
}

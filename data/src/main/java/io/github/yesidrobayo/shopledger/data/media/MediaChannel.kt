package io.github.yesidrobayo.shopledger.data.media

import io.github.yesidrobayo.shopledger.data.destination.DestinationResult
import io.github.yesidrobayo.shopledger.data.destination.TransferControl
import java.io.File

/**
 * Las fotos en el servicio WebDAV, en la subcarpeta `media/` de la carpeta de copias. La lista de
 * copias solo mira el primer nivel de la carpeta, así que una foto nunca parece una copia, y su
 * `.slmeta` lo dice además con `sl_kind=media`.
 *
 * Una foto nunca se reemplaza: su nombre es su contenido. Solo existe con WebDAV: con ranuras las
 * fotos no salen del teléfono.
 */
internal interface MediaChannel {
    /** Lo que hay en `media/`, con el espacio libre del servicio. La crea si no existe. */
    suspend fun list(control: TransferControl): DestinationResult<MediaListing>

    /**
     * Sube [file], la foto [sha256], con su `.slmeta`. Una foto con ese nombre que ya está no se
     * pisa: tiene el mismo contenido.
     */
    suspend fun upload(
        sha256: String,
        file: File,
        control: TransferControl,
    ): DestinationResult<Unit>

    /**
     * Baja la foto [sha256] sin guardarla y compara su suma y su tamaño con [sizeBytes]. Una foto
     * pesa a lo sumo 300 KiB, así que se lee entera y no por muestra.
     */
    suspend fun verify(
        sha256: String,
        sizeBytes: Long,
        control: TransferControl,
    ): DestinationResult<Boolean>
}

/**
 * La subcarpeta de fotos: el tamaño de cada archivo por su nombre, nulo si el servicio no lo dijo,
 * y el espacio libre del servicio, nulo si no lo informa.
 */
internal data class MediaListing(val files: Map<String, Long?>, val quotaAvailableBytes: Long?) {
    /** Si la foto [sha256] está, con su `.slmeta`. */
    fun has(sha256: String): Boolean =
        MediaNames.photo(sha256) in files && MediaNames.meta(sha256) in files

    /** Las sumas de las fotos que están con su `.slmeta`, sin mirar nada más de la carpeta. */
    fun photoSums(): Set<String> =
        files.keys.mapNotNullTo(HashSet()) { MediaNames.sha256Of(it) }.filterTo(HashSet(), ::has)
}

/** Los nombres de las fotos en el servicio. No se traducen ni cambian nunca. */
internal object MediaNames {
    /** La subcarpeta de las fotos, dentro de la carpeta de copias. */
    const val FOLDER = "media"

    private const val PHOTO_SUFFIX = ".webp"
    private const val META_SUFFIX = ".slmeta"
    private const val SHA256_LENGTH = 64

    fun photo(sha256: String): String = sha256 + PHOTO_SUFFIX

    fun meta(sha256: String): String = photo(sha256) + META_SUFFIX

    /**
     * La suma de [name] si es una foto con el nombre que la app escribe, o `null`. El nombre viene
     * del servicio: solo se usa como dato, nunca como ruta.
     */
    fun sha256Of(name: String): String? {
        if (!name.endsWith(PHOTO_SUFFIX)) return null
        val sha = name.removeSuffix(PHOTO_SUFFIX)
        val valid = sha.length == SHA256_LENGTH && sha.all { it in '0'..'9' || it in 'a'..'f' }
        return sha.takeIf { valid }
    }
}

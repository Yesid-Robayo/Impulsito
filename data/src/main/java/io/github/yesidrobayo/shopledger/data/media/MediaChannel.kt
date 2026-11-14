package io.github.yesidrobayo.shopledger.data.media

import io.github.yesidrobayo.shopledger.data.destination.BackupFileName
import io.github.yesidrobayo.shopledger.data.destination.DestinationResult
import io.github.yesidrobayo.shopledger.data.destination.TransferControl
import java.io.OutputStream

/**
 * Las fotos en el servicio WebDAV, en la subcarpeta `media/` de la carpeta de copias, repartidas
 * en 256 subcarpetas por los dos primeros caracteres de su suma, como `media/ab/<sha256>.slmd`,
 * con su `.slmeta` y su temporal `.sltmp` en la misma. Así cada lista pesa unas 256 veces menos
 * que la de una carpeta plana, y no llega al tope del XML. La lista de copias solo mira el primer
 * nivel de la carpeta, así que una foto nunca parece una copia, y su `.slmeta` lo dice además con
 * `sl_kind=media`.
 *
 * Solo da los pasos sueltos: subir, bajar, mover y borrar por nombre. Qué forma lleva cada foto, en
 * claro o cifrada, y en qué orden se reemplaza una por otra lo decide [MediaSync]. Solo existe con
 * WebDAV: con ranuras las fotos no salen del teléfono.
 */
internal interface MediaChannel {
    /**
     * Abre `media/`: la crea si no existe y da el espacio libre del servicio. No lista lo que hay
     * dentro: eso se pide por subcarpeta, y solo la que hace falta.
     */
    suspend fun openFolder(control: TransferControl): DestinationResult<MediaFolder>

    /**
     * Lo que hay en la subcarpeta [shard] de `media/`, solo con los nombres que le corresponden.
     * Una subcarpeta que no existe está vacía: se crea al subir su primera foto, no al listar.
     */
    suspend fun list(shard: String, control: TransferControl): DestinationResult<MediaListing>

    /**
     * Sube [bytes] a un temporal y lo mueve a [name] sin pisar nada, y crea la subcarpeta si falta.
     * Si [name] ya existe, el temporal se borra y el resultado es éxito: el nombre es el contenido.
     * Quien llama verifica.
     */
    suspend fun upload(
        name: String,
        bytes: ByteArray,
        control: TransferControl,
    ): DestinationResult<Unit>

    /** Sube [bytes] solo al temporal de [name], para verificarlo antes de reemplazar [name]. */
    suspend fun uploadTemporary(
        name: String,
        bytes: ByteArray,
        control: TransferControl,
    ): DestinationResult<Unit>

    /** Mueve el temporal de [name] a [name], sin pisar: quien llama ya borró la versión vieja. */
    suspend fun promote(name: String, control: TransferControl): DestinationResult<Unit>

    /**
     * Escribe [bytes] como el `.slmeta` de [name]. Con [replace] reemplaza el anterior: es el único
     * reemplazo, porque el `.slmeta` de una foto cifrada cambia con el código.
     */
    suspend fun putMeta(
        name: String,
        bytes: ByteArray,
        replace: Boolean,
        control: TransferControl,
    ): DestinationResult<Unit>

    /**
     * Baja el archivo [name] hacia [sink], hasta [MAX_DOWNLOAD_BYTES]. Devuelve su suma y su
     * tamaño, o `null` si no está o pesa más: nada que la app sube pesa tanto.
     */
    suspend fun download(
        name: String,
        sink: OutputStream,
        control: TransferControl,
    ): DestinationResult<DownloadedMedia?>

    /** Borra el archivo [name]. Que ya no esté también es éxito. */
    suspend fun delete(name: String, control: TransferControl): DestinationResult<Unit>

    companion object {
        /** El tope de lectura de cada archivo de `media/`, también al restaurar. */
        const val MAX_DOWNLOAD_BYTES: Long = 2L * 1024 * 1024
    }
}

/** Un archivo bajado de `media/`: su suma SHA-256 en hexadecimal y su tamaño. */
internal data class DownloadedMedia(val sha256: String, val sizeBytes: Long)

/** La carpeta `media/` abierta: el espacio libre del servicio, nulo si no lo informa. */
internal data class MediaFolder(val quotaAvailableBytes: Long?)

/**
 * Una subcarpeta de fotos: el tamaño de cada archivo por su nombre, nulo si el servicio no lo
 * dijo, y el espacio libre del servicio, nulo si no lo informa.
 */
internal data class MediaListing(val files: Map<String, Long?>, val quotaAvailableBytes: Long?) {
    /** Si el archivo [name] está, con su `.slmeta`. */
    fun has(name: String): Boolean = name in files && MediaNames.metaOf(name) in files

    /** Si la foto [sha256] está en la forma [form], con su `.slmeta`. */
    fun has(sha256: String, form: MediaForm): Boolean = has(MediaNames.fileOf(sha256, form))

    /** Los nombres con que está la foto [sha256], en claro, cifrada o las dos. */
    fun namesOf(sha256: String): List<String> =
        listOf(MediaNames.photo(sha256), MediaNames.sealed(sha256)).filter(::has)

    /**
     * Las sumas de las fotos que están en claro con su `.slmeta`. Una cifrada no entra: su código
     * solo se sabe bajándola, y la verificación lo hace cuando le toca.
     */
    fun plainSums(): Set<String> = files.keys.mapNotNullTo(HashSet()) { name ->
        MediaNames.sha256Of(name)?.takeIf { name == MediaNames.photo(it) && has(name) }
    }

    companion object {
        /**
         * La lista de la subcarpeta [shard] con solo los archivos que le corresponden: una foto,
         * su `.slmeta` o su temporal, con los dos primeros caracteres de su suma iguales a [shard].
         * Lo demás, como un nombre de otra subcarpeta, uno con mayúsculas o uno con `/` o `..`, no
         * entra: el servicio es entrada no confiable y sus nombres solo se usan como dato.
         */
        fun ofShard(shard: String, files: Map<String, Long?>, quota: Long?): MediaListing =
            MediaListing(files.filterKeys { MediaNames.shardOfName(it) == shard }, quota)
    }
}

/** Los nombres de las fotos en el servicio. No se traducen ni cambian nunca. */
internal object MediaNames {
    /** La subcarpeta de las fotos, dentro de la carpeta de copias. */
    const val FOLDER = "media"

    /** Cuántos caracteres de la suma nombran la subcarpeta de una foto: 256 subcarpetas. */
    const val SHARD_LENGTH = 2

    private const val PHOTO_SUFFIX = ".webp"
    private const val SEALED_SUFFIX = ".slmd"
    private const val META_SUFFIX = ".slmeta"
    private const val SHA256_LENGTH = 64

    /** La foto en claro. */
    fun photo(sha256: String): String = sha256 + PHOTO_SUFFIX

    /** La foto cifrada, con el formato de las copias cifradas. */
    fun sealed(sha256: String): String = sha256 + SEALED_SUFFIX

    /** El nombre de la foto [sha256] en la forma [form]. */
    fun fileOf(sha256: String, form: MediaForm): String = when (form) {
        MediaForm.Plain -> photo(sha256)
        is MediaForm.Sealed -> sealed(sha256)
    }

    /** El `.slmeta` del archivo [name]. */
    fun metaOf(name: String): String = name + META_SUFFIX

    /** El temporal del archivo [name], mientras sube. */
    fun temporaryOf(name: String): String = name + BackupFileName.TEMPORARY_SUFFIX

    /** La subcarpeta de la foto [sha256]: sus dos primeros caracteres, como `ab`. */
    fun shardOf(sha256: String): String = sha256.substring(0, SHARD_LENGTH)

    /** Si [segment] es el nombre de una subcarpeta de fotos: dos caracteres hexadecimales. */
    fun isShard(segment: String): Boolean =
        segment.length == SHARD_LENGTH && segment.all(::isLowerHex)

    /**
     * La subcarpeta de [name] si es un nombre que la app escribe en `media/`: una foto en claro o
     * cifrada, su `.slmeta`, o el temporal de cualquiera de ellos. Si no, `null`. El nombre puede
     * venir del servicio: solo se usa como dato, nunca como ruta.
     */
    fun shardOfName(name: String): String? {
        val base = name.removeSuffix(BackupFileName.TEMPORARY_SUFFIX).removeSuffix(META_SUFFIX)
        return sha256Of(base)?.let(::shardOf)
    }

    /**
     * La suma de [name] si es una foto, en claro o cifrada, con el nombre que la app escribe, o
     * `null`. El nombre viene del servicio: solo se usa como dato, nunca como ruta.
     */
    fun sha256Of(name: String): String? {
        val sha =
            when {
                name.endsWith(PHOTO_SUFFIX) -> name.removeSuffix(PHOTO_SUFFIX)
                name.endsWith(SEALED_SUFFIX) -> name.removeSuffix(SEALED_SUFFIX)
                else -> return null
            }
        val valid = sha.length == SHA256_LENGTH && sha.all(::isLowerHex)
        return sha.takeIf { valid }
    }

    private fun isLowerHex(char: Char): Boolean = char in '0'..'9' || char in 'a'..'f'
}

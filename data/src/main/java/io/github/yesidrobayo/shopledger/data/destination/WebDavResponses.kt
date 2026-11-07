package io.github.yesidrobayo.shopledger.data.destination

/**
 * Lo que falló en una petición WebDAV, ya traducido según la tabla de respuestas por método. Nunca
 * guarda el texto ni el cuerpo de la respuesta: un servidor no le habla a la persona por boca de la
 * app.
 */
internal sealed interface WebDavFailure {
    /** `401`, o `403` al listar: la credencial ya no sirve o no deja entrar a la carpeta. */
    data class PermissionLost(val status: Int) : WebDavFailure

    /** El servicio no sirve para las copias, o respondió algo que la app no acepta. */
    data class Unsupported(val reason: UnsupportedReason, val status: Int?) : WebDavFailure

    /** `507`: no queda espacio. Nunca lleva a podar. */
    data class QuotaExceeded(val status: Int) : WebDavFailure

    /**
     * `423`, `429` o `5xx`: puede pasar sola. [retryAfterSeconds] es `Retry-After` en segundos, si
     * vino en esa forma.
     */
    data class Transient(val status: Int, val retryAfterSeconds: Long?) : WebDavFailure

    /** No hubo respuesta: red caída, certificado que el sistema no reconoce o tiempo agotado. */
    data class Unreachable(val kind: TransportFailure) : WebDavFailure

    /** Quien llama pidió detenerse mientras se enviaba el cuerpo. */
    data class Interrupted(val reason: InterruptReason) : WebDavFailure
}

/** Por qué no hubo respuesta. */
internal enum class TransportFailure {
    NETWORK,

    /** El certificado no se pudo comprobar. Nunca se ofrece seguir. */
    TLS,

    TIMEOUT,
}

/** Una entrada de un listado. [name] es el último segmento, decodificado y solo como dato. */
internal data class WebDavEntry(
    val name: String,
    val isCollection: Boolean,
    val contentLength: Long?,
    val lastModified: String?,
)

/**
 * Un listado ya revisado. Si [complete] es falso, alguna entrada traía un error: no sirve para
 * decidir ni para podar, y nunca significa carpeta vacía. [quotaAvailableBytes] es nulo si el
 * servicio no lo informa, lo informa negativo o no se entiende: sin tope conocido.
 */
internal data class WebDavListing(
    val self: WebDavEntry?,
    val children: List<WebDavEntry>,
    val complete: Boolean,
    val quotaAvailableBytes: Long?,
)

internal sealed interface PropfindResult {
    data class Listed(val listing: WebDavListing) : PropfindResult

    data object NotFound : PropfindResult

    data class Failed(val failure: WebDavFailure) : PropfindResult
}

internal sealed interface MkcolResult {
    data object Created : MkcolResult

    /** `405`: la carpeta ya existe. */
    data object AlreadyExists : MkcolResult

    /** `409`: falta un ancestro, así que la dirección base no sirve. Nunca se reintenta. */
    data object MissingAncestor : MkcolResult

    data class Failed(val failure: WebDavFailure) : MkcolResult
}

internal sealed interface PutResult {
    /**
     * `200`, `201` o `204`.
     */
    data class Stored(val status: Int) : PutResult

    /** `404` o `409`: falta la carpeta. */
    data class MissingFolder(val status: Int) : PutResult

    /**
     * [bodySent] dice si el cuerpo salió entero antes del fallo: en red medida, una subida que ya
     * gastó los datos no se repite dentro de la ejecución.
     */
    data class Failed(val failure: WebDavFailure, val bodySent: Boolean) : PutResult
}

internal sealed interface MoveResult {
    /** `201` o `204`. */
    data class Moved(val status: Int) : MoveResult

    /** `404`: quizá ya se aplicó y se perdió la respuesta. Lleva a recuperar por nombre. */
    data object NotFound : MoveResult

    /** `409`: falta la carpeta o no se conservaron las propiedades. */
    data object Conflict : MoveResult

    /** `412`: el destino ya existe y no se pisó. */
    data object TargetExists : MoveResult

    data class Failed(val failure: WebDavFailure) : MoveResult
}

internal sealed interface GetResult {
    /** `200`, con todo el cuerpo escrito en el destino de la descarga. */
    data class Downloaded(val sizeBytes: Long, val sha256: String) : GetResult

    data object NotFound : GetResult

    /** El archivo pasa del tope pedido: no se leyó entero, y la llamada se cortó. */
    data object TooLarge : GetResult

    data class Failed(val failure: WebDavFailure) : GetResult
}

/** Una parte de un archivo, para la verificación por muestra. */
internal sealed interface RangeResult {
    /** `206` con el rango pedido, exacto. */
    class Partial(val bytes: ByteArray) : RangeResult {
        override fun toString(): String = "Partial(${bytes.size} bytes)"
    }

    /**
     * `200`: el servidor ignoró `Range`. [bytes] son los primeros bytes del archivo, hasta el largo
     * pedido: sirven para comparar el comienzo, nunca el final.
     */
    class Ignored(val bytes: ByteArray) : RangeResult {
        override fun toString(): String = "Ignored(${bytes.size} bytes)"
    }

    data object NotFound : RangeResult

    data class Failed(val failure: WebDavFailure) : RangeResult
}

internal sealed interface DeleteResult {
    /** `200`, `204` o `404`: ya no está. */
    data class Deleted(val status: Int) : DeleteResult

    data class Failed(val failure: WebDavFailure) : DeleteResult
}

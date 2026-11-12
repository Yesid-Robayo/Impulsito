package io.github.yesidrobayo.shopledger.data.destination

import android.util.Xml
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.xmlpull.v1.XmlPullParser

/**
 * Las seis peticiones de WebDAV que necesita el respaldo: `PROPFIND`, `MKCOL`, `PUT`, `MOVE`, `GET`
 * y `DELETE`, sin biblioteca de terceros.
 *
 * Cada método traduce el código de la respuesta según su fila de la tabla de respuestas por método,
 * y devuelve un resultado cerrado. No reintenta nada, salvo un `3xx` hacia la misma URL, una sola
 * vez: los reintentos los decide quien sabe qué método repite.
 *
 * - Toda URL sale de [endpoint]. `Authorization` se agrega petición por petición, y solo si esquema,
 *   host y puerto son los guardados.
 * - Toda petición lleva `Accept-Encoding: identity`, y una respuesta con otra codificación no se
 *   descomprime: en `PROPFIND` y `GET` es inválida.
 * - El cuerpo de un error nunca se lee. Un cuerpo que queda sin leer corta la llamada, en vez de
 *   solo cerrarla: cerrar deja a OkHttp bajar el resto para reusar la conexión.
 * - Cada llamada corre en un hilo de [executor] con `execute()`, nunca con `enqueue()`: OkHttp
 *   renombra con la dirección el hilo de una llamada asíncrona, y ese nombre puede terminar en el
 *   registro del sistema. Cancelar la corrutina corta la llamada en curso.
 *
 * @param http el cliente de [HttpClientFactory.newClient]. Las pruebas cambian solo la capa de
 *   conexión.
 * @param newXmlParser el analizador de XML. En la app, siempre el del sistema.
 * @param executor dónde corre cada llamada. Por defecto, los hilos de entrada y salida de las
 *   corrutinas, cuyo nombre no lleva nada del servicio.
 */
internal class WebDavClient(
    private val endpoint: WebDavEndpoint,
    private val credential: WebDavCredential,
    private val http: OkHttpClient,
    private val newXmlParser: () -> XmlPullParser = { Xml.newPullParser() },
    private val executor: Executor = Dispatchers.IO.asExecutor(),
) {
    /** `PROPFIND` de [path], con `Depth` 0 o 1. Nunca `infinity`. */
    suspend fun propfind(
        path: WebDavPath,
        depth: Depth,
        control: TransferControl = TransferControl.Unbounded,
    ): PropfindResult {
        val url = endpoint.urlOf(path)
        val result =
            exchange(
                Exchange(
                    "PROPFIND",
                    url,
                    PROPFIND_BODY.toRequestBody(XML_TYPE),
                    mapOf("Depth" to depth.header),
                ),
                control,
            ) { received ->
                when (received.response.code) {
                    207 -> readListing(received, url)
                    404 -> PropfindResult.NotFound
                    else -> PropfindResult.Failed(commonFailure(Method.PROPFIND, received.response))
                }
            }
        return result.fold { PropfindResult.Failed(it) }
    }

    /** `MKCOL` de la carpeta [path]. */
    suspend fun mkcol(
        path: WebDavPath,
        control: TransferControl = TransferControl.Unbounded,
    ): MkcolResult {
        require(path.name == null) { "MKCOL solo crea carpetas" }
        val result =
            exchange(Exchange("MKCOL", endpoint.urlOf(path)), control) { received ->
                when (received.response.code) {
                    201 -> MkcolResult.Created
                    405 -> MkcolResult.AlreadyExists
                    409 -> MkcolResult.MissingAncestor
                    else -> MkcolResult.Failed(commonFailure(Method.MKCOL, received.response))
                }
            }
        return result.fold { MkcolResult.Failed(it) }
    }

    /**
     * `PUT` de [file] en [path], en flujo y con `Content-Length`, sin cargarlo en memoria. Entre una
     * escritura y la siguiente revisa [control]: si debe detenerse, corta la petición y el destino
     * se queda solo con un nombre temporal a medias.
     *
     * [onSent] recibe cada tramo que se escribió hacia el servidor, también en una petición que
     * después se corta o se repite: esos datos ya se gastaron.
     */
    suspend fun putFile(
        path: WebDavPath,
        file: File,
        contentType: String,
        control: TransferControl = TransferControl.Unbounded,
        onSent: (Long) -> Unit = {},
    ): PutResult {
        val body = FileBody(file, contentType.toMediaType(), control, onSent)
        return put(path, body, control) { body.sentFully }
    }

    /** `PUT` de [bytes], para archivos pequeños como los metadatos de una copia. */
    suspend fun putBytes(
        path: WebDavPath,
        bytes: ByteArray,
        contentType: String,
        control: TransferControl = TransferControl.Unbounded,
    ): PutResult = put(path, bytes.toRequestBody(contentType.toMediaType()), control) { true }

    /**
     * `MOVE` de [from] a [to], con `Overwrite: F`: una copia nunca pisa otra. `Destination` se arma
     * con la dirección guardada, nunca con una respuesta.
     *
     * [overwrite] solo es verdadero al reemplazar los metadatos de una copia al fijarla o
     * liberarla: es el único reemplazo que hace la app.
     */
    suspend fun move(
        from: WebDavPath,
        to: WebDavPath,
        overwrite: Boolean = false,
        control: TransferControl = TransferControl.Unbounded,
    ): MoveResult {
        require(from.name != null && to.name != null) { "MOVE solo mueve archivos" }
        val headers =
            mapOf(
                "Destination" to endpoint.urlOf(to).toString(),
                "Overwrite" to if (overwrite) "T" else "F",
            )
        val result =
            exchange(Exchange("MOVE", endpoint.urlOf(from), headers = headers), control) {
                val response = it.response
                when (response.code) {
                    201, 204 -> MoveResult.Moved(response.code)
                    404 -> MoveResult.NotFound
                    409 -> MoveResult.Conflict
                    412 -> MoveResult.TargetExists
                    400 -> MoveResult.Failed(unsupported(UnsupportedReason.NAME_REJECTED, response))
                    else -> MoveResult.Failed(commonFailure(Method.MOVE, response))
                }
            }
        return result.fold { MoveResult.Failed(it) }
    }

    /**
     * `GET` de [path] hacia [sink], hasta [maxBytes], con su suma calculada mientras baja. Si el
     * servicio anuncia más, o manda más, no se lee entero y la llamada se corta.
     *
     * Entre una lectura y la siguiente revisa [control]. Si debe detenerse, el resultado es
     * [WebDavFailure.Interrupted], y [sink] queda con lo que alcanzó a llegar.
     */
    suspend fun get(
        path: WebDavPath,
        sink: OutputStream,
        maxBytes: Long,
        control: TransferControl = TransferControl.Unbounded,
    ): GetResult {
        require(path.name != null) { "GET solo baja archivos" }
        val result =
            exchange(Exchange("GET", endpoint.urlOf(path)), control) { received ->
                when (received.response.code) {
                    200 -> readInto(received, sink, maxBytes, control)
                    404 -> GetResult.NotFound
                    else -> GetResult.Failed(commonFailure(Method.GET, received.response))
                }
            }
        return result.fold { GetResult.Failed(it) }
    }

    /**
     * `GET` de los bytes [first] a [last], ambos incluidos, de un archivo de [totalSize] bytes, para
     * la verificación por muestra.
     *
     * - `206` solo vale si `Content-Range` trae exactamente ese rango y ese tamaño, y el cuerpo trae
     *   exactamente esos bytes. Si no, la respuesta es inválida.
     * - `200` significa que el servidor ignoró `Range`: se leen solo los primeros bytes que se
     *   pidieron y se corta la llamada. En una red rápida, cerrar sin cortar bajaría el archivo
     *   entero.
     */
    suspend fun getRange(
        path: WebDavPath,
        first: Long,
        last: Long,
        totalSize: Long,
        control: TransferControl = TransferControl.Unbounded,
    ): RangeResult {
        require(path.name != null) { "GET solo baja archivos" }
        require(first in 0..last && last < totalSize) { "Rango fuera del archivo" }
        require(last - first < MAX_RANGE_BYTES) { "Rango demasiado grande para la memoria" }
        val headers = mapOf("Range" to "bytes=$first-$last")
        val result =
            exchange(
                Exchange("GET", endpoint.urlOf(path), headers = headers),
                control,
            ) { received ->
                when (received.response.code) {
                    206 -> readPartial(received, first, last, totalSize)
                    200 -> readIgnoredRange(received, (last - first + 1).toInt())
                    404 -> RangeResult.NotFound
                    else -> RangeResult.Failed(commonFailure(Method.GET, received.response))
                }
            }
        return result.fold { RangeResult.Failed(it) }
    }

    /** `DELETE` del archivo [path]. Nunca de una carpeta: la app no borra lo que no creó. */
    suspend fun delete(
        path: WebDavPath,
        control: TransferControl = TransferControl.Unbounded,
    ): DeleteResult {
        require(path.name != null) { "DELETE solo borra archivos, nunca una carpeta" }
        val result =
            exchange(Exchange("DELETE", endpoint.urlOf(path)), control) { received ->
                val response = received.response
                when (response.code) {
                    200, 204, 404 -> DeleteResult.Deleted(response.code)
                    else -> DeleteResult.Failed(commonFailure(Method.DELETE, response))
                }
            }
        return result.fold { DeleteResult.Failed(it) }
    }

    /** Profundidad de un `PROPFIND`. */
    enum class Depth(val header: String) {
        ZERO("0"),
        ONE("1"),
    }

    private suspend fun put(
        path: WebDavPath,
        body: RequestBody,
        control: TransferControl,
        sentFully: () -> Boolean,
    ): PutResult {
        require(path.name != null) { "PUT solo sube archivos" }
        require(body.contentLength() >= 0) { "El cuerpo debe saber su tamaño" }
        val result =
            exchange(Exchange("PUT", endpoint.urlOf(path), body), control) { received ->
                val response = received.response
                when (response.code) {
                    200, 201, 204 -> PutResult.Stored(response.code)

                    404, 409 -> PutResult.MissingFolder(response.code)

                    400 ->
                        PutResult.Failed(
                            unsupported(UnsupportedReason.NAME_REJECTED, response),
                            sentFully(),
                        )

                    else -> PutResult.Failed(commonFailure(Method.PUT, response), sentFully())
                }
            }
        return result.fold { PutResult.Failed(it, sentFully()) }
    }

    private fun readListing(received: Received, url: HttpUrl): PropfindResult {
        if (!isIdentityEncoded(received.response)) {
            return PropfindResult.Failed(invalid(received.response))
        }
        return try {
            val parsed =
                WebDavXmlReader.parseMultistatus(received.bodyStream(), newXmlParser())
            received.drained()
            PropfindResult.Listed(WebDavXmlReader.toListing(parsed, url))
        } catch (_: InvalidWebDavResponse) {
            PropfindResult.Failed(invalid(received.response))
        }
    }

    private fun readInto(
        received: Received,
        sink: OutputStream,
        maxBytes: Long,
        control: TransferControl,
    ): GetResult {
        val response = received.response
        if (!isIdentityEncoded(response)) return GetResult.Failed(invalid(response))
        if (response.body.contentLength() > maxBytes) return GetResult.TooLarge
        val digest = newSha256()
        val buffer = ByteArray(BUFFER_BYTES)
        val input = received.bodyStream()
        var total = 0L
        while (true) {
            stopReason(control)?.let { return GetResult.Failed(WebDavFailure.Interrupted(it)) }
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) return GetResult.TooLarge
            digest.update(buffer, 0, read)
            sink.write(buffer, 0, read)
        }
        received.drained()
        return GetResult.Downloaded(total, digest.digest().toLowerHex())
    }

    private fun readPartial(
        received: Received,
        first: Long,
        last: Long,
        total: Long,
    ): RangeResult {
        val response = received.response
        val invalid = RangeResult.Failed(invalid(response))
        if (!isIdentityEncoded(response)) return invalid
        val match =
            CONTENT_RANGE.matchEntire(response.header("Content-Range")?.trim().orEmpty())
                ?: return invalid
        val (from, to, size) = match.destructured
        val exact =
            from.toLongOrNull() == first && to.toLongOrNull() == last &&
                size.toLongOrNull() == total
        if (!exact) return invalid
        val expected = (last - first + 1).toInt()
        val bytes = readUpTo(received.bodyStream(), expected + 1)
        // Un byte de más también es una respuesta que no es la pedida.
        if (bytes.size != expected) return invalid
        received.drained()
        return RangeResult.Partial(bytes)
    }

    private fun readIgnoredRange(received: Received, wanted: Int): RangeResult {
        val response = received.response
        if (!isIdentityEncoded(response)) return RangeResult.Failed(invalid(response))
        // Lee solo el comienzo: el resto del cuerpo queda sin leer, y la llamada se corta.
        return RangeResult.Ignored(readUpTo(received.bodyStream(), wanted))
    }

    private fun readUpTo(input: InputStream, limit: Int): ByteArray {
        val buffer = ByteArray(limit)
        var read = 0
        while (read < limit) {
            val n = input.read(buffer, read, limit - read)
            if (n < 0) break
            read += n
        }
        return buffer.copyOf(read)
    }

    /**
     * Los códigos que significan lo mismo en todos los métodos. Lo que no tiene fila cae en la fila
     * por defecto: el servicio no sirve, sin confirmar ni podar nada.
     */
    private fun commonFailure(method: Method, response: Response): WebDavFailure {
        val code = response.code
        return when {
            code == 401 -> WebDavFailure.PermissionLost(code)

            code == 403 && method == Method.PROPFIND -> WebDavFailure.PermissionLost(code)

            code == 403 -> unsupported(UnsupportedReason.FORBIDDEN, response)

            code == 405 -> unsupported(UnsupportedReason.METHOD_NOT_ALLOWED, response)

            code == 413 -> unsupported(UnsupportedReason.TOO_LARGE, response)

            code == 501 -> unsupported(UnsupportedReason.NOT_IMPLEMENTED, response)

            code == 507 -> WebDavFailure.QuotaExceeded(code)

            code == 423 || code == 429 || code in 500..599 ->
                WebDavFailure.Transient(code, retryAfterSeconds(response))

            else -> unsupported(UnsupportedReason.UNEXPECTED_STATUS, response)
        }
    }

    private fun unsupported(reason: UnsupportedReason, response: Response) =
        WebDavFailure.Unsupported(reason, response.code)

    private fun invalid(response: Response) =
        unsupported(UnsupportedReason.INVALID_RESPONSE, response)

    private fun retryAfterSeconds(response: Response): Long? = response
        .header("Retry-After")
        ?.trim()
        ?.takeIf {
            it.isNotEmpty() && it.length <= MAX_RETRY_AFTER_DIGITS &&
                it.all { c -> c in '0'..'9' }
        }
        ?.toLongOrNull()

    private fun isIdentityEncoded(response: Response): Boolean {
        val encoding = response.header("Content-Encoding")?.trim() ?: return true
        return encoding.isEmpty() || encoding.equals("identity", ignoreCase = true)
    }

    /**
     * Envía la petición y entrega a [read] cualquier respuesta que no sea `3xx`. Un `3xx` cuyo
     * `Location` es la misma URL, con o sin `/` final, se repite una sola vez, armada desde la URL
     * guardada. Cualquier otro, también al mismo host, deja el servicio como no apto, sin enviar
     * nada a ese lugar.
     */
    private suspend fun <T> exchange(
        exchange: Exchange,
        control: TransferControl,
        read: (Received) -> T,
    ): Exchanged<T> {
        stopReason(control)?.let { return Exchanged.Failed(WebDavFailure.Interrupted(it)) }
        var target = exchange.url
        var repeated = false
        while (true) {
            val step =
                try {
                    send(exchange.request(target), control.remainingMillis()) { received ->
                        val response = received.response
                        if (response.code in 300..399) {
                            Step.Redirect(response.code, response.header("Location"))
                        } else {
                            Step.Done(read(received))
                        }
                    }
                } catch (e: IOException) {
                    return Exchanged.Failed(failureOf(e, control))
                }
            when (step) {
                is Step.Done -> return Exchanged.Answered(step.value)

                is Step.Redirect -> {
                    val again = if (repeated) null else sameUrlVariant(target, step.location)
                    if (again == null) {
                        return Exchanged.Failed(
                            WebDavFailure.Unsupported(UnsupportedReason.REDIRECT, step.status),
                        )
                    }
                    target = again
                    repeated = true
                }
            }
        }
    }

    private fun failureOf(e: IOException, control: TransferControl): WebDavFailure {
        val stopped = generateSequence<Throwable>(e) {
            it.cause
        }.filterIsInstance<TransferStopped>()
        stopped.firstOrNull()?.let { return WebDavFailure.Interrupted(it.reason) }
        // Un tope de la llamada que coincide con el fin del tiempo de la ejecución no es la red.
        if (e is InterruptedIOException && control.remainingMillis()?.let { it <= 0 } == true) {
            return WebDavFailure.Interrupted(InterruptReason.OUT_OF_TIME)
        }
        return WebDavFailure.Unreachable(
            when (e) {
                is SSLException -> TransportFailure.TLS
                is InterruptedIOException -> TransportFailure.TIMEOUT
                else -> TransportFailure.NETWORK
            },
        )
    }

    /**
     * Una llamada en un hilo de [executor]. [handle] lee la respuesta allí mismo, antes de volver:
     * así cancelar la corrutina corta la llamada también mientras baja el cuerpo, y no solo al
     * esperar la respuesta. Si [handle] deja el cuerpo sin leer entero, la llamada se corta antes de
     * cerrarla.
     */
    private suspend fun <T> send(
        request: Request,
        timeoutMillis: Long?,
        handle: (Received) -> T,
    ): T = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        timeoutMillis?.let { call.timeout().timeout(it.coerceAtLeast(1), TimeUnit.MILLISECONDS) }
        continuation.invokeOnCancellation { call.cancel() }
        executor.execute {
            val outcome =
                try {
                    Result.success(
                        call.execute().use { response ->
                            handleAndClose(call, response, handle)
                        },
                    )
                } catch (e: IOException) {
                    Result.failure(e)
                } catch (e: RuntimeException) {
                    call.cancel()
                    Result.failure(e)
                }
            outcome.fold(
                onSuccess = { continuation.resume(it) },
                onFailure = { continuation.resumeWithException(it) },
            )
        }
    }

    private fun <T> handleAndClose(call: Call, response: Response, handle: (Received) -> T): T {
        val received = Received(response)
        try {
            return handle(received)
        } finally {
            if (!received.isDrained && response.body.contentLength() != 0L) call.cancel()
        }
    }

    /**
     * Una respuesta y si su cuerpo se leyó entero. Lo que no se leyó entero no se deja bajar en
     * segundo plano.
     */
    private class Received(val response: Response) {
        var isDrained: Boolean = false
            private set

        fun bodyStream(): InputStream = response.body.byteStream()

        fun drained() {
            isDrained = true
        }
    }

    private enum class Method { PROPFIND, MKCOL, PUT, MOVE, GET, DELETE }

    private class Exchange(
        val method: String,
        val url: HttpUrl,
        val body: RequestBody? = null,
        val headers: Map<String, String> = emptyMap(),
    )

    private fun Exchange.request(target: HttpUrl): Request = Request
        .Builder()
        .url(target)
        .method(method, body)
        .header("Accept-Encoding", "identity")
        .apply {
            headers.forEach { (name, value) -> header(name, value) }
            if (endpoint.isSameOrigin(target)) header("Authorization", credential.authorization)
        }.build()

    private sealed interface Step<out T> {
        data class Done<T>(val value: T) : Step<T>

        data class Redirect(val status: Int, val location: String?) : Step<Nothing>
    }

    /**
     * La URL que se repite tras un `3xx`, o nulo si no se repite. Solo vale si `Location` apunta a
     * la misma URL pedida, con o sin `/` final. La que se devuelve se arma con la URL propia, nunca
     * con el texto del servidor.
     */
    private fun sameUrlVariant(requested: HttpUrl, location: String?): HttpUrl? {
        if (location == null) return null
        val resolved = requested.resolve(location) ?: return null
        if (resolved.scheme != requested.scheme ||
            resolved.host != requested.host ||
            resolved.port != requested.port ||
            resolved.query != null ||
            resolved.username.isNotEmpty() ||
            resolved.password.isNotEmpty()
        ) {
            return null
        }
        val ownPath = requested.encodedPath
        val theirPath = resolved.encodedPath
        if (ownPath.trimEnd('/') != theirPath.trimEnd('/')) return null
        val newPath =
            when {
                theirPath.endsWith("/") && !ownPath.endsWith("/") -> "$ownPath/"
                !theirPath.endsWith("/") && ownPath.endsWith("/") -> ownPath.trimEnd('/')
                else -> ownPath
            }
        return requested.newBuilder().encodedPath(newPath).build()
    }

    private sealed interface Exchanged<out T> {
        data class Answered<T>(val value: T) : Exchanged<T>

        data class Failed(val failure: WebDavFailure) : Exchanged<Nothing>
    }

    private fun <T> Exchanged<T>.fold(onFailure: (WebDavFailure) -> T): T = when (this) {
        is Exchanged.Answered -> value
        is Exchanged.Failed -> onFailure(failure)
    }

    /**
     * El cuerpo de un `PUT` que lee el archivo en flujo. Antes de cada escritura revisa si debe
     * detenerse, y en ese caso corta la petición: el servidor nunca recibe la subida entera.
     */
    private class FileBody(
        private val file: File,
        private val type: MediaType,
        private val control: TransferControl,
        private val onSent: (Long) -> Unit,
    ) : RequestBody() {
        private val length = file.length()

        @Volatile var sentFully: Boolean = false
            private set

        override fun contentType(): MediaType = type

        // Cada escritura abre el archivo de nuevo: repetir tras un 3xx a la misma URL envía lo mismo.
        override fun contentLength(): Long = length

        override fun writeTo(sink: BufferedSink) {
            FileInputStream(file).use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                var sent = 0L
                while (sent < length) {
                    stopReason(control)?.let { throw TransferStopped(it) }
                    val read = input.read(
                        buffer,
                        0,
                        minOf(buffer.size.toLong(), length - sent).toInt(),
                    )
                    if (read < 0) throw IOException("El archivo se acortó mientras se subía")
                    sink.write(buffer, 0, read)
                    sent += read
                    onSent(read.toLong())
                }
                sink.flush()
            }
            sentFully = true
        }
    }

    private companion object {
        val XML_TYPE = "application/xml; charset=utf-8".toMediaType()

        /** `Content-Range` de un `206`: `bytes <primero>-<último>/<tamaño>`, con cifras ASCII. */
        val CONTENT_RANGE = Regex("""(?i)bytes\s+([0-9]{1,19})-([0-9]{1,19})/([0-9]{1,19})""")

        const val BUFFER_BYTES = 64 * 1024

        /** Un rango se lee en memoria: nunca más que esto. */
        const val MAX_RANGE_BYTES = 1L * 1024 * 1024

        /** `Retry-After` en segundos, con un tope que no desborda. */
        const val MAX_RETRY_AFTER_DIGITS = 9

        /** Solo las propiedades que la app usa. */
        const val PROPFIND_BODY =
            """<?xml version="1.0" encoding="utf-8"?>""" +
                """<D:propfind xmlns:D="DAV:"><D:prop>""" +
                "<D:resourcetype/><D:getcontentlength/><D:getlastmodified/>" +
                "<D:quota-available-bytes/>" +
                "</D:prop></D:propfind>"
    }
}

/** Por qué una transferencia debe detenerse ahora, o nulo si puede seguir. */
internal fun stopReason(control: TransferControl): InterruptReason? = when {
    control.isStopped() -> InterruptReason.STOPPED
    control.meteredStop() -> InterruptReason.METERED
    control.remainingMillis()?.let { it <= 0 } == true -> InterruptReason.OUT_OF_TIME
    else -> null
}

/** Corta una petición a propósito. Sin mensaje: nada de esto llega a un log. */
internal class TransferStopped(val reason: InterruptReason) : IOException()

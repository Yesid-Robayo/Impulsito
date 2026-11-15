package io.github.yesidrobayo.shopledger.data.update

import io.github.yesidrobayo.shopledger.domain.model.update.ManifestRead
import io.github.yesidrobayo.shopledger.domain.model.update.VersionManifest
import java.io.IOException
import java.io.InputStream
import java.security.GeneralSecurityException
import java.security.PublicKey
import java.security.Signature
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject

/**
 * Lee el aviso de versión firmado, solo cuando la persona toca `Buscar actualización`. Nunca en
 * segundo plano, y nunca bloquea nada: cada falla es un resultado.
 *
 * - **No envía nada de la persona.** Un `GET` sin `Authorization`, sin cookies, sin parámetros y
 *   con un `User-Agent` fijo, que no lleva el modelo del teléfono ni la versión de la app. El host
 *   ve la IP, y la política lo dice.
 * - **El mismo cliente que el destino WebDAV:** solo HTTPS con las CA del sistema, sin caché ni
 *   cookies, y sin seguir redirecciones. Una respuesta que no sea `200` no se lee.
 * - **Lectura acotada:** el aviso a 64 KiB y su firma a 1 KiB. Lo que pasa el tope se corta,
 *   aunque el tamaño declarado haya mentido, y se descarta entero.
 * - **La firma se comprueba primero,** con `SHA256withECDSA` sobre los bytes exactos del aviso, y
 *   solo después se interpreta el JSON. Una firma que no coincide se ignora sin mirar el
 *   contenido.
 *
 * Cada llamada corre en un hilo de [executor] con `execute()`, como las del destino WebDAV: OkHttp
 * renombra con la dirección el hilo de una llamada asíncrona. Cancelar la corrutina corta la
 * llamada.
 *
 * @param http el cliente único de la app. Las pruebas cambian solo la capa de conexión.
 */
internal class VersionManifestClient(
    private val http: OkHttpClient,
    private val executor: Executor = Dispatchers.IO.asExecutor(),
) {
    /** Lee el aviso de [source], o dice que no hay con qué, si es nulo. */
    suspend fun read(source: VersionManifestSource?): ManifestRead {
        if (source == null) return ManifestRead.NotConfigured
        val manifest =
            when (val got = fetch(source.manifestUrl, MAX_MANIFEST_BYTES)) {
                is Fetched.Body -> got.bytes
                is Fetched.Failed -> return got.read
            }
        val signature =
            when (val got = fetch(source.signatureUrl, MAX_SIGNATURE_BYTES)) {
                is Fetched.Body -> got.bytes
                is Fetched.Failed -> return got.read
            }
        if (!verifies(source.publicKey, manifest, signature)) return ManifestRead.BadSignature
        return parse(manifest)
    }

    private suspend fun fetch(url: HttpUrl, maxBytes: Int): Fetched =
        suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request(url))
            call.timeout().timeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            continuation.invokeOnCancellation { call.cancel() }
            executor.execute { continuation.resume(exchange(call, maxBytes)) }
        }

    private fun exchange(call: Call, maxBytes: Int): Fetched = try {
        call.execute().use { response ->
            val outcome = body(response, maxBytes)
            // Un cuerpo que no se leyó entero corta la llamada: cerrar dejaría bajar el resto.
            if (outcome !is Fetched.Body) call.cancel()
            outcome
        }
    } catch (_: SSLException) {
        Fetched.Failed(ManifestRead.Unreadable)
    } catch (_: IOException) {
        Fetched.Failed(ManifestRead.Offline)
    } catch (_: RuntimeException) {
        call.cancel()
        Fetched.Failed(ManifestRead.Unreadable)
    }

    private fun body(response: Response, maxBytes: Int): Fetched {
        if (response.code != HTTP_OK) return Fetched.Failed(ManifestRead.Unreadable)
        val encoding = response.header("Content-Encoding")
        if (encoding != null && !encoding.equals(IDENTITY, ignoreCase = true)) {
            return Fetched.Failed(ManifestRead.Unreadable)
        }
        if (response.body.contentLength() > maxBytes) {
            return Fetched.Failed(ManifestRead.Unreadable)
        }
        val bytes = readUpTo(response.body.byteStream(), maxBytes + 1)
        if (bytes.size > maxBytes) return Fetched.Failed(ManifestRead.Unreadable)
        return Fetched.Body(bytes)
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

    private fun request(url: HttpUrl): Request = Request
        .Builder()
        .url(url)
        .get()
        .header("User-Agent", USER_AGENT)
        .header("Accept-Encoding", IDENTITY)
        .build()

    private fun verifies(key: PublicKey, data: ByteArray, signature: ByteArray): Boolean = try {
        Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initVerify(key)
            update(data)
            verify(signature)
        }
    } catch (_: GeneralSecurityException) {
        false
    } catch (_: RuntimeException) {
        // Una firma que no es DER válido, según el proveedor del sistema.
        false
    }

    /** Los campos del aviso ya firmado. Un número no entero, o un texto que falta, no vale. */
    private fun parse(bytes: ByteArray): ManifestRead {
        val root =
            try {
                JSONObject(String(bytes, Charsets.UTF_8))
            } catch (_: JSONException) {
                return ManifestRead.Unreadable
            }
        val manifest =
            VersionManifest.of(
                versionCode = root.wholeNumber(VERSION_CODE),
                versionName = root.opt(VERSION_NAME) as? String,
                apkSha256 = root.opt(APK_SHA256) as? String,
                minSafeVersionCode = root.wholeNumber(MIN_SAFE_VERSION_CODE),
                publishedAtMillis = root.wholeNumber(PUBLISHED_AT),
            )
        return manifest?.let(ManifestRead::Verified) ?: ManifestRead.Unreadable
    }

    private fun JSONObject.wholeNumber(name: String): Long? = when (val value = opt(name)) {
        is Int -> value.toLong()
        is Long -> value
        else -> null
    }

    private sealed interface Fetched {
        class Body(val bytes: ByteArray) : Fetched

        class Failed(val read: ManifestRead) : Fetched
    }

    companion object {
        /** El `User-Agent` de cada consulta: fijo, sin modelo, versión ni nombre de la app. */
        const val USER_AGENT = "version-check"

        /** El tope del aviso: un aviso real ocupa unos cientos de bytes. Valor de diseño. */
        const val MAX_MANIFEST_BYTES = 64 * 1024

        /** El tope de la firma: una de curva P-256 en DER ocupa como mucho 72 bytes. */
        const val MAX_SIGNATURE_BYTES = 1024

        /** El algoritmo de la firma del aviso. */
        const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

        const val VERSION_CODE = "version_code"
        const val VERSION_NAME = "version_name"
        const val APK_SHA256 = "apk_sha256"
        const val MIN_SAFE_VERSION_CODE = "min_safe_version_code"
        const val PUBLISHED_AT = "published_at"

        private const val CALL_TIMEOUT_SECONDS = 30L
        private const val HTTP_OK = 200
        private const val IDENTITY = "identity"
    }
}

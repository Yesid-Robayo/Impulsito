package io.github.yesidrobayo.shopledger.data.update

import io.github.yesidrobayo.shopledger.domain.model.update.ApkDownloadResult
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Baja el APK de la versión anunciada, solo cuando la persona toca `Actualizar` después de una
 * búsqueda que encontró una versión nueva. Nunca en segundo plano, y nunca instala: deja el archivo
 * para que la app lo compare con el aviso firmado y con su firma.
 *
 * - **No envía nada de la persona.** Un `GET` sin `Authorization`, sin cookies y con un
 *   `User-Agent` fijo, distinto del de la búsqueda, que no lleva el modelo del teléfono ni la
 *   versión de la app. Cada salto arma su petición desde cero: nada pasa de un host a otro.
 * - **Redirecciones a mano**, con [policy]: cada dirección, también la primera, tiene que ser
 *   `https` y de su lista cerrada de hosts, y se siguen como mucho tres. Una que no cumple no se
 *   pide.
 * - **Lectura acotada:** si el tamaño declarado pasa el tope, no se lee; si el flujo entrega más,
 *   se corta al pasarlo.
 * - **Archivo de nombre fijo:** se escribe en un temporal de la misma carpeta, calculando su
 *   SHA-256 mientras se escribe, y solo al terminar entero toma el nombre fijo. Antes de empezar se
 *   vacía la carpeta, y lo que falla o se cancela no deja nada.
 *
 * Cancelar la corrutina corta la llamada en curso, y esta función no vuelve hasta que el hilo que
 * escribe soltó el archivo: así una descarga nueva nunca choca con los restos de la anterior.
 *
 * @param http el cliente único de la app, sin redirecciones automáticas ni reintentos.
 * @param policy los hosts permitidos. Las pruebas usan los de la propia máquina.
 * @param ioContext donde se lee la red y se escribe el disco.
 */
internal class ApkDownloadClient(
    private val http: OkHttpClient,
    private val policy: ApkDownloadPolicy = ApkDownloadPolicy.RELEASES,
    private val ioContext: CoroutineContext = Dispatchers.IO,
) {
    /**
     * Baja [url] a [target], con el tope [maxBytes]. [onProgress] recibe lo bajado y el total
     * declarado, o nulo sin él; se llama desde el hilo que lee.
     */
    suspend fun download(
        url: HttpUrl,
        target: File,
        maxBytes: Long,
        onProgress: (downloaded: Long, total: Long?) -> Unit,
    ): ApkDownloadResult {
        val folder = requireNotNull(target.parentFile) { "El archivo necesita una carpeta" }
        val ready = withContext(ioContext) { emptyFolder(folder) }
        if (!ready) return ApkDownloadResult.Failed
        var current = url
        // La primera petición y, como mucho, una por cada redirección permitida.
        repeat(policy.maxRedirects + 1) {
            if (!policy.allows(current)) return ApkDownloadResult.Refused
            when (val hop = fetch(current, target, maxBytes, onProgress)) {
                is Hop.Done -> return hop.result
                is Hop.Redirect -> current = hop.location
            }
        }
        // Una redirección más de las permitidas no se sigue.
        return ApkDownloadResult.Refused
    }

    /** Un salto: la llamada corre en [ioContext], y cancelar la corrutina la corta. */
    private suspend fun fetch(
        url: HttpUrl,
        target: File,
        maxBytes: Long,
        onProgress: (Long, Long?) -> Unit,
    ): Hop = coroutineScope {
        val call = http.newCall(request(url))
        call.timeout().timeout(CALL_TIMEOUT_MINUTES, TimeUnit.MINUTES)
        // Mientras el hilo espera la red no ve la cancelación: este vigía corta la llamada, y la
        // lectura bloqueada falla enseguida.
        val watcher =
            launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
        try {
            withContext(ioContext) { exchange(call, url, target, maxBytes, onProgress) }
        } finally {
            watcher.cancel()
        }
    }

    private fun CoroutineScope.exchange(
        call: Call,
        url: HttpUrl,
        target: File,
        maxBytes: Long,
        onProgress: (Long, Long?) -> Unit,
    ): Hop {
        val response =
            try {
                call.execute()
            } catch (_: SSLException) {
                ensureActive()
                return Hop.Done(ApkDownloadResult.Failed)
            } catch (_: IOException) {
                ensureActive()
                return Hop.Done(ApkDownloadResult.Offline)
            }
        return response.use {
            val hop = answer(it, url, target, maxBytes, onProgress)
            // Un cuerpo que no se leyó entero corta la llamada: cerrar dejaría bajar el resto.
            if (hop !is Hop.Done || hop.result !is ApkDownloadResult.Downloaded) call.cancel()
            hop
        }
    }

    private fun CoroutineScope.answer(
        response: Response,
        url: HttpUrl,
        target: File,
        maxBytes: Long,
        onProgress: (Long, Long?) -> Unit,
    ): Hop {
        if (response.code in REDIRECTS) {
            val location = response.header("Location")?.let(url::resolve)
            return location?.let(Hop::Redirect) ?: Hop.Done(ApkDownloadResult.Failed)
        }
        if (response.code != HTTP_OK) return Hop.Done(ApkDownloadResult.Failed)
        val encoding = response.header("Content-Encoding")
        if (encoding != null && !encoding.equals(IDENTITY, ignoreCase = true)) {
            return Hop.Done(ApkDownloadResult.Failed)
        }
        val declared = response.body.contentLength().takeIf { it >= 0 }
        if (declared != null && declared > maxBytes) return Hop.Done(ApkDownloadResult.TooLarge)
        return Hop.Done(save(response, declared, target, maxBytes, onProgress))
    }

    /** Escribe el cuerpo en un temporal y, entero, lo deja con el nombre fijo. */
    private fun CoroutineScope.save(
        response: Response,
        declared: Long?,
        target: File,
        maxBytes: Long,
        onProgress: (Long, Long?) -> Unit,
    ): ApkDownloadResult {
        val folder = requireNotNull(target.parentFile) { "El archivo necesita una carpeta" }
        // El nombre del temporal lo pone el sistema: nunca uno que venga de la red.
        val partial =
            try {
                File.createTempFile(PARTIAL_PREFIX, PARTIAL_SUFFIX, folder)
            } catch (_: IOException) {
                return ApkDownloadResult.Failed
            }
        try {
            val digest = MessageDigest.getInstance(SHA_256)
            var total = 0L
            response.body.byteStream().use { source ->
                FileOutputStream(partial).use { sink ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        ensureActive()
                        val read =
                            try {
                                source.read(buffer)
                            } catch (_: IOException) {
                                ensureActive()
                                return ApkDownloadResult.Failed
                            }
                        if (read < 0) break
                        total += read
                        if (total > maxBytes) return ApkDownloadResult.TooLarge
                        digest.update(buffer, 0, read)
                        sink.write(buffer, 0, read)
                        onProgress(total, declared)
                    }
                }
            }
            if (declared != null && total != declared) return ApkDownloadResult.Failed
            ensureActive()
            target.delete()
            if (!partial.renameTo(target)) return ApkDownloadResult.Failed
            return ApkDownloadResult.Downloaded(hex(digest.digest()), total)
        } catch (_: IOException) {
            // El disco se llenó o la carpeta desapareció.
            return ApkDownloadResult.Failed
        } finally {
            partial.delete()
        }
    }

    /** Crea [folder] o la vacía, también de restos de una descarga anterior. */
    private fun emptyFolder(folder: File): Boolean {
        if (!folder.isDirectory && !folder.mkdirs()) return false
        folder.listFiles().orEmpty().forEach { it.deleteRecursively() }
        return true
    }

    private fun request(url: HttpUrl): Request = Request
        .Builder()
        .url(url)
        .get()
        .header("User-Agent", USER_AGENT)
        .header("Accept-Encoding", IDENTITY)
        .build()

    private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (byte in bytes) {
            val value = byte.toInt() and BYTE_MASK
            append(HEX_DIGITS[value ushr NIBBLE_BITS])
            append(HEX_DIGITS[value and NIBBLE_MASK])
        }
    }

    private sealed interface Hop {
        class Done(val result: ApkDownloadResult) : Hop

        class Redirect(val location: HttpUrl) : Hop
    }

    companion object {
        /** El `User-Agent` de la descarga: fijo, sin modelo, versión ni nombre de la app. */
        const val USER_AGENT = "apk-download"

        /**
         * El tope de cada salto entero. Un APK en una red lenta tarda: los tiempos de conexión y
         * de lectura del cliente siguen cortando una red que no responde.
         */
        private const val CALL_TIMEOUT_MINUTES = 10L

        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        private const val HTTP_OK = 200
        private const val IDENTITY = "identity"
        private const val SHA_256 = "SHA-256"
        private const val BUFFER_BYTES = 64 * 1024
        private const val PARTIAL_PREFIX = "download"
        private const val PARTIAL_SUFFIX = ".part"
        private const val HEX_DIGITS = "0123456789abcdef"
        private const val BYTE_MASK = 0xFF
        private const val NIBBLE_MASK = 0x0F
        private const val NIBBLE_BITS = 4
    }
}

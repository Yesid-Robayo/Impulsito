package io.github.yesidrobayo.shopledger.data.destination

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Un servidor HTTP/1.1 falso y mínimo, propio de las pruebas: lee cada petición entera, la anota y
 * contesta lo que diga [handler]. Corre igual en la JVM y en el dispositivo, sin dependencias.
 *
 * @param bindAll si escucha en todas las interfaces. Así otro nombre de la propia máquina, como
 *   `localhost`, también llega aquí.
 * @param keepAlive si deja la conexión abierta para la petición siguiente. Sin esto, cada respuesta
 *   lleva `Connection: close`.
 */
internal class FakeHttpServer(
    bindAll: Boolean = false,
    private val keepAlive: Boolean = false,
    private val handler: (RecordedRequest) -> FakeResponse,
) : Closeable {
    private val socket =
        if (bindAll) ServerSocket(0) else ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val recorded = Collections.synchronizedList(ArrayList<RecordedRequest>())
    private val thread =
        Thread {
            while (!socket.isClosed) {
                val client =
                    try {
                        socket.accept()
                    } catch (_: IOException) {
                        break
                    }
                Thread { serve(client) }.apply { isDaemon = true }.start()
            }
        }.apply {
            isDaemon = true
            start()
        }

    private val written = AtomicLong()

    val port: Int get() = socket.localPort

    /**
     * Bytes de cuerpo que el servidor logró escribir en el socket, sumando todas las respuestas.
     * Muestra cuánto bajó de verdad un cliente que cortó antes de terminar.
     */
    val bodyBytesWritten: Long get() = written.get()

    /** La dirección base, con `/dav/` como carpeta raíz. */
    fun url(host: String = "127.0.0.1", path: String = "/dav/"): String = "http://$host:$port$path"

    /** Las peticiones recibidas, en orden. */
    val requests: List<RecordedRequest> get() = synchronized(recorded) { recorded.toList() }

    override fun close() {
        socket.close()
        thread.join(TimeUnit.SECONDS.toMillis(2))
    }

    private fun serve(client: Socket) {
        try {
            serveOrThrow(client)
        } catch (_: IOException) {
            // El cliente cortó: es lo que mide la prueba, no un error del servidor.
        }
    }

    private fun serveOrThrow(client: Socket) {
        client.use { connection ->
            connection.soTimeout = 10_000
            val input = BufferedInputStream(connection.getInputStream())
            do {
                val request = readRequest(input) ?: return
                recorded += request
                when (val response = handler(request)) {
                    // Cierra sin responder, como un corte justo después de recibir.
                    FakeResponse.Drop -> return

                    is FakeResponse.Reply -> write(connection, response)
                }
            } while (keepAlive)
        }
    }

    private fun readRequest(input: InputStream): RecordedRequest? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 3) return null
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase()] =
                    line.substring(colon + 1).trim()
            }
        }
        val length = headers["content-length"]?.toInt() ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) return null
            read += n
        }
        return RecordedRequest(parts[0], parts[1], headers, body)
    }

    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString("ISO-8859-1")
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
        }
        return out.toString("ISO-8859-1")
    }

    private fun write(connection: Socket, response: FakeResponse.Reply) {
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(response.status).append(" Fake\r\n")
        response.headers.forEach { (name, value) ->
            head.append(name).append(": ").append(value).append("\r\n")
        }
        head.append("Content-Length: ").append(response.body.size).append("\r\n")
        if (!keepAlive) head.append("Connection: close\r\n")
        head.append("\r\n")
        val out = connection.getOutputStream()
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        var offset = 0
        while (offset < response.body.size) {
            val n = minOf(WRITE_CHUNK, response.body.size - offset)
            out.write(response.body, offset, n)
            written.addAndGet(n.toLong())
            offset += n
        }
        out.flush()
    }

    private companion object {
        const val WRITE_CHUNK = 8 * 1024
    }
}

/** Una petición tal como llegó. Los nombres de cabecera van en minúsculas. */
internal class RecordedRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    fun header(name: String): String? = headers[name.lowercase()]

    override fun toString(): String = "$method $path"
}

internal sealed interface FakeResponse {
    class Reply(
        val status: Int,
        val headers: Map<String, String> = emptyMap(),
        val body: ByteArray = ByteArray(0),
    ) : FakeResponse

    /** Cierra la conexión sin responder. */
    data object Drop : FakeResponse

    companion object {
        fun status(code: Int, headers: Map<String, String> = emptyMap()) = Reply(code, headers)

        fun xml(code: Int, xml: String, headers: Map<String, String> = emptyMap()) = Reply(
            code,
            mapOf("Content-Type" to "application/xml; charset=utf-8") + headers,
            xml.toByteArray(Charsets.UTF_8),
        )
    }
}

package io.github.yesidrobayo.shopledger.data.destination

import java.net.URI

/**
 * Un servicio WebDAV falso, en memoria, para la prueba de conexión: carpetas, archivos y las
 * respuestas del RFC para cada método. Con [ignoreOverwrite] pisa el destino aunque llegue
 * `Overwrite: F`, como un servicio que no lo respeta.
 *
 * [override] permite forzar la respuesta de una petición concreta.
 */
internal class InMemoryWebDav(
    private val ignoreOverwrite: Boolean = false,
    private val honorRange: Boolean = true,
    private val override: (RecordedRequest) -> FakeResponse? = { null },
) {
    val collections = linkedSetOf("/dav/")
    val files = LinkedHashMap<String, ByteArray>()

    /** El espacio libre que informa el servicio. Negativo es sin tope conocido. */
    @Volatile var quota: String = "-3"

    /** Si el listado de una carpeta incluye sus subcarpetas, como hace un servicio real. */
    @Volatile var listsFolders: Boolean = false

    fun handle(request: RecordedRequest): FakeResponse {
        override(request)?.let { return it }
        val path = request.path
        return synchronized(this) {
            when (request.method) {
                "PROPFIND" -> propfind(path, request.header("Depth") == "1")
                "MKCOL" -> mkcol(path)
                "PUT" -> put(path, request.body)
                "MOVE" -> move(path, request)
                "GET" -> files[path]?.let { get(it, request) } ?: FakeResponse.status(404)
                "DELETE" -> delete(path)
                else -> FakeResponse.status(405)
            }
        }
    }

    private fun parentOf(path: String): String = path.trimEnd('/').substringBeforeLast('/') + "/"

    private fun propfind(path: String, withChildren: Boolean): FakeResponse = when {
        path in collections -> {
            // Con depth 1, también los archivos de la carpeta, en su primer nivel.
            val children =
                if (withChildren) {
                    val folders =
                        if (listsFolders) {
                            collections.filter { it != path && parentOf(it) == path }
                                .map { Dav.folder(it) }
                        } else {
                            emptyList()
                        }
                    folders + files.filterKeys { parentOf(it) == path }.map { (href, body) ->
                        Dav.file(href, body.size.toString())
                    }
                } else {
                    emptyList()
                }
            FakeResponse.xml(
                207,
                Dav.multistatus(Dav.folder(path, quota = quota), *children.toTypedArray()),
            )
        }

        path in files -> FakeResponse.xml(
            207,
            Dav.multistatus(Dav.file(path, files.getValue(path).size.toString())),
        )

        else -> FakeResponse.status(404)
    }

    private fun mkcol(path: String): FakeResponse = when {
        path in collections || path.trimEnd('/') in files -> FakeResponse.status(405)

        parentOf(path) !in collections -> FakeResponse.status(409)

        else -> {
            collections += path
            FakeResponse.status(201)
        }
    }

    private fun put(path: String, body: ByteArray): FakeResponse {
        if (parentOf(path) !in collections) return FakeResponse.status(409)
        val existed = path in files
        files[path] = body
        return FakeResponse.status(if (existed) 204 else 201)
    }

    private fun move(path: String, request: RecordedRequest): FakeResponse {
        val source = files[path] ?: return FakeResponse.status(404)
        val destination = URI(requireNotNull(request.header("Destination"))).rawPath
        if (parentOf(destination) !in collections) return FakeResponse.status(409)
        val existed = destination in files
        if (existed && request.header("Overwrite") == "F" && !ignoreOverwrite) {
            return FakeResponse.status(412)
        }
        files.remove(path)
        files[destination] = source
        return FakeResponse.status(if (existed) 204 else 201)
    }

    /** `Range` de un solo tramo, `bytes=a-b`, como lo pide la verificación por muestra. */
    private fun get(body: ByteArray, request: RecordedRequest): FakeResponse {
        val range = request.header("Range")?.let { RANGE.matchEntire(it) }
        if (!honorRange || range == null) return FakeResponse.Reply(200, body = body)
        val first = range.groupValues[1].toInt()
        val last = minOf(range.groupValues[2].toInt(), body.size - 1)
        return FakeResponse.Reply(
            206,
            mapOf("Content-Range" to "bytes $first-$last/${body.size}"),
            body.copyOfRange(first, last + 1),
        )
    }

    private fun delete(path: String): FakeResponse = when {
        files.remove(path) != null -> FakeResponse.status(204)
        collections.remove(path) -> FakeResponse.status(204)
        else -> FakeResponse.status(404)
    }

    private companion object {
        val RANGE = Regex("""bytes=(\d+)-(\d+)""")
    }
}

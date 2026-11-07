package io.github.yesidrobayo.shopledger.data.destination

import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException

/**
 * Lectura del XML de un `207`, que siempre es entrada hostil, aunque ayer fuera de fiar.
 *
 * - Se recorre con `nextToken()`, porque `next()` salta la declaración `<!DOCTYPE`: aquí se ve, y
 *   el documento se rechaza antes de leer nada más. Así ninguna entidad declarada llega a usarse.
 * - Una entidad sin texto de reemplazo hace inválida la respuesta.
 * - Topes: [MAX_XML_BYTES] contados al leer, [MAX_TEXT_CHARS] de texto por elemento y [MAX_DEPTH]
 *   niveles. Pasarse hace inválida la respuesta, y nunca significa carpeta vacía.
 */
internal object WebDavXmlReader {
    const val MAX_XML_BYTES = 8L * 1024 * 1024
    const val MAX_TEXT_CHARS = 4 * 1024
    const val MAX_DEPTH = 16

    private const val DAV = "DAV:"

    /** Lee un `multistatus`. Lanza [InvalidWebDavResponse] si no lo acepta. */
    fun parseMultistatus(input: InputStream, parser: XmlPullParser): List<RawResponse> {
        try {
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            if (parser.getFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL)) {
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            }
            parser.setInput(LimitedInputStream(input, MAX_XML_BYTES), null)
            return MultistatusReader(parser).read()
        } catch (_: XmlPullParserException) {
            throw InvalidWebDavResponse()
        } catch (_: LimitExceeded) {
            throw InvalidWebDavResponse()
        }
    }

    private class Frame(val namespace: String, val name: String) {
        val text = StringBuilder()

        fun isDav(local: String) = namespace == DAV && name == local
    }

    private class MultistatusReader(private val parser: XmlPullParser) {
        private val stack = ArrayList<Frame>()
        private val responses = ArrayList<RawResponse>()
        private var rootSeen = false

        private var hrefs: MutableList<String>? = null
        private var responseStatus: Int? = null
        private var propstats: MutableList<RawPropstat>? = null

        private var propstatStatus: Int? = null
        private var props: MutableMap<String, String>? = null
        private var collection = false

        fun read(): List<RawResponse> {
            while (true) {
                when (parser.nextToken()) {
                    XmlPullParser.DOCDECL -> throw InvalidWebDavResponse()

                    XmlPullParser.START_TAG -> start()

                    XmlPullParser.END_TAG -> end()

                    XmlPullParser.TEXT,
                    XmlPullParser.CDSECT,
                    XmlPullParser.IGNORABLE_WHITESPACE,
                    -> text(parser.text.orEmpty())

                    XmlPullParser.ENTITY_REF -> text(parser.text ?: throw InvalidWebDavResponse())

                    XmlPullParser.END_DOCUMENT -> break

                    // Comentarios e instrucciones de proceso no aportan nada.
                    else -> Unit
                }
            }
            if (!rootSeen || stack.isNotEmpty()) throw InvalidWebDavResponse()
            return responses
        }

        private fun start() {
            if (stack.size >= MAX_DEPTH) throw InvalidWebDavResponse()
            val frame = Frame(parser.namespace.orEmpty(), parser.name.orEmpty())
            if (stack.isEmpty()) {
                if (rootSeen || !frame.isDav("multistatus")) throw InvalidWebDavResponse()
                rootSeen = true
            }
            stack.add(frame)
            when {
                at("multistatus", "response") -> {
                    hrefs = ArrayList()
                    responseStatus = null
                    propstats = ArrayList()
                }

                at("multistatus", "response", "propstat") -> {
                    propstatStatus = null
                    props = LinkedHashMap()
                    collection = false
                }

                at("multistatus", "response", "propstat", "prop", "resourcetype", "collection") ->
                    collection = true
            }
        }

        private fun end() {
            val frame = stack.last()
            val value = frame.text.toString().trim()
            when {
                at("multistatus", "response", "href") -> requireNotNull(hrefs).add(value)

                at("multistatus", "response", "status") -> responseStatus = statusCode(value)

                at("multistatus", "response", "propstat", "status") ->
                    propstatStatus = statusCode(value)

                frame.namespace == DAV && under("multistatus", "response", "propstat", "prop") ->
                    requireNotNull(props)[frame.name] = value

                at("multistatus", "response", "propstat") -> {
                    val status = propstatStatus ?: throw InvalidWebDavResponse()
                    requireNotNull(propstats) +=
                        RawPropstat(status, requireNotNull(props).toMap(), collection)
                }

                at("multistatus", "response") -> {
                    val all = requireNotNull(hrefs)
                    if (all.isEmpty()) throw InvalidWebDavResponse()
                    responses +=
                        RawResponse(all.toList(), responseStatus, requireNotNull(propstats))
                }
            }
            stack.removeAt(stack.lastIndex)
        }

        private fun text(value: String) {
            val top = stack.lastOrNull()
            if (top == null) {
                // Fuera del elemento raíz solo cabe espacio en blanco.
                if (value.isNotBlank()) throw InvalidWebDavResponse()
                return
            }
            if (top.text.length + value.length > MAX_TEXT_CHARS) throw InvalidWebDavResponse()
            top.text.append(value)
        }

        /**
         * Si el elemento actual, el último de [stack], está justo en [path] desde la raíz, con cada
         * nivel del espacio `DAV:`. Un elemento con el mismo nombre en otro lugar no cuenta.
         */
        private fun at(vararg path: String): Boolean =
            stack.size == path.size && path.indices.all { stack[it].isDav(path[it]) }

        /** Si el elemento actual es hijo directo de [path]. */
        private fun under(vararg path: String): Boolean =
            stack.size == path.size + 1 && path.indices.all { stack[it].isDav(path[it]) }
    }

    private val statusLine = Regex("""^HTTP/\d(\.\d)?\s+(\d{3})(\s.*)?$""")

    private fun statusCode(value: String): Int {
        val match = statusLine.matchEntire(value) ?: throw InvalidWebDavResponse()
        return match.groupValues[2].toInt()
    }

    /**
     * Interpreta las respuestas contra la URL pedida, [requested], que siempre sale de la dirección
     * guardada. Un `href` con otro host, fuera de lo pedido, o con `..`, `/`, `\` o NUL en un
     * segmento decodificado hace inválida la respuesta entera: ignorar esa entrada podría fabricar
     * una carpeta vacía.
     */
    fun toListing(responses: List<RawResponse>, requested: HttpUrl): WebDavListing {
        val base = decodedSegments(requested.encodedPath) ?: throw InvalidWebDavResponse()
        val requestedIsCollection = requested.encodedPath.endsWith("/")
        var self: WebDavEntry? = null
        val children = ArrayList<WebDavEntry>()
        var complete = true
        var quota: Long? = null
        for (response in responses) {
            for (href in response.hrefs) {
                val segments = hrefSegments(href, requested) ?: throw InvalidWebDavResponse()
                val isSelf = segments == base
                val isChild =
                    requestedIsCollection &&
                        segments.size == base.size + 1 &&
                        segments.subList(0, base.size) == base
                if (!isSelf && !isChild) throw InvalidWebDavResponse()
                val entry = entryOf(segments.lastOrNull().orEmpty(), response)
                if (response.status != null && response.status !in 200..299) complete = false
                if (isSelf) {
                    // La propia carpeta no es una copia: solo aporta la cuota.
                    self = entry.entry
                    quota = entry.quota
                } else {
                    if (!entry.childComplete) complete = false
                    children += entry.entry
                }
            }
        }
        if (self == null) complete = false
        return WebDavListing(self, children, complete, quota)
    }

    private class ParsedEntry(val entry: WebDavEntry, val childComplete: Boolean, val quota: Long?)

    private val contentLengthForm = Regex("""^[0-9]{1,15}$""")

    private fun entryOf(name: String, response: RawResponse): ParsedEntry {
        var isCollection = false
        var resourceTypeOk = false
        var length: Long? = null
        var lengthOk = false
        var lastModified: String? = null
        var quota: Long? = null
        var badType = false
        var badLength = false
        for (propstat in response.propstats) {
            if (propstat.status != 200) {
                badType = badType || "resourcetype" in propstat.props
                badLength = badLength || "getcontentlength" in propstat.props
                continue
            }
            if ("resourcetype" in propstat.props) {
                resourceTypeOk = true
                isCollection = isCollection || propstat.collection
            }
            propstat.props["getcontentlength"]?.let { value ->
                if (!contentLengthForm.matches(value)) throw InvalidWebDavResponse()
                length = value.toLong()
                lengthOk = true
            }
            propstat.props["getlastmodified"]?.let { lastModified = it }
            // Un negativo, como los de Nextcloud, o algo que no se entiende: sin tope conocido.
            propstat.props["quota-available-bytes"]?.toLongOrNull()?.let { if (it >= 0) quota = it }
        }
        // Una carpeta no tiene tamaño: varios servicios responden su getcontentlength con 404, y eso no
        // deja la lista incompleta. En un archivo, sí.
        val childComplete =
            response.propstats.isNotEmpty() &&
                !badType &&
                resourceTypeOk &&
                (isCollection || (lengthOk && !badLength))
        return ParsedEntry(
            WebDavEntry(name, isCollection, length, lastModified),
            childComplete,
            quota,
        )
    }

    /**
     * Los segmentos decodificados de un `href`, o nulo si no se acepta. Se compara con el texto
     * crudo: resolver la URL antes borraría un `..` en vez de rechazarlo.
     */
    private fun hrefSegments(href: String, requested: HttpUrl): List<String>? {
        val rawPath: String
        val schemeEnd = href.indexOf("://")
        if (schemeEnd > 0) {
            val authorityEnd = href.indexOf('/', schemeEnd + 3)
            if (authorityEnd < 0) return null
            val origin = href.substring(0, authorityEnd + 1).toHttpUrlOrNull() ?: return null
            if (origin.scheme != requested.scheme ||
                origin.host != requested.host ||
                origin.port != requested.port ||
                origin.username.isNotEmpty() ||
                origin.password.isNotEmpty()
            ) {
                return null
            }
            rawPath = href.substring(authorityEnd)
        } else if (href.startsWith("/")) {
            rawPath = href
        } else {
            return null
        }
        return decodedSegments(rawPath)
    }

    private fun decodedSegments(rawPath: String): List<String>? {
        if (!rawPath.startsWith("/") || '?' in rawPath || '#' in rawPath) return null
        val parts = rawPath.substring(1).split('/')
        val withoutTrailing = if (parts.last().isEmpty()) parts.dropLast(1) else parts
        val segments = ArrayList<String>(withoutTrailing.size)
        for (part in withoutTrailing) {
            if (part.isEmpty()) return null
            val decoded = percentDecode(part) ?: return null
            if (decoded == "." || decoded == "..") return null
            if (decoded.any { it == '/' || it == '\\' || it == '\u0000' }) return null
            segments += decoded
        }
        return segments
    }

    private fun percentDecode(part: String): String? {
        val bytes = ByteArrayOutputStream(part.length)
        var i = 0
        while (i < part.length) {
            val c = part[i]
            if (c == '%') {
                if (i + 2 >= part.length) return null
                val high = Character.digit(part[i + 1], 16)
                val low = Character.digit(part[i + 2], 16)
                if (high < 0 || low < 0) return null
                bytes.write(high * 16 + low)
                i += 3
            } else {
                val end = if (Character.isHighSurrogate(c) && i + 1 < part.length) i + 2 else i + 1
                bytes.write(part.substring(i, end).toByteArray(Charsets.UTF_8))
                i = end
            }
        }
        return try {
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray()))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }
}

/** Un `response` tal como vino, antes de compararlo con lo pedido. */
internal data class RawResponse(
    val hrefs: List<String>,
    val status: Int?,
    val propstats: List<RawPropstat>,
)

/** Un `propstat`: su código y el texto de cada propiedad `DAV:` que trae. */
internal data class RawPropstat(
    val status: Int,
    val props: Map<String, String>,
    val collection: Boolean,
)

/** La respuesta no se acepta. Sin mensaje: nada del servidor llega a un log ni a la pantalla. */
internal class InvalidWebDavResponse : Exception()

/** Se pasó del tope de bytes de una respuesta. */
internal class LimitExceeded : IOException()

/** Cuenta los bytes al leer y corta al pasar de [limit]. */
internal class LimitedInputStream(input: InputStream, private val limit: Long) :
    FilterInputStream(input) {
    private var count = 0L

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) add(1)
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val read = super.read(b, off, len)
        if (read > 0) add(read.toLong())
        return read
    }

    override fun skip(n: Long): Long {
        val skipped = super.skip(n)
        if (skipped > 0) add(skipped)
        return skipped
    }

    private fun add(n: Long) {
        count += n
        if (count > limit) throw LimitExceeded()
    }
}

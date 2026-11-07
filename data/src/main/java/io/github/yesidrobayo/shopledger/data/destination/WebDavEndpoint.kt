package io.github.yesidrobayo.shopledger.data.destination

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * La dirección de un servicio WebDAV, ya revisada. Toda petición se arma desde aquí, nunca desde
 * algo que respondió el servidor: así un `href` o un `Location` hostil no elige a dónde va la
 * contraseña.
 *
 * Fuera de las pruebas solo se crea con [parse], que exige `https://`. Las pruebas usan el
 * constructor para apuntar a un servidor falso en la propia máquina.
 */
internal class WebDavEndpoint(
    /** Siempre con `/` final, sin usuario, sin consulta y sin fragmento. */
    val baseUrl: HttpUrl,
) {
    init {
        require(baseUrl.encodedPath.endsWith("/")) { "La dirección base debe terminar en /" }
        require(baseUrl.username.isEmpty() && baseUrl.password.isEmpty()) {
            "La dirección no lleva usuario ni contraseña"
        }
        require(baseUrl.query == null && baseUrl.fragment == null) {
            "La dirección no lleva consulta ni fragmento"
        }
    }

    /**
     * El host tal como viajará, en minúsculas y en ASCII: un nombre con letras de otro alfabeto se
     * ve como `xn--…`. Es lo que la persona confirma antes de la primera petición, porque un
     * certificado válido no prueba que el servicio sea el correcto.
     */
    val asciiHost: String get() = baseUrl.host

    /** La URL de [path], armada con la dirección guardada y con cada segmento codificado. */
    fun urlOf(path: WebDavPath): HttpUrl = baseUrl
        .newBuilder()
        .apply {
            path.folder.forEach { addPathSegment(it) }
            // Una carpeta se pide con / final.
            addPathSegment(path.name ?: "")
        }.build()

    /**
     * Si [url] tiene exactamente el esquema, el host y el puerto guardados. Solo entonces viaja la
     * cabecera `Authorization`.
     */
    fun isSameOrigin(url: HttpUrl): Boolean = url.scheme == baseUrl.scheme &&
        url.host == baseUrl.host &&
        url.port == baseUrl.port

    /** Sin la dirección: un `toString()` nunca debe llevarla a un log. */
    override fun toString(): String = "WebDavEndpoint"

    override fun equals(other: Any?): Boolean = other is WebDavEndpoint && other.baseUrl == baseUrl

    override fun hashCode(): Int = baseUrl.hashCode()

    companion object {
        /**
         * Revisa lo que escribió la persona. Rechaza sin abrir ninguna conexión una dirección que no
         * sea `https://`, que lleve usuario o contraseña dentro, o que tenga `?` o `#`. Pasa el host
         * a minúsculas y a ASCII, quita el puerto 443 y agrega la `/` final.
         */
        fun parse(input: String): EndpointParse {
            val text = input.trim()
            if (text.isEmpty() || text.any { it.isWhitespace() || it.isISOControl() }) {
                return EndpointParse.Rejected(EndpointRejection.MALFORMED)
            }
            val scheme = text.substringBefore("://", missingDelimiterValue = "")
            if (scheme.isEmpty()) return EndpointParse.Rejected(EndpointRejection.MALFORMED)
            if (!scheme.equals("https", ignoreCase = true)) {
                return EndpointParse.Rejected(EndpointRejection.NOT_HTTPS)
            }
            if ('?' in text || '#' in text) {
                return EndpointParse.Rejected(EndpointRejection.QUERY_OR_FRAGMENT)
            }
            // Un @ en la autoridad es usuario, aunque venga vacío: la contraseña nunca va en la URL.
            val authority = text.substringAfter("://").substringBefore('/')
            if ('@' in
                authority
            ) {
                return EndpointParse.Rejected(EndpointRejection.CREDENTIALS_IN_URL)
            }
            val parsed =
                text.toHttpUrlOrNull() ?: return EndpointParse.Rejected(EndpointRejection.MALFORMED)
            val withSlash =
                if (parsed.encodedPath.endsWith("/")) {
                    parsed
                } else {
                    parsed.newBuilder().addPathSegment("").build()
                }
            return EndpointParse.Accepted(WebDavEndpoint(withSlash))
        }
    }
}

/** El resultado de revisar una dirección escrita a mano. */
internal sealed interface EndpointParse {
    data class Accepted(val endpoint: WebDavEndpoint) : EndpointParse

    data class Rejected(val reason: EndpointRejection) : EndpointParse
}

/** Por qué se rechazó una dirección, antes de abrir ninguna conexión. */
internal enum class EndpointRejection {
    /** `http://` u otro esquema. Solo se admite `https://`, también en una red local. */
    NOT_HTTPS,

    /** Usuario o contraseña dentro de la URL. */
    CREDENTIALS_IN_URL,

    /** Lleva `?` o `#`. */
    QUERY_OR_FRAGMENT,

    /** No es una URL. */
    MALFORMED,
}

/**
 * Un lugar dentro del servicio, relativo a la dirección guardada: una carpeta, si [name] es nulo, o
 * un archivo dentro de ella. Cada segmento es un nombre, nunca una ruta.
 */
internal data class WebDavPath(val folder: List<String>, val name: String? = null) {
    init {
        folder.forEach { require(isSafeSegment(it)) { "Segmento de carpeta no válido" } }
        if (name != null) require(isSafeSegment(name)) { "Nombre de archivo no válido" }
    }

    /** Un archivo dentro de esta carpeta. */
    fun file(fileName: String): WebDavPath {
        require(name == null) { "Un archivo no contiene archivos" }
        return WebDavPath(folder, fileName)
    }

    /** Una carpeta dentro de esta carpeta. */
    fun folder(child: String): WebDavPath {
        require(name == null) { "Un archivo no contiene carpetas" }
        return WebDavPath(folder + child)
    }

    companion object {
        /** Un segmento que no cambia de carpeta al leerlo: sin `/`, `\`, `.`, `..` ni controles. */
        fun isSafeSegment(segment: String): Boolean = segment.isNotEmpty() &&
            segment != "." &&
            segment != ".." &&
            segment.none { it == '/' || it == '\\' || it.isISOControl() }
    }
}

/**
 * Usuario y contraseña de un servicio WebDAV, solo en memoria y solo mientras dura la operación.
 * Guarda la cabecera `Basic` ya armada en UTF-8: OkHttp usa ISO-8859-1 por defecto, y eso daña una
 * contraseña con letras no latinas. [toString] nunca la muestra.
 */
internal class WebDavCredential(user: String, password: String) {
    val authorization: String = Credentials.basic(user, password, Charsets.UTF_8)

    override fun toString(): String = "WebDavCredential(oculta)"
}

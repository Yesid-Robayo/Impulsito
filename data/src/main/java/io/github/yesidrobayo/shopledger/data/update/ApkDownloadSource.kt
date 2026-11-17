package io.github.yesidrobayo.shopledger.data.update

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Dónde está el APK de la versión anunciada. La dirección se arma dentro de la app, con la
 * plantilla de sus recursos y el nombre de versión del aviso firmado: nunca sale de la red. El
 * nombre solo vale si es una versión limpia, `1.0.2` o `1.1.0-rc.2`, así que no puede cambiar la
 * carpeta, el host ni agregar parámetros.
 */
internal object ApkDownloadSource {
    /** Lo que la plantilla cambia por el nombre de versión, en cada lugar donde aparece. */
    const val PLACEHOLDER = "%1\$s"

    /** Tres números de hasta cuatro cifras, y una candidata opcional, como `-rc.2`. */
    private val VERSION_NAME = Regex("""[0-9]{1,4}\.[0-9]{1,4}\.[0-9]{1,4}(-rc\.[0-9]{1,4})?""")

    /**
     * La dirección del APK de [versionName] con [template], o nula si el nombre no es una versión
     * limpia, o si la plantilla no es `https`, no tiene dónde va la versión o lleva usuario,
     * parámetros o fragmento. Los hosts permitidos los decide [ApkDownloadPolicy] en cada salto.
     */
    fun urlOf(template: String, versionName: String): HttpUrl? {
        if (!VERSION_NAME.matches(versionName)) return null
        val trimmed = template.trim()
        if (PLACEHOLDER !in trimmed) return null
        val url = trimmed.replace(PLACEHOLDER, versionName).toHttpUrlOrNull() ?: return null
        if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        if (url.query != null || url.fragment != null) return null
        return url
    }
}

/**
 * A qué hosts puede ir la descarga del APK, y por cuántas redirecciones. GitHub entrega el archivo
 * de una versión publicada redirigiendo a su servidor de archivos, con una dirección firmada que
 * vence: por eso se siguen redirecciones aquí, y solo aquí, a mano y con esta lista cerrada.
 *
 * @param allowedHosts los hosts exactos, sin subdominios.
 * @param httpsOnly si exige `https`. Solo las pruebas, con un servidor sin TLS, lo apagan; el
 *   cliente de la app tampoco habla en claro.
 * @param maxRedirects cuántas redirecciones sigue, como mucho.
 */
internal class ApkDownloadPolicy(
    private val allowedHosts: Set<String>,
    private val httpsOnly: Boolean = true,
    val maxRedirects: Int = MAX_REDIRECTS,
) {
    /** Si se puede pedir [url]: con el esquema exigido, a un host de la lista, sin credenciales. */
    fun allows(url: HttpUrl): Boolean {
        val schemeAllowed = !httpsOnly || url.isHttps
        val noCredentials = url.username.isEmpty() && url.password.isEmpty()
        return schemeAllowed && noCredentials && url.host in allowedHosts
    }

    companion object {
        /** Bastan dos: la página de la versión y su servidor de archivos. Una más de margen. */
        const val MAX_REDIRECTS = 3

        /**
         * Los hosts de las versiones publicadas: la página de descargas, y los dos nombres que
         * GitHub usa para su servidor de archivos. Ningún otro destino de la app los usa.
         */
        val RELEASES: ApkDownloadPolicy =
            ApkDownloadPolicy(
                setOf(
                    "github.com",
                    "release-assets.githubusercontent.com",
                    "objects.githubusercontent.com",
                ),
            )
    }
}

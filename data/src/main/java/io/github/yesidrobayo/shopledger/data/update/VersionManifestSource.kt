package io.github.yesidrobayo.shopledger.data.update

import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Dónde está el aviso de versión y con qué clave se comprueba. Es el único destino de red de la
 * app fuera del servicio WebDAV que eligió la persona.
 *
 * @property manifestUrl la dirección de `version.json`.
 * @property signatureUrl la de su firma, `version.json.sig`, en la misma carpeta.
 * @property publicKey la clave pública con que se firmó, que viaja dentro de la app.
 */
internal class VersionManifestSource(
    val manifestUrl: HttpUrl,
    val signatureUrl: HttpUrl,
    val publicKey: PublicKey,
) {
    companion object {
        /** Lo que se agrega al nombre del aviso para llegar a su firma. */
        const val SIGNATURE_SUFFIX = ".sig"

        /**
         * El origen con la dirección [manifestUrl] y la clave [publicKey], o nulo si falta una o
         * no sirve. Solo vale una dirección `https` a un archivo, sin usuario, contraseña,
         * parámetros ni fragmento: la consulta no lleva nada de la persona. La clave va en Base64,
         * con la forma pública X.509 de una clave de curva elíptica.
         */
        fun parse(manifestUrl: String, publicKey: String): VersionManifestSource? {
            val url = manifestUrl.trim().toHttpUrlOrNull() ?: return null
            if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
            if (url.query != null || url.fragment != null) return null
            val name = url.pathSegments.last()
            if (name.isEmpty()) return null
            val key = publicKeyOf(publicKey) ?: return null
            val signature =
                url.newBuilder().setPathSegment(url.pathSize - 1, name + SIGNATURE_SUFFIX).build()
            return VersionManifestSource(url, signature, key)
        }

        /** La clave pública de [base64], o nula si está vacía o no es una clave de curva. */
        fun publicKeyOf(base64: String): PublicKey? {
            val text = base64.filterNot(Char::isWhitespace)
            if (text.isEmpty()) return null
            return try {
                val bytes = Base64.getDecoder().decode(text)
                KeyFactory.getInstance(KEY_ALGORITHM).generatePublic(X509EncodedKeySpec(bytes))
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: GeneralSecurityException) {
                null
            }
        }

        private const val KEY_ALGORITHM = "EC"
    }
}

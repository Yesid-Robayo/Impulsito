package io.github.yesidrobayo.shopledger.ui.privacy

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URI
import java.net.URISyntaxException

/** Lo que pasó al tocar un enlace que abre el navegador. */
enum class LinkOutcome {
    /** Otra app lo abrió: desde ahí, el trabajo es suyo. */
    OPENED,

    /** No hay ninguna app que abra enlaces. */
    NO_APP,

    /** No es una dirección `https` con su servidor: no se abre. */
    NOT_ALLOWED,
}

/**
 * Si [url] se puede entregar al navegador: solo `https`, con servidor, y sin usuario ni contraseña
 * en la dirección. Un enlace de otro esquema, como `intent:`, `file:` o `javascript:`, nunca sale.
 */
fun isOpenableLink(url: String): Boolean {
    val parsed =
        try {
            URI(url.trim())
        } catch (_: URISyntaxException) {
            return false
        }
    return parsed.scheme == HTTPS && !parsed.host.isNullOrBlank() && parsed.rawUserInfo == null
}

/**
 * Abre [url] en el navegador del sistema, solo porque la persona lo tocó. La app no abre ninguna
 * conexión ni lee la respuesta: el navegador hace ese trabajo, con sus propias reglas. No usa
 * WebView.
 */
fun openInBrowser(context: Context, url: String): LinkOutcome {
    if (!isOpenableLink(url)) return LinkOutcome.NOT_ALLOWED
    return try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url.trim()))
                .addCategory(Intent.CATEGORY_BROWSABLE),
        )
        LinkOutcome.OPENED
    } catch (_: ActivityNotFoundException) {
        LinkOutcome.NO_APP
    }
}

private const val HTTPS = "https"

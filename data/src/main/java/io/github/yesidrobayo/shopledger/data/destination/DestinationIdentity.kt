package io.github.yesidrobayo.shopledger.data.destination

/**
 * Cómo se reconoce un destino WebDAV y cómo se llama su carpeta de copias.
 *
 * Son reglas puras, sin idioma ni región: el mismo destino da la misma huella y la misma carpeta en
 * cualquier teléfono, idioma o zona, porque de eso depende reconocer las copias al reconectar.
 */
internal object DestinationIdentity {
    /** Los caracteres hexadecimales de SHA-256 que forman la huella. */
    const val DESTINATION_ID_HEX_CHARS = 32

    /**
     * La huella del destino: los primeros 32 caracteres hexadecimales de SHA-256 de la dirección
     * normalizada, un salto de línea y el usuario. La dirección ya llega normalizada por
     * [WebDavEndpoint.parse]: host en minúsculas y en ASCII, sin el puerto 443 y con `/` final. Así
     * la misma dirección escrita de otra forma, con el mismo usuario, es el mismo destino, y otro
     * usuario en el mismo servicio es otro destino.
     */
    fun destinationId(endpoint: WebDavEndpoint, username: String): String {
        val text = endpoint.baseUrl.toString() + "\n" + username
        return newSha256().digest(
            text.toByteArray(Charsets.UTF_8),
        ).toLowerHex().take(DESTINATION_ID_HEX_CHARS)
    }

    /**
     * El nombre de la carpeta de copias a partir del nombre de la app, o `null` si no queda
     * ninguno usable.
     *
     * Sale del recurso que nunca se traduce, así que la carpeta es la misma en cualquier idioma. Se
     * quitan `/`, `\`, `:` y los caracteres de control, y los espacios de los extremos. No cambia
     * mayúsculas ni acentos: la persona debe reconocer el nombre de su app en el servicio, y pasar
     * a minúsculas dependería del idioma del teléfono.
     */
    fun folderName(appName: String): String? {
        val cleaned = appName.filterNot {
            it == '/' || it == '\\' || it == ':' || it.isISOControl()
        }.trim()
        return cleaned.takeIf { WebDavPath.isSafeSegment(it) }
    }
}

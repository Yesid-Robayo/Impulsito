package io.github.yesidrobayo.shopledger.domain.model.update

/**
 * Lo que dio bajar el APK de la versión anunciada, antes de comprobarlo. Bajarlo no instala nada:
 * el archivo todavía tiene que coincidir con el aviso firmado y con la firma de la app instalada.
 */
sealed interface ApkDownloadResult {
    /**
     * El archivo llegó entero y quedó en su lugar de nombre fijo.
     *
     * @property sha256 su huella SHA-256, en hexadecimal en minúsculas, calculada mientras se
     *   escribía.
     * @property bytes cuántos bytes tiene.
     */
    data class Downloaded(val sha256: String, val bytes: Long) : ApkDownloadResult

    /** Sin red, o el servidor no respondió: se puede reintentar. */
    data object Offline : ApkDownloadResult

    /** El archivo pasa el tope de tamaño: no se bajó, o se cortó al pasarlo. */
    data object TooLarge : ApkDownloadResult

    /** Respondió otra cosa, la descarga se cortó o el archivo no se pudo guardar. */
    data object Failed : ApkDownloadResult

    /**
     * La dirección no es una de las permitidas: un nombre de versión raro, una redirección a otro
     * host, a texto en claro o demasiadas redirecciones. No se siguió.
     */
    data object Refused : ApkDownloadResult
}

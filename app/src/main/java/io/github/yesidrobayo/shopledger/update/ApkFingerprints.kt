package io.github.yesidrobayo.shopledger.update

import java.security.MessageDigest

/**
 * Las cuentas del verificador de APK que no necesitan Android: huellas y la comparación de
 * firmantes. Se prueban en la JVM.
 */
object ApkFingerprints {
    private const val HEX_DIGITS = "0123456789abcdef"
    private const val NIBBLE_BITS = 4
    private const val NIBBLE_MASK = 0x0F
    private const val BYTE_MASK = 0xFF

    /** La huella SHA-256 de [bytes], en hexadecimal en minúsculas. */
    fun sha256Hex(bytes: ByteArray): String =
        hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /**
     * [bytes] en hexadecimal en minúsculas, sin separadores. Se arma a mano: un formato con el
     * idioma del teléfono podría cambiar las cifras.
     */
    fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and BYTE_MASK
            out.append(HEX_DIGITS[value shr NIBBLE_BITS])
            out.append(HEX_DIGITS[value and NIBBLE_MASK])
        }
        return out.toString()
    }

    /**
     * Una huella en hexadecimal como se lee en pantalla y en la página publicada: en mayúsculas,
     * de a dos cifras, separadas por `:`. `3f9a21` da `3F:9A:21`.
     */
    fun grouped(hex: String): String = hex.uppercase().chunked(2).joinToString(":")

    /**
     * Si un archivo con los firmantes [candidate] puede actualizar a la app con [own]. Cada
     * firmante va como la huella SHA-256 de su certificado.
     *
     * El archivo debe traer todos los certificados de la app instalada: así un firmante cambiado,
     * con su historia de rotación, sigue valiendo, y una firma ajena nunca. Sin firmantes propios,
     * nada coincide.
     */
    fun signersMatch(own: Set<String>, candidate: Set<String>): Boolean =
        own.isNotEmpty() && candidate.containsAll(own)
}

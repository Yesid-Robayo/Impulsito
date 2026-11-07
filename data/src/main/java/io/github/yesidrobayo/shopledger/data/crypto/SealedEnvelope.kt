package io.github.yesidrobayo.shopledger.data.crypto

/**
 * El sobre de un archivo sellado: versión, el alias de la clave de Keystore que lo selló, el
 * vector de inicio que generó Keystore y el texto cifrado con su etiqueta de autenticación al
 * final.
 *
 * El alias va en el encabezado para que el sobre diga con qué clave abre: así un archivo se puede
 * reemplazar por otro sellado con una clave nueva, y renombrarlo lleva su clave consigo, sin dejar
 * ilegible ni el de antes ni el nuevo. Todo el encabezado entra como dato autenticado del cifrado:
 * cambiar el alias o la versión hace que el sobre no abra.
 *
 * Es Kotlin puro, para probarlo sin dispositivo. Un sobre que no se puede leer se trata igual que
 * uno que no descifra: el contenido no se recupera.
 */
internal class SealedEnvelope(val alias: String, val iv: ByteArray, val ciphertext: ByteArray) {
    init {
        require(isValidAlias(alias)) { "Alias de clave no válido" }
        require(iv.size in 1..MAX_IV_BYTES) { "Vector de inicio fuera de rango" }
    }

    /** El encabezado, que también es el dato autenticado del cifrado. */
    val header: ByteArray get() = headerOf(alias, iv)

    fun encode(): ByteArray = header + ciphertext

    // Nunca imprime bytes: aunque estén cifrados, no van a un registro.
    override fun toString(): String = "SealedEnvelope(iv=${iv.size}, ciphertext=${ciphertext.size})"

    companion object {
        private val MAGIC = byteArrayOf(0x53, 0x4C, 0x53, 0x45)
        private const val VERSION: Byte = 1
        private const val MAX_IV_BYTES = 32
        private const val MAX_ALIAS_CHARS = 64

        // La etiqueta de GCM ocupa 16 bytes: un texto cifrado más corto no puede ser válido.
        private const val MIN_CIPHERTEXT_BYTES = 16

        /** Un alias de clave de la app: letras minúsculas, cifras y guiones, en ASCII. */
        fun isValidAlias(alias: String): Boolean = alias.length in 1..MAX_ALIAS_CHARS &&
            alias.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }

        /** El encabezado de un sobre sellado con [alias] y [iv]. */
        fun headerOf(alias: String, iv: ByteArray): ByteArray {
            val aliasBytes = alias.toByteArray(Charsets.US_ASCII)
            return MAGIC + byteArrayOf(VERSION, aliasBytes.size.toByte()) + aliasBytes +
                byteArrayOf(iv.size.toByte()) + iv
        }

        /** Solo el alias del encabezado, sin abrir nada, o `null` si no es un sobre de esta versión. */
        fun aliasOf(bytes: ByteArray): String? = decode(bytes)?.alias

        /** Devuelve `null` si los bytes no son un sobre de esta versión. */
        fun decode(bytes: ByteArray): SealedEnvelope? {
            var offset = MAGIC.size
            if (bytes.size < offset + 2) return null
            for (i in MAGIC.indices) if (bytes[i] != MAGIC[i]) return null
            if (bytes[offset++] != VERSION) return null
            val aliasSize = bytes[offset++].toInt() and 0xFF
            if (aliasSize !in 1..MAX_ALIAS_CHARS || bytes.size < offset + aliasSize + 1) return null
            val alias = String(bytes, offset, aliasSize, Charsets.US_ASCII)
            if (!isValidAlias(alias)) return null
            offset += aliasSize
            val ivSize = bytes[offset++].toInt() and 0xFF
            if (ivSize !in 1..MAX_IV_BYTES) return null
            val ciphertextStart = offset + ivSize
            if (bytes.size - ciphertextStart < MIN_CIPHERTEXT_BYTES) return null
            return SealedEnvelope(
                alias = alias,
                iv = bytes.copyOfRange(offset, ciphertextStart),
                ciphertext = bytes.copyOfRange(ciphertextStart, bytes.size),
            )
        }
    }
}

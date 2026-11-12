package io.github.yesidrobayo.shopledger.data.crypto

import com.google.crypto.tink.subtle.Hkdf
import io.github.yesidrobayo.shopledger.data.destination.toLowerHex
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * El código de recuperación de las copias cifradas: 130 bits aleatorios en 26 caracteres de
 * Crockford Base32, más 2 de control, impresos en 7 grupos de 4.
 *
 * La normalización, el control y las dos derivaciones son parte del formato público de las copias
 * cifradas: la herramienta de rescate en el PC las repite igual. Nada de aquí cambia sin cambiar el
 * formato, y los vectores de prueba lo vigilan.
 */
internal object RecoveryCodec {
    /** Crockford Base32: sin I, L, O ni U, para que el código no forme palabras ni se confunda. */
    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** Caracteres del código canónico, sin los de control. */
    const val CODE_LENGTH = 26

    /** Caracteres de control al final del código impreso. */
    const val CHECK_LENGTH = 2

    /** Bytes del identificador de la clave que va en la cabecera y en los metadatos. */
    const val KEY_ID_BYTES = 8

    /** Bytes de la clave de las copias. */
    const val BACKUP_KEY_BYTES = 32

    private const val GROUP_LENGTH = 4
    private const val SYMBOL_BITS = 5
    private const val BYTE_BITS = 8
    private const val BYTE_MASK = 0xFF
    private const val LOW_THREE_BITS = 0x07
    private const val HKDF_MAC = "HMACSHA256"
    private val SALT = "SLBK-v2".toByteArray(Charsets.US_ASCII)
    private val BACKUP_KEY_INFO = "backup-aead".toByteArray(Charsets.US_ASCII)
    private val KEY_ID_INFO = "key-id".toByteArray(Charsets.US_ASCII)

    /** Un código nuevo: cada carácter son 5 bits uniformes del generador del sistema, 130 en total. */
    fun generate(random: SecureRandom): RecoveryCode =
        RecoveryCode(String(CharArray(CODE_LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] }))

    /**
     * Lo escrito, como lo compara el formato: mayúsculas, sin guiones ni espacios, I y L pasan a 1,
     * y O pasa a 0. Cualquier otro carácter queda tal cual, para que [parse] lo rechace.
     */
    fun normalize(typed: String): String = buildString {
        for (char in typed.uppercase()) {
            when (char) {
                '-', ' ' -> Unit
                'I', 'L' -> append('1')
                'O' -> append('0')
                else -> append(char)
            }
        }
    }

    /**
     * Valida un código escrito con sus 2 caracteres de control, antes de usarlo para nada. Un error
     * de escritura nunca llega a derivar una clave.
     */
    fun parse(typed: String): CodeParseResult {
        val normalized = normalize(typed)
        if (normalized.length != CODE_LENGTH + CHECK_LENGTH) return CodeParseResult.WrongLength
        if (normalized.any { it !in ALPHABET }) return CodeParseResult.InvalidCharacter
        val code = normalized.substring(0, CODE_LENGTH)
        return if (checkCharacters(code) == normalized.substring(CODE_LENGTH)) {
            CodeParseResult.Valid(RecoveryCode(code))
        } else {
            CodeParseResult.CheckMismatch
        }
    }

    /** Los primeros 10 bits de SHA-256 del código canónico, en 2 caracteres de Crockford. */
    fun checkCharacters(canonical: String): String {
        val hash =
            MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.US_ASCII))
        val first = (hash[0].toInt() and BYTE_MASK) ushr (BYTE_BITS - SYMBOL_BITS)
        val second =
            ((hash[0].toInt() and LOW_THREE_BITS) shl 2) or ((hash[1].toInt() and BYTE_MASK) ushr 6)
        return "${ALPHABET[first]}${ALPHABET[second]}"
    }

    /** El código como se imprime y se escribe: 28 caracteres en 7 grupos de 4, con guiones. */
    fun printable(code: RecoveryCode): String = (code.canonical + checkCharacters(code.canonical))
        .chunked(GROUP_LENGTH)
        .joinToString("-")

    /** `BK`: la clave de las copias, que entra a Tink como material de `AesGcmHkdfStreaming`. */
    fun deriveBackupKey(code: RecoveryCode): ByteArray =
        Hkdf.computeHkdf(HKDF_MAC, code.ascii(), SALT, BACKUP_KEY_INFO, BACKUP_KEY_BYTES)

    /** `key_id`: dice qué código abre una copia sin revelar nada del código. */
    fun deriveKeyId(code: RecoveryCode): ByteArray =
        Hkdf.computeHkdf(HKDF_MAC, code.ascii(), SALT, KEY_ID_INFO, KEY_ID_BYTES)
}

/**
 * Los 26 caracteres canónicos de un código, sin los de control.
 *
 * Nunca se registra ni se convierte en texto de un log, una excepción o un `Intent`: su forma de
 * texto lo oculta.
 */
internal class RecoveryCode(val canonical: String) {
    init {
        val wellFormed =
            canonical.length == RecoveryCodec.CODE_LENGTH &&
                canonical.all { it in RecoveryCodec.ALPHABET }
        require(wellFormed) { "Código con forma inválida" }
    }

    fun ascii(): ByteArray = canonical.toByteArray(Charsets.US_ASCII)

    override fun equals(other: Any?): Boolean = other is RecoveryCode &&
        MessageDigest.isEqual(ascii(), other.ascii())

    override fun hashCode(): Int = canonical.hashCode()

    override fun toString(): String = "RecoveryCode(****)"
}

/** Lo que dio validar un código escrito. Ningún caso lleva lo escrito. */
internal sealed interface CodeParseResult {
    class Valid(val code: RecoveryCode) : CodeParseResult {
        override fun toString(): String = "Valid"
    }

    /** No tiene 28 caracteres tras normalizar. */
    data object WrongLength : CodeParseResult

    /** Lleva un carácter fuera del alfabeto, como una U. */
    data object InvalidCharacter : CodeParseResult

    /** Los 2 de control no coinciden: un error de escritura, detectado sin usar el código. */
    data object CheckMismatch : CodeParseResult
}

/**
 * La clave de las copias, derivada de un código, con su identificador. La clave nunca sale en
 * texto: ni en un log ni en un mensaje.
 */
internal class BackupKey private constructor(
    val keyId: ByteArray,
    private val material: ByteArray,
) {
    /** Una copia de los 32 bytes, solo para entregárselos a Tink. */
    fun material(): ByteArray = material.copyOf()

    /** El identificador en hexadecimal en minúscula, como va en los metadatos de la copia. */
    val keyIdHex: String get() = keyId.toLowerHex()

    fun matches(otherKeyId: ByteArray): Boolean = MessageDigest.isEqual(keyId, otherKeyId)

    override fun toString(): String = "BackupKey(****)"

    companion object {
        fun from(code: RecoveryCode): BackupKey =
            BackupKey(RecoveryCodec.deriveKeyId(code), RecoveryCodec.deriveBackupKey(code))
    }
}

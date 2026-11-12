package io.github.yesidrobayo.shopledger.data.crypto

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupHeaderField
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * La cabecera de una copia cifrada, en claro al comienzo del archivo y completa como datos
 * asociados del cifrado: cambiar un solo byte hace fallar el descifrado.
 *
 * La posición de cada campo y los valores que admiten los de lista cerrada salen de
 * [BackupHeaderField]. Todos los enteros van en big-endian. No lleva moneda, región ni zona: el
 * instante va en UTC y lo demás vive dentro de la base.
 *
 * Una foto usa la misma cabecera con [generation] y [schemaVersion] en cero.
 *
 * @property keyId los 8 bytes que identifican el código con que se cifró.
 * @property generation el orden de la copia. Cero en una foto.
 * @property schemaVersion la versión del esquema de la base. Cero en una foto.
 * @property createdAtUtcMillis cuándo se creó, en milisegundos desde 1970 en UTC.
 * @property deviceId la instalación que la creó.
 * @property rawSize bytes del archivo sin comprimir ni cifrar.
 * @property extensions extensiones ya codificadas, etiqueta, largo y valor. El formato 2 no define
 *   ninguna; las desconocidas se conservan para que la autenticación cubra lo que se leyó.
 */
internal class BackupHeader(
    keyId: ByteArray,
    val generation: Long,
    val schemaVersion: Long,
    val createdAtUtcMillis: Long,
    val deviceId: UUID,
    val rawSize: Long,
    extensions: ByteArray = ByteArray(0),
) {
    private val keyIdBytes = keyId.copyOf()
    private val extensionBytes = extensions.copyOf()

    init {
        require(keyId.size == RecoveryCodec.KEY_ID_BYTES) { "key_id debe tener 8 bytes" }
        require(generation >= 0) { "La generación no puede ser negativa" }
        require(schemaVersion in 0..UINT32_MAX) { "schema_version no cabe en 32 bits sin signo" }
        require(rawSize >= 0) { "raw_size no puede ser negativo" }
        require(FIXED_LENGTH + extensions.size <= MAX_LENGTH) { "Cabecera demasiado larga" }
        require(extensionsAreWellFormed(extensions)) { "Extensiones mal formadas" }
    }

    val keyId: ByteArray get() = keyIdBytes.copyOf()

    val extensions: ByteArray get() = extensionBytes.copyOf()

    /** Largo total de la cabecera, con sus extensiones. */
    val length: Int get() = FIXED_LENGTH + extensionBytes.size

    /** Los bytes exactos que van al archivo, y completos como datos asociados del cifrado. */
    fun encode(): ByteArray = ByteBuffer
        .allocate(length)
        .order(ByteOrder.BIG_ENDIAN)
        .apply {
            put(MAGIC)
            putShort(FORMAT_VERSION.toShort())
            putShort(length.toShort())
            put(AEAD_ID.toByte())
            put(KEY_SOURCE.toByte())
            put(PAYLOAD_CODEC.toByte())
            put(RESERVED.toByte())
            put(keyIdBytes)
            putLong(generation)
            putInt(schemaVersion.toInt())
            putLong(createdAtUtcMillis)
            putLong(deviceId.mostSignificantBits)
            putLong(deviceId.leastSignificantBits)
            putLong(rawSize)
            put(extensionBytes)
        }.array()

    // Nada de esto es secreto, pero la forma de texto no lleva bytes: no va a un registro.
    override fun toString(): String = "BackupHeader(length=$length)"

    companion object {
        /** `SLBK` y una cola que delata una transferencia en modo texto, como la firma de PNG. */
        val MAGIC: ByteArray
            get() = byteArrayOf(0x53, 0x4C, 0x42, 0x4B, 0x0D, 0x0A, 0x1A, 0x0A)

        /** Los dos primeros bytes de la copia en claro, el `.db.gz`. */
        val GZIP_MAGIC: ByteArray get() = byteArrayOf(0x1F, 0x8B.toByte())

        val FORMAT_VERSION: Int = BackupHeaderField.FORMAT_VERSION.closedValues.single()
        val AEAD_ID: Int = BackupHeaderField.AEAD_ID.closedValues.single()
        val KEY_SOURCE: Int = BackupHeaderField.KEY_SOURCE.closedValues.single()
        val PAYLOAD_CODEC: Int = BackupHeaderField.PAYLOAD_CODEC.closedValues.single()
        val RESERVED: Int = BackupHeaderField.RESERVED.closedValues.single()
        const val FIXED_LENGTH: Int = BackupHeaderField.FIXED_LENGTH
        const val MAX_LENGTH: Int = BackupHeaderField.MAX_LENGTH

        private const val UINT32_MAX = 0xFFFF_FFFFL
        private const val UINT16_MASK = 0xFFFF
        private const val BYTE_MASK = 0xFF
        private const val TLV_PREFIX = 4

        /** Lo que hay que leer para saber el formato y el largo de la cabecera. */
        private val PREFIX_LENGTH = BackupHeaderField.AEAD_ID.offset

        /**
         * Lee la cabecera desde el comienzo de [input] y la valida contra la lista cerrada **antes**
         * de usar ninguna clave. Si es válida, deja [input] justo al comienzo del flujo cifrado.
         */
        fun read(input: InputStream): HeaderReadResult {
            val prefix = ByteArray(PREFIX_LENGTH)
            val prefixRead = input.readFully(prefix)
            val gzip = GZIP_MAGIC
            if (prefixRead >= gzip.size && prefix[0] == gzip[0] && prefix[1] == gzip[1]) {
                return HeaderReadResult.PlainFormat
            }
            val magic = MAGIC
            if (prefixRead < magic.size || !prefix.copyOf(magic.size).contentEquals(magic)) {
                return HeaderReadResult.NotEncryptedCopy
            }
            if (prefixRead < PREFIX_LENGTH) return HeaderReadResult.Truncated
            val fixed = ByteBuffer.wrap(prefix).order(ByteOrder.BIG_ENDIAN)
            val formatVersion =
                fixed.getShort(BackupHeaderField.FORMAT_VERSION.offset).toInt() and UINT16_MASK
            if (formatVersion > FORMAT_VERSION) return HeaderReadResult.NewerFormat(formatVersion)
            if (formatVersion != FORMAT_VERSION) {
                return HeaderReadResult.Rejected(BackupHeaderField.FORMAT_VERSION)
            }
            val headerLength =
                fixed.getShort(BackupHeaderField.HEADER_LEN.offset).toInt() and UINT16_MASK
            if (headerLength !in FIXED_LENGTH..MAX_LENGTH) {
                return HeaderReadResult.Rejected(BackupHeaderField.HEADER_LEN)
            }
            val bytes = prefix.copyOf(headerLength)
            if (input.readFully(bytes, prefix.size) < headerLength - prefix.size) {
                return HeaderReadResult.Truncated
            }
            closedFieldOutOfList(bytes)?.let { return HeaderReadResult.Rejected(it) }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val generation = buffer.getLong(BackupHeaderField.GENERATION.offset)
            val rawSize = buffer.getLong(BackupHeaderField.RAW_SIZE.offset)
            // Un u64 con el bit alto no cabe en un Long: ninguna copia real llega ahí.
            if (generation < 0) return HeaderReadResult.Rejected(BackupHeaderField.GENERATION)
            if (rawSize < 0) return HeaderReadResult.Rejected(BackupHeaderField.RAW_SIZE)
            val extensions = bytes.copyOfRange(FIXED_LENGTH, headerLength)
            if (!extensionsAreWellFormed(extensions)) {
                return HeaderReadResult.Rejected(BackupHeaderField.EXTENSIONS)
            }
            val keyIdOffset = BackupHeaderField.KEY_ID.offset
            val deviceOffset = BackupHeaderField.DEVICE_ID.offset
            val header =
                BackupHeader(
                    keyId = bytes.copyOfRange(
                        keyIdOffset,
                        keyIdOffset + RecoveryCodec.KEY_ID_BYTES,
                    ),
                    generation = generation,
                    schemaVersion =
                        buffer.getInt(BackupHeaderField.SCHEMA_VERSION.offset).toLong() and
                            UINT32_MAX,
                    createdAtUtcMillis = buffer.getLong(BackupHeaderField.CREATED_AT_UTC_MS.offset),
                    deviceId = UUID(buffer.getLong(deviceOffset), buffer.getLong(deviceOffset + 8)),
                    rawSize = rawSize,
                    extensions = extensions,
                )
            return HeaderReadResult.Valid(header, bytes)
        }

        /** El primer campo de lista cerrada cuyo valor no está en la lista, o `null`. */
        private fun closedFieldOutOfList(bytes: ByteArray): BackupHeaderField? = listOf(
            BackupHeaderField.AEAD_ID,
            BackupHeaderField.KEY_SOURCE,
            BackupHeaderField.PAYLOAD_CODEC,
            BackupHeaderField.RESERVED,
        ).firstOrNull { field ->
            (bytes[field.offset].toInt() and BYTE_MASK) !in
                field.closedValues
        }

        /**
         * Las extensiones desconocidas se ignoran, pero deben llenar su espacio exacto: etiqueta y
         * largo de 2 bytes cada uno, y el valor.
         */
        private fun extensionsAreWellFormed(extensions: ByteArray): Boolean {
            val buffer = ByteBuffer.wrap(extensions).order(ByteOrder.BIG_ENDIAN)
            var position = 0
            while (position < extensions.size) {
                if (extensions.size - position < TLV_PREFIX) return false
                position += TLV_PREFIX + (buffer.getShort(position + 2).toInt() and UINT16_MASK)
            }
            return position == extensions.size
        }

        /** Lee hasta llenar [target] desde [offset] o hasta el final. Devuelve lo que leyó. */
        private fun InputStream.readFully(target: ByteArray, offset: Int = 0): Int {
            var total = 0
            while (offset + total < target.size) {
                val read =
                    try {
                        read(target, offset + total, target.size - offset - total)
                    } catch (_: EOFException) {
                        -1
                    }
                if (read < 0) break
                total += read
            }
            return total
        }
    }
}

/** Lo que dio leer la cabecera, sin haber usado ninguna clave. */
internal sealed interface HeaderReadResult {
    /** Cabecera dentro de la lista cerrada. [bytes] son los datos asociados exactos. */
    class Valid(val header: BackupHeader, val bytes: ByteArray) : HeaderReadResult {
        override fun toString(): String = "Valid"
    }

    /** Empieza con los bytes de gzip: es una copia en claro, válida siempre. */
    data object PlainFormat : HeaderReadResult

    /** Ni gzip ni la firma de una copia cifrada. */
    data object NotEncryptedCopy : HeaderReadResult

    /** Una versión del formato que esta app no conoce: pide actualizar, nunca "copia dañada". */
    data class NewerFormat(val formatVersion: Int) : HeaderReadResult

    /** Un valor fuera de la lista cerrada, rechazado antes de tocar ninguna clave. */
    data class Rejected(val field: BackupHeaderField) : HeaderReadResult

    /** El archivo se acaba dentro de la cabecera. */
    data object Truncated : HeaderReadResult
}

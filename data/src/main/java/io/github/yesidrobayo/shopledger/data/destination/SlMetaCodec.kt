package io.github.yesidrobayo.shopledger.data.destination

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupLimits
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupMetaKey
import io.github.yesidrobayo.shopledger.domain.model.backup.MetaValueForm
import java.io.InputStream

/**
 * Los metadatos que acompañan a una copia de la base en el destino, en el archivo `.slmeta`: lo
 * que hace falta para decidir antes de descargarla.
 *
 * Nunca llevan moneda, región ni zona, nombre del negocio, montos, dirección del servicio ni
 * usuario. Con el cifrado activo (formato 2) tampoco llevan el fabricante y modelo del teléfono ni
 * la versión de la app, porque el archivo va en claro.
 *
 * @property format `1` en claro, `2` cifrada.
 * @property keyId el identificador del código de recuperación, solo en formato 2.
 * @property deviceId la instalación que hizo la copia.
 * @property deviceLabel fabricante y modelo del teléfono, si se conocen y la copia va en claro.
 * @property generation el orden confiable entre copias.
 * @property schemaVersion la versión del esquema de la base copiada.
 * @property appVersion la versión de la app, solo para diagnosticar y si la copia va en claro.
 * @property sha256 la suma del archivo tal como queda en el destino.
 * @property rawSize el tamaño de la base sin comprimir: el espacio que hace falta para restaurar.
 * @property fileSize el tamaño del archivo en el destino.
 * @property records cuántos registros tiene la base.
 * @property catalogCount cuántos productos, proveedores y clientes tiene la base.
 * @property valid `false` si el validador encontró algo al crear la copia.
 * @property fatal si el validador dio un fallo fatal: la app no restaura esa copia.
 * @property pinned si la copia está fijada: nunca se poda.
 */
internal data class SlMeta(
    val format: Int,
    val keyId: String?,
    val deviceId: String,
    val deviceLabel: String?,
    val generation: Long,
    val schemaVersion: Long,
    val appVersion: String?,
    val sha256: String,
    val rawSize: Long,
    val fileSize: Long,
    val records: Long,
    val catalogCount: Long,
    val valid: Boolean,
    val fatal: Boolean = false,
    val pinned: Boolean = false,
)

/** La generación, la versión del esquema y el comienzo del `device_id` que da el nombre de la copia. */
internal data class CopyIdentity(
    val generation: Long,
    val schemaVersion: Long,
    val deviceIdPrefix: String,
)

/**
 * Lo que la app sabe de una copia por su `.slmeta`.
 *
 * Una copia sin `.slmeta` válido no cuenta para decidir si se puede subir ni para podar, pero se
 * ofrece para restaurar sin suma esperada: el gzip trae su propia comprobación, y la restauración
 * corre todas las demás.
 */
internal sealed interface CopyMetadata {
    /** Si la copia cuenta para decidir y para podar. */
    val decides: Boolean

    /** La suma con que se comprueba la descarga, o `null` si no hay ninguna confiable. */
    val expectedSha256: String?

    /** El `.slmeta` es válido y coincide con el nombre de su copia. */
    data class Valid(val meta: SlMeta) : CopyMetadata {
        override val decides: Boolean get() = true
        override val expectedSha256: String get() = meta.sha256
    }

    /** No hay `.slmeta` en que confiar, por [reason]. */
    data class Unusable(val reason: UnusableReason) : CopyMetadata {
        override val decides: Boolean get() = false
        override val expectedSha256: String? get() = null
    }
}

/** Por qué no se usa el `.slmeta` de una copia. Es un código para el registro, sin datos. */
internal enum class UnusableReason {
    /** No hay `.slmeta` junto a la copia. */
    MISSING,

    /** El archivo pasa de [SlMetaCodec.MAX_BYTES]. */
    TOO_LARGE,

    /** No es UTF-8, no es un objeto JSON plano, o una clave conocida no tiene su forma exacta. */
    MALFORMED,

    /** Es válido, pero su generación, su esquema o su teléfono no son los del nombre de la copia. */
    NAME_MISMATCH,
}

/**
 * Escribe y lee el `.slmeta`: un objeto JSON plano en UTF-8, de hasta [MAX_BYTES], con las claves
 * de [BackupMetaKey].
 *
 * Al leer, todo `.slmeta` es entrada no confiable, también si lo escribió la app:
 *
 * - El tamaño se cuenta al leer, y un archivo más grande ni se termina de leer.
 * - Debe ser UTF-8 válido y un solo objeto JSON plano, sin claves repetidas.
 * - Una clave `sl_*` desconocida se ignora: así una versión más nueva de la app puede agregar
 *   claves, y esta sigue contando y podando esas copias. Cada clave conocida debe tener su tipo de
 *   JSON y su forma exacta, y las obligatorias deben estar.
 * - Los números tienen límites: la generación y el esquema empiezan en 1, el esquema cabe en un
 *   entero de 32 bits, y los tamaños no pasan de lo que una copia puede pesar.
 * - Generación, esquema y teléfono deben coincidir con el nombre de su copia.
 *
 * Cada clave va siempre con el mismo tipo de JSON: los conteos, los tamaños, la generación, el
 * esquema, el formato, la marca de fatal y la de fijada, como número; `sl_valid` como booleano, y lo
 * demás como texto.
 */
internal object SlMetaCodec {
    /** 16 KiB: el mayor `.slmeta` que se acepta leer. */
    const val MAX_BYTES = 16 * 1024

    private const val KIND_BACKUP = "backup"
    private const val ENCRYPTED_FORMAT = 2
    private const val DEVICE_ID_PREFIX_LENGTH = 8

    /** Las claves sin las que un `.slmeta` de una copia de la base no sirve. */
    private val REQUIRED =
        setOf(
            BackupMetaKey.KIND,
            BackupMetaKey.FORMAT,
            BackupMetaKey.DEVICE_ID,
            BackupMetaKey.GENERATION,
            BackupMetaKey.SCHEMA_VERSION,
            BackupMetaKey.SHA256,
            BackupMetaKey.RAW_SIZE,
            BackupMetaKey.FILE_SIZE,
            BackupMetaKey.RECORDS,
            BackupMetaKey.CATALOG_COUNT,
            BackupMetaKey.VALID,
        )

    /** El `.slmeta` de [meta], en UTF-8, con las claves en el orden de [BackupMetaKey]. */
    fun encode(meta: SlMeta): ByteArray {
        val encrypted = meta.format == ENCRYPTED_FORMAT
        val values =
            mapOf(
                BackupMetaKey.KIND to KIND_BACKUP,
                BackupMetaKey.FORMAT to meta.format.toString(),
                BackupMetaKey.KEY_ID to meta.keyId,
                BackupMetaKey.DEVICE_ID to meta.deviceId,
                // Con el cifrado activo, el archivo va en claro: nada que identifique al teléfono.
                BackupMetaKey.DEVICE_LABEL to meta.deviceLabel.takeUnless { encrypted },
                BackupMetaKey.GENERATION to meta.generation.toString(),
                BackupMetaKey.SCHEMA_VERSION to meta.schemaVersion.toString(),
                BackupMetaKey.APP_VERSION to meta.appVersion.takeUnless { encrypted },
                BackupMetaKey.SHA256 to meta.sha256,
                BackupMetaKey.RAW_SIZE to meta.rawSize.toString(),
                BackupMetaKey.FILE_SIZE to meta.fileSize.toString(),
                BackupMetaKey.RECORDS to meta.records.toString(),
                BackupMetaKey.CATALOG_COUNT to meta.catalogCount.toString(),
                BackupMetaKey.VALID to meta.valid.toString(),
                BackupMetaKey.FATAL to FLAG.takeIf { meta.fatal },
                BackupMetaKey.PINNED to FLAG.takeIf { meta.pinned },
            )
        val members =
            BackupMetaKey.entries.mapNotNull { key ->
                values[key]?.let { text -> key.wireName to jsonValue(key, text) }
            }
        val bytes = FlatJson.write(members).encodeToByteArray()
        // Lo que la app escribe debe pasar por su propia lectura: si no, nunca decidiría con esa
        // copia, y nunca la podaría.
        check(bytes.size <= MAX_BYTES) { "El .slmeta pasa del tope" }
        val identity =
            CopyIdentity(
                meta.generation,
                meta.schemaVersion,
                meta.deviceId.take(DEVICE_ID_PREFIX_LENGTH),
            )
        check(decode(bytes, identity) == CopyMetadata.Valid(meta.normalized())) {
            "Los metadatos de la copia no tienen la forma que se lee"
        }
        return bytes
    }

    /**
     * El fabricante y el modelo del teléfono como texto de `sl_device_label`, o `null` si no queda
     * nada que mostrar. Quita los caracteres de control, junta los espacios y corta en el tope de
     * bytes sin partir un carácter. Lo da el sistema del teléfono, nunca la persona.
     */
    fun deviceLabel(manufacturer: String?, model: String?): String? {
        val form = BackupMetaKey.DEVICE_LABEL.form as MetaValueForm.DeviceLabel
        val clean =
            listOfNotNull(manufacturer, model)
                .joinToString(" ")
                .filterNot(Char::isISOControl)
                .split(' ')
                .filter(String::isNotEmpty)
                .joinToString(" ")
        var end = 0
        var bytes = 0
        while (end < clean.length) {
            // Un par sustituto es un solo carácter: entra entero o no entra.
            val pair =
                clean[end].isHighSurrogate() && clean.getOrNull(end + 1)?.isLowSurrogate() == true
            val width = if (pair) 2 else 1
            val size = clean.substring(end, end + width).encodeToByteArray().size
            if (bytes + size > form.maxUtf8Bytes) break
            bytes += size
            end += width
        }
        // Una mitad suelta de un par, o un texto vacío, no pasan la forma de la clave.
        return clean.substring(0, end).trimEnd().takeIf(form::accepts)
    }

    /**
     * Lee como mucho [MAX_BYTES] y un byte más de [input], sin cerrarlo. Devuelve `null` si el
     * archivo pasa del tope: el resto ni se lee.
     */
    fun readBounded(input: InputStream): ByteArray? {
        val buffer = ByteArray(MAX_BYTES + 1)
        var size = 0
        while (size < buffer.size) {
            val read = input.read(buffer, size, buffer.size - size)
            if (read < 0) break
            size += read
        }
        return if (size > MAX_BYTES) null else buffer.copyOf(size)
    }

    /**
     * Lo que dicen los bytes del `.slmeta` de la copia con el nombre [identity].
     *
     * @param bytes el contenido del `.slmeta`, o `null` si no hay ninguno junto a la copia.
     */
    fun decode(bytes: ByteArray?, identity: CopyIdentity): CopyMetadata {
        if (bytes == null) return CopyMetadata.Unusable(UnusableReason.MISSING)
        if (bytes.size > MAX_BYTES) return CopyMetadata.Unusable(UnusableReason.TOO_LARGE)
        val meta = parse(bytes) ?: return CopyMetadata.Unusable(UnusableReason.MALFORMED)
        val matches =
            meta.generation == identity.generation &&
                meta.schemaVersion == identity.schemaVersion &&
                identity.deviceIdPrefix.length == DEVICE_ID_PREFIX_LENGTH &&
                meta.deviceId.startsWith(identity.deviceIdPrefix)
        return if (matches) {
            CopyMetadata.Valid(meta)
        } else {
            CopyMetadata.Unusable(UnusableReason.NAME_MISMATCH)
        }
    }

    private fun parse(bytes: ByteArray): SlMeta? {
        val text =
            try {
                bytes.decodeToString(throwOnInvalidSequence = true)
            } catch (_: CharacterCodingException) {
                return null
            }
        val members = FlatJson.parse(text) ?: return null
        val values = HashMap<BackupMetaKey, String>()
        for ((name, value) in members) {
            // Una clave desconocida es de una versión más nueva de la app: se ignora.
            val key = BackupMetaKey.fromWireName(name) ?: continue
            if (value != jsonValue(key, value.text) || !key.form.accepts(value.text)) return null
            values[key] = value.text
        }
        if (!values.keys.containsAll(REQUIRED)) return null
        if (values[BackupMetaKey.KIND] != KIND_BACKUP) return null
        if (BackupMetaKey.MEDIA_SHA256 in values) return null
        return toMeta(values)
    }

    private fun toMeta(values: Map<BackupMetaKey, String>): SlMeta? {
        fun count(key: BackupMetaKey): Long? = values[key]?.let(MetaValueForm.Count::parse)
        val format = values.getValue(BackupMetaKey.FORMAT).toInt()
        val keyId = values[BackupMetaKey.KEY_ID]
        // El identificador de la clave va con el cifrado, y solo con él.
        if ((format == ENCRYPTED_FORMAT) != (keyId != null)) return null
        val valid = values.getValue(BackupMetaKey.VALID).toBooleanStrict()
        val fatal = BackupMetaKey.FATAL in values
        // Un fallo fatal siempre deja la copia inválida.
        if (fatal && valid) return null
        val generation = count(BackupMetaKey.GENERATION) ?: return null
        val schemaVersion = count(BackupMetaKey.SCHEMA_VERSION) ?: return null
        val rawSize = count(BackupMetaKey.RAW_SIZE) ?: return null
        val fileSize = count(BackupMetaKey.FILE_SIZE) ?: return null
        val inRange =
            generation >= 1 &&
                // El nombre de la copia y la base guardan la versión del esquema como entero de
                // 32 bits: una más grande no es de ninguna copia.
                schemaVersion in 1..Int.MAX_VALUE &&
                rawSize in 1..BackupLimits.MAX_RESTORE_DECOMPRESSED_BYTES &&
                fileSize in 1..BackupLimits.MAX_BACKUP_DOWNLOAD_BYTES
        if (!inRange) return null
        return SlMeta(
            format = format,
            keyId = keyId,
            deviceId = values.getValue(BackupMetaKey.DEVICE_ID),
            deviceLabel = values[BackupMetaKey.DEVICE_LABEL],
            generation = generation,
            schemaVersion = schemaVersion,
            appVersion = values[BackupMetaKey.APP_VERSION],
            sha256 = values.getValue(BackupMetaKey.SHA256),
            rawSize = rawSize,
            fileSize = fileSize,
            records = count(BackupMetaKey.RECORDS) ?: return null,
            catalogCount = count(BackupMetaKey.CATALOG_COUNT) ?: return null,
            valid = valid,
            fatal = fatal,
            pinned = BackupMetaKey.PINNED in values,
        )
    }

    /** El valor de JSON con que va [key]: siempre el mismo tipo para la misma clave. */
    private fun jsonValue(key: BackupMetaKey, text: String): FlatJson.Value = when (key) {
        BackupMetaKey.KIND,
        BackupMetaKey.KEY_ID,
        BackupMetaKey.MEDIA_SHA256,
        BackupMetaKey.DEVICE_ID,
        BackupMetaKey.DEVICE_LABEL,
        BackupMetaKey.APP_VERSION,
        BackupMetaKey.SHA256,
        -> FlatJson.Value.Text(text)

        BackupMetaKey.FORMAT,
        BackupMetaKey.GENERATION,
        BackupMetaKey.SCHEMA_VERSION,
        BackupMetaKey.RAW_SIZE,
        BackupMetaKey.FILE_SIZE,
        BackupMetaKey.RECORDS,
        BackupMetaKey.CATALOG_COUNT,
        BackupMetaKey.FATAL,
        BackupMetaKey.PINNED,
        -> FlatJson.Value.Number(text)

        BackupMetaKey.VALID -> FlatJson.Value.Bool(text)
    }

    /** Lo que se lee de lo que escribe [encode]: con cifrado no van ni el teléfono ni la versión. */
    private fun SlMeta.normalized(): SlMeta = if (format == ENCRYPTED_FORMAT) {
        copy(deviceLabel = null, appVersion = null)
    } else {
        this
    }

    private const val FLAG = "1"
}

package io.github.yesidrobayo.shopledger.domain.model.backup

/**
 * La lista cerrada de claves de los metadatos que acompañan a cada copia en el destino: el archivo
 * `.slmeta`, junto a la copia.
 *
 * Es la **única fuente** de esas claves: quien escribe o lee los metadatos lo hace a través de esta
 * lista, nunca con un texto escrito a mano. Solo llevan lo que hace falta para decidir antes de
 * descargar una copia, y nunca moneda, región ni zona: esos son datos del negocio, que viajan dentro
 * de la base. Si la copia dependiera de un supuesto regional, otro teléfono podría rechazarla. Una
 * prueba falla si una clave nueva usa una palabra que no está en el vocabulario neutro de estas
 * claves, o una forma de valor capaz de llevar una moneda, una región o una zona.
 *
 * El nombre en el archivo ([wireName]) no se traduce ni cambia con el nombre comercial: una copia
 * se restaura en cualquier idioma. Cada clave fija la forma exacta de su valor ([form]).
 */
enum class BackupMetaKey(
    /** El nombre exacto de la clave en el archivo. */
    val wireName: String,
    /** La forma que debe tener su valor. */
    val form: MetaValueForm,
) {
    /** Distingue una copia de la base de una foto: una foto nunca debe parecer una copia. */
    KIND("sl_kind", MetaValueForm.OneOf(listOf("backup", "media"))),

    /** El formato del archivo: `1` en claro, `2` cifrado. */
    FORMAT("sl_format", MetaValueForm.OneOf(listOf("1", "2"))),

    /**
     * Identifica el código de recuperación de una copia cifrada, para detectar otro código sin
     * descargarla.
     */
    KEY_ID("sl_key_id", MetaValueForm.LowerHex(KEY_ID_HEX_LENGTH)),

    /** Suma de una foto sin cifrar, que le da su nombre local. */
    MEDIA_SHA256("sl_media_sha256", MetaValueForm.LowerHex(SHA256_HEX_LENGTH)),

    /** El teléfono que hizo la copia. */
    DEVICE_ID("sl_device_id", MetaValueForm.Uuid),

    /** Fabricante y modelo de ese teléfono. No va con el cifrado activo. */
    DEVICE_LABEL("sl_device_label", MetaValueForm.DeviceLabel(DEVICE_LABEL_MAX_BYTES)),

    /** Generación de la copia: el orden confiable entre copias, que no depende del reloj. */
    GENERATION("sl_generation", MetaValueForm.Count),

    /** Versión del esquema de la base copiada. */
    SCHEMA_VERSION("sl_schema_version", MetaValueForm.Count),

    /** Versión de la app que hizo la copia, solo para diagnosticar. No va con el cifrado activo. */
    APP_VERSION("sl_app_version", MetaValueForm.AppVersion),

    /** Suma del archivo tal como queda en el destino. */
    SHA256("sl_sha256", MetaValueForm.LowerHex(SHA256_HEX_LENGTH)),

    /** Tamaño de la base sin comprimir: el espacio que hace falta para restaurar. */
    RAW_SIZE("sl_raw_size", MetaValueForm.Count),

    /** Tamaño del archivo en el destino, para notar una copia que el servicio truncó. */
    FILE_SIZE("sl_file_size", MetaValueForm.Count),

    /** Cuántos registros tiene la base, para detectar una que perdió datos y proteger la poda. */
    RECORDS("sl_records", MetaValueForm.Count),

    /** Cuántos productos, proveedores y clientes tiene la base. */
    CATALOG_COUNT("sl_catalog_count", MetaValueForm.Count),

    /** `false` si el validador encontró algo que rechazar o recalcular al crear la copia. */
    VALID("sl_valid", MetaValueForm.OneOf(listOf("true", "false"))),

    /** Presente solo si el validador dio un fallo fatal: la app no restaura esa copia. */
    FATAL("sl_fatal", MetaValueForm.OneOf(listOf("1"))),

    /** Presente solo si la copia está fijada: nunca se poda. */
    PINNED("sl_pinned", MetaValueForm.OneOf(listOf("1"))),
    ;

    companion object {
        /**
         * La clave con ese nombre exacto, o `null` si no es una clave de esta lista.
         *
         * Compara el texto tal cual, sin pasar a minúsculas: el nombre viene de un archivo no
         * confiable, y una clave tiene un solo nombre.
         */
        fun fromWireName(name: String): BackupMetaKey? = entries.firstOrNull { it.wireName == name }
    }
}

/** Un identificador de clave son 8 bytes: 16 cifras hexadecimales. */
private const val KEY_ID_HEX_LENGTH = 16

/** Una suma SHA-256 son 32 bytes: 64 cifras hexadecimales. */
private const val SHA256_HEX_LENGTH = 64

/** Tope del fabricante y el modelo juntos, en bytes UTF-8. */
private const val DEVICE_LABEL_MAX_BYTES = 124

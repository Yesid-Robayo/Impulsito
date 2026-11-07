package io.github.yesidrobayo.shopledger.data.destination

import io.github.yesidrobayo.shopledger.domain.model.backup.CopyInstant

/**
 * El nombre de un archivo de copia en el destino, como
 * `shopledger-backup-g00000042-s001-20261101T203012Z-7f3a9c1e.db.gz`.
 *
 * El nombre es informativo: sirve a la persona y a un rescate a mano, y dentro de la carpeta propia
 * sirve para reconocer una subida interrumpida. Nunca decide nada por sí solo: lo que vale está en
 * los metadatos de la copia y dentro de ella. Y siempre se usa como dato, nunca como ruta local.
 *
 * El prefijo no cambia con el nombre comercial ni con el idioma: así una copia vieja se reconoce
 * después de un cambio de nombre.
 *
 * @property generation la generación de la copia, de 0 en adelante.
 * @property schemaVersion la versión del esquema de la base copiada, de 1 en adelante.
 * @property createdAt el instante UTC del teléfono al crearla. El reloj puede estar mal: el orden
 *   confiable es la generación.
 * @property devicePrefix los primeros 8 caracteres del identificador del teléfono, en minúscula.
 * @property format el formato del archivo, que fija su extensión.
 */
internal data class BackupFileName(
    val generation: Long,
    val schemaVersion: Int,
    val createdAt: CopyInstant,
    val devicePrefix: String,
    val format: BackupFileFormat,
) {
    init {
        require(generation >= 0) { "La generación no puede ser negativa" }
        require(schemaVersion >= 1) { "La versión del esquema empieza en 1" }
        require(DEVICE_PREFIX.matches(devicePrefix)) { "El prefijo del teléfono no es válido" }
    }

    /** El nombre del archivo de la copia. */
    val fileName: String
        get() =
            PREFIX + "g" + generation.toString().padStart(GENERATION_DIGITS, '0') +
                "-s" + schemaVersion.toString().padStart(SCHEMA_DIGITS, '0') +
                "-" + createdAt + "-" + devicePrefix + format.extension

    /** El nombre de sus metadatos, que van al lado. */
    val metaFileName: String get() = fileName + META_SUFFIX

    override fun toString(): String = fileName

    companion object {
        /** El comienzo de todo archivo de copia de la app. */
        const val PREFIX = "shopledger-backup-"

        /** Lo que se agrega al nombre de una copia para nombrar sus metadatos. */
        const val META_SUFFIX = ".slmeta"

        /**
         * Lo que se agrega a un nombre mientras se sube. Nunca `.part` ni `.filepart`, que algunos
         * servicios prohíben. Un archivo con este sufijo nunca es una copia.
         */
        const val TEMPORARY_SUFFIX = ".sltmp"

        private const val GENERATION_DIGITS = 8
        private const val SCHEMA_DIGITS = 3

        private val DEVICE_PREFIX = Regex("[0-9a-f]{8}")

        /**
         * Lee el nombre exacto de un archivo de copia, o devuelve `null` si no lo es.
         *
         * El nombre viene de un servicio no confiable. Solo se acepta la forma que escribe la app,
         * con cifras ASCII, los ceros de relleno justos y un instante real: un nombre parecido se
         * ignora, igual que cualquier otro archivo de la carpeta.
         */
        fun parse(name: String): BackupFileName? {
            val match = NAME.matchEntire(name) ?: return null
            val (generationText, schemaText, stamp, prefix, extension) = match.destructured
            val generation = generationText.toLongOrNull() ?: return null
            val schemaVersion = schemaText.toIntOrNull() ?: return null
            if (schemaVersion < 1) return null
            val createdAt = CopyInstant.parse(stamp) ?: return null
            val format = BackupFileFormat.entries.first { it.extension == extension }
            val parsed = BackupFileName(generation, schemaVersion, createdAt, prefix, format)
            // Rechaza ceros de más, como g000000042: el nombre debe ser el que la app escribiría.
            return parsed.takeIf { it.fileName == name }
        }

        /**
         * El nombre de la copia de un archivo de metadatos, o `null` si [name] no es exactamente
         * los metadatos de una copia.
         */
        fun parseMeta(name: String): BackupFileName? =
            if (name.endsWith(META_SUFFIX)) parse(name.removeSuffix(META_SUFFIX)) else null

        // Solo cifras ASCII, escritas como clase explícita para que se lea sin dudas.
        private val NAME =
            Regex(
                "shopledger-backup-g([0-9]{8,19})-s([0-9]{3,10})-([0-9]{8}T[0-9]{6}Z)" +
                    "-([0-9a-f]{8})(\\.db\\.gz|\\.slbk)",
            )
    }
}

/** El formato de un archivo de copia, que decide su extensión y su tipo de contenido. */
internal enum class BackupFileFormat(
    /** La extensión del archivo, con su punto. */
    val extension: String,
    /** El `Content-Type` con que se sube. */
    val contentType: String,
) {
    /** La base comprimida con gzip, que se abre con herramientas comunes. */
    GZIP(".db.gz", "application/gzip"),

    /** La misma base, cifrada. */
    ENCRYPTED(".slbk", "application/octet-stream"),
}

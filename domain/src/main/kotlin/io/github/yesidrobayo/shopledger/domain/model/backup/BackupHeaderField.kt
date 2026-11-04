package io.github.yesidrobayo.shopledger.domain.model.backup

/**
 * Qué clase de valor guarda un campo de la cabecera binaria de una copia cifrada.
 *
 * Todas son números o bytes opacos: ninguna es texto. Por eso la cabecera no puede llevar una
 * moneda, una región ni una zona, y una prueba exige que siga así.
 */
enum class HeaderValueKind {
    /** Bytes fijos que reconocen el formato. */
    SIGNATURE,

    /** Un número de una lista cerrada, como la versión del formato o el algoritmo. */
    CLOSED_CODE,

    /** Un entero sin signo: largo, generación, versión de esquema o tamaño. */
    UNSIGNED_INTEGER,

    /** Un instante en milisegundos desde 1970-01-01T00:00Z. Siempre UTC, nunca hora local. */
    UTC_EPOCH_MILLIS,

    /** Bytes opacos que identifican algo, como el teléfono o el código de recuperación. */
    IDENTIFIER,

    /** Extensiones etiquetadas, autenticadas con el resto. Las desconocidas se ignoran. */
    TAGGED_EXTENSIONS,
}

/**
 * La lista cerrada de campos de la cabecera binaria de una copia cifrada, en su orden dentro del
 * archivo.
 *
 * La cabecera es autodescriptiva, va completa como datos autenticados del cifrado, y no lleva
 * moneda, región ni zona: todo instante es UTC y lo demás vive dentro de la base. Esta lista fija
 * qué campos hay, dónde está cada uno y qué valores admiten los de lista cerrada ([closedValues]):
 * el lector de la cabecera rechaza un valor fuera de esa lista antes de usar ninguna clave.
 *
 * Los enteros van en big-endian. [EXTENSIONS] es el único campo de largo variable, y el último.
 */
enum class BackupHeaderField(
    /** El nombre del campo en la especificación del formato. */
    val wireName: String,
    /** Desde qué byte de la cabecera empieza. */
    val offset: Int,
    /** Cuántos bytes ocupa, o `null` si su largo es variable. */
    val size: Int?,
    /** Qué clase de valor guarda. */
    val kind: HeaderValueKind,
    /** Los únicos valores que admite, si su clase es [HeaderValueKind.CLOSED_CODE]. */
    val closedValues: List<Int> = emptyList(),
) {
    /** `SLBK` y una cola que detecta una transferencia en modo texto. */
    MAGIC("magic", 0, 8, HeaderValueKind.SIGNATURE),

    /** Una versión mayor que la conocida pide actualizar la app, nunca dice "copia dañada". */
    FORMAT_VERSION("format_version", 8, 2, HeaderValueKind.CLOSED_CODE, listOf(2)),

    /** Largo total de la cabecera, extensiones incluidas. */
    HEADER_LEN("header_len", 10, 2, HeaderValueKind.UNSIGNED_INTEGER),

    /** El cifrado autenticado en flujo. */
    AEAD_ID("aead_id", 12, 1, HeaderValueKind.CLOSED_CODE, listOf(1)),

    /** De dónde sale la clave: el código de recuperación. */
    KEY_SOURCE("key_source", 13, 1, HeaderValueKind.CLOSED_CODE, listOf(1)),

    /** La compresión del contenido: gzip. */
    PAYLOAD_CODEC("payload_codec", 14, 1, HeaderValueKind.CLOSED_CODE, listOf(1)),

    /** Reservado, siempre cero. */
    RESERVED("reserved", 15, 1, HeaderValueKind.CLOSED_CODE, listOf(0)),

    /** Identifica el código de recuperación sin revelarlo. */
    KEY_ID("key_id", 16, 8, HeaderValueKind.IDENTIFIER),

    /** Generación de la copia. Cero en una foto. */
    GENERATION("generation", 24, 8, HeaderValueKind.UNSIGNED_INTEGER),

    /** Versión del esquema de la base. Cero en una foto. */
    SCHEMA_VERSION("schema_version", 32, 4, HeaderValueKind.UNSIGNED_INTEGER),

    /** Cuándo se creó la copia, en UTC. */
    CREATED_AT_UTC_MS("created_at_utc_ms", 36, 8, HeaderValueKind.UTC_EPOCH_MILLIS),

    /** El teléfono que hizo la copia, como UUID binario. */
    DEVICE_ID("device_id", 44, 16, HeaderValueKind.IDENTIFIER),

    /** Bytes del archivo sin cifrar ni comprimir. */
    RAW_SIZE("raw_size", 60, 8, HeaderValueKind.UNSIGNED_INTEGER),

    /**
     * Etiqueta, largo y valor, hasta completar el largo de la cabecera. El formato 2 no define
     * ninguna.
     */
    EXTENSIONS("extensions", 68, null, HeaderValueKind.TAGGED_EXTENSIONS),
    ;

    companion object {
        /** Largo de la parte fija: la cabecera más corta posible, sin extensiones. */
        const val FIXED_LENGTH: Int = 68

        /** Largo máximo de la cabecera completa. */
        const val MAX_LENGTH: Int = 4096
    }
}

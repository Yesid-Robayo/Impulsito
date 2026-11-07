package io.github.yesidrobayo.shopledger.data.backup

import java.io.File
import java.io.FileInputStream

/**
 * Lee el encabezado de una base candidata por sus bytes, antes de que SQLite la abra.
 *
 * Abrir un archivo con SQLite ya es confiar un poco en él. Por eso primero se miran sus primeros
 * 100 bytes, que el formato de SQLite fija: la firma, el modo de diario, el tamaño de página, el
 * número de páginas y la versión del esquema que guarda la app.
 *
 * Solo vale para candidatos. En la base activa la versión del esquema se lee con `PRAGMA`, porque con
 * WAL sin vaciar los bytes del archivo pueden estar atrasados.
 */
internal object CandidateHeader {
    /** Los bytes del encabezado de una base de SQLite. */
    const val SIZE = 100

    /**
     * Revisa el encabezado de [file] para la app con esquema [appSchemaVersion].
     *
     * Devuelve la versión del esquema de la copia, o por qué se rechaza.
     */
    fun read(file: File, appSchemaVersion: Int): HeaderResult {
        val length = file.length()
        if (length < SIZE) return HeaderResult.Rejected(CandidateRejection.NOT_A_DATABASE)
        val bytes = ByteArray(SIZE)
        FileInputStream(file).use { input ->
            var read = 0
            while (read < SIZE) {
                val count = input.read(bytes, read, SIZE - read)
                if (count < 0) return HeaderResult.Rejected(CandidateRejection.NOT_A_DATABASE)
                read += count
            }
        }
        return parse(bytes, length, appSchemaVersion)
    }

    /**
     * Revisa [header], los primeros [SIZE] bytes de un archivo de [fileLength] bytes.
     *
     * - La firma es `SQLite format 3` con su cero final.
     * - Las versiones de escritura y de lectura valen 1, el diario normal. Con 2 la base está en
     *   WAL: abrirla dejaría archivos a su lado, y las copias de la app nunca lo están.
     * - El tamaño de página es una potencia de dos entre 512 y 65.536, y el archivo es un número
     *   entero de páginas. Si el encabezado dice cuántas tiene, y el contador que lo valida coincide,
     *   el archivo no tiene menos: así se rechaza una base cortada antes de leerla.
     * - La versión del esquema que guarda la app va de 1 a [appSchemaVersion]. Mayor viene de una
     *   app más nueva.
     */
    fun parse(header: ByteArray, fileLength: Long, appSchemaVersion: Int): HeaderResult {
        require(header.size >= SIZE) { "El encabezado de SQLite tiene $SIZE bytes" }
        val rejected = when {
            !header.copyOf(MAGIC.size).contentEquals(MAGIC) -> CandidateRejection.NOT_A_DATABASE

            header[WRITE_VERSION_OFFSET].toInt() == WAL_VERSION ||
                header[READ_VERSION_OFFSET].toInt() == WAL_VERSION ->
                CandidateRejection.WAL_DATABASE

            header[WRITE_VERSION_OFFSET].toInt() != ROLLBACK_VERSION ||
                header[READ_VERSION_OFFSET].toInt() != ROLLBACK_VERSION ->
                CandidateRejection.NOT_A_DATABASE

            else -> pageProblem(header, fileLength)
        }
        if (rejected != null) return HeaderResult.Rejected(rejected)
        val version = int32(header, USER_VERSION_OFFSET)
        return when {
            version < 1 -> HeaderResult.Rejected(CandidateRejection.UNKNOWN_SCHEMA_VERSION)
            version > appSchemaVersion -> HeaderResult.Rejected(CandidateRejection.SCHEMA_TOO_NEW)
            else -> HeaderResult.Valid(schemaVersion = version.toInt())
        }
    }

    private fun pageProblem(header: ByteArray, fileLength: Long): CandidateRejection? {
        val stored = int16(header, PAGE_SIZE_OFFSET)
        // El valor 1 significa 65.536, que no cabe en dos bytes.
        val pageSize = if (stored == 1L) MAX_PAGE_SIZE else stored
        val validSize =
            pageSize in MIN_PAGE_SIZE..MAX_PAGE_SIZE && pageSize and (pageSize - 1) == 0L
        if (!validSize || fileLength % pageSize != 0L) return CandidateRejection.NOT_A_DATABASE
        val declaredPages = int32(header, PAGE_COUNT_OFFSET)
        val countIsValid = int32(header, CHANGE_COUNTER_OFFSET) ==
            int32(header, VERSION_VALID_FOR_OFFSET)
        return if (countIsValid && declaredPages > 0 && fileLength < declaredPages * pageSize) {
            CandidateRejection.NOT_A_DATABASE
        } else {
            null
        }
    }

    private fun int16(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and BYTE) shl 8) or (bytes[offset + 1].toLong() and BYTE)

    /** Un entero de 4 bytes en orden de red, sin signo para los contadores y con signo si no. */
    private fun int32(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in 0 until 4) value = (value shl 8) or (bytes[offset + index].toLong() and BYTE)
        return value.toInt().toLong()
    }

    private const val BYTE = 0xFFL
    private const val PAGE_SIZE_OFFSET = 16
    private const val WRITE_VERSION_OFFSET = 18
    private const val READ_VERSION_OFFSET = 19
    private const val CHANGE_COUNTER_OFFSET = 24
    private const val PAGE_COUNT_OFFSET = 28
    private const val USER_VERSION_OFFSET = 60
    private const val VERSION_VALID_FOR_OFFSET = 92
    private const val ROLLBACK_VERSION = 1
    private const val WAL_VERSION = 2
    private const val MIN_PAGE_SIZE = 512L
    private const val MAX_PAGE_SIZE = 65_536L

    /** `SQLite format 3` y un cero, en ASCII. */
    private val MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
}

/** Lo que dio leer el encabezado de un candidato. */
internal sealed interface HeaderResult {
    /** Un encabezado de SQLite sano, con la versión del esquema que guarda la app. */
    data class Valid(val schemaVersion: Int) : HeaderResult

    /** Se rechaza sin abrir el archivo. */
    data class Rejected(val reason: CandidateRejection) : HeaderResult
}

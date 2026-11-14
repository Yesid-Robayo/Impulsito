package io.github.yesidrobayo.shopledger.domain.model.imports

/**
 * Los topes con que se lee un archivo para importar, que es entrada no confiable: lo hizo otra app
 * u otra persona, y puede venir dañado o armado a propósito para colgar el teléfono.
 *
 * Se comprueban mientras se lee, antes de procesar nada: superar uno rechaza el archivo entero,
 * sin tocar la base. Los cuatro primeros son los de las reglas de negocio y son valores de diseño.
 * Con las 13 columnas de los productos, 50.000 filas ocupan cerca de 10 MiB en CSV y 32 MiB de
 * hoja descomprimida, así que los topes dejan holgura sobre el mayor archivo razonable.
 */
data class ImportLimits(
    /** Bytes del archivo elegido, CSV o `.xlsx`, tal como llega. */
    val maxFileBytes: Long = MAX_IMPORT_FILE_BYTES,
    /** Bytes descomprimidos de cada entrada de un `.xlsx`. */
    val maxEntryBytes: Long = MAX_IMPORT_ENTRY_BYTES,
    /** Bytes descomprimidos de todo un `.xlsx`. */
    val maxTotalBytes: Long = MAX_IMPORT_TOTAL_BYTES,
    /** Filas de datos, sin contar el encabezado ni las filas en blanco. */
    val maxRows: Int = MAX_IMPORT_ROWS,
    /** Columnas de una fila. */
    val maxColumns: Int = MAX_IMPORT_COLUMNS,
    /** Caracteres de una celda. */
    val maxCellChars: Int = MAX_IMPORT_CELL_CHARS,
) {
    init {
        require(maxFileBytes > 0 && maxEntryBytes > 0 && maxTotalBytes > 0) {
            "Los topes de bytes son positivos"
        }
        require(maxRows > 0 && maxColumns > 0 && maxCellChars > 0) {
            "Los topes de filas, columnas y celdas son positivos"
        }
    }

    companion object {
        private const val MIB: Long = 1024L * 1024

        /** Tamaño del archivo elegido: 20 MiB. */
        const val MAX_IMPORT_FILE_BYTES: Long = 20 * MIB

        /** Bytes descomprimidos de cada entrada de un `.xlsx`: 64 MiB. */
        const val MAX_IMPORT_ENTRY_BYTES: Long = 64 * MIB

        /** Bytes descomprimidos de todo un `.xlsx`: 128 MiB. */
        const val MAX_IMPORT_TOTAL_BYTES: Long = 128 * MIB

        /** Filas de datos por archivo. */
        const val MAX_IMPORT_ROWS: Int = 50_000

        /**
         * Columnas por fila. La tabla más ancha de la exportación tiene menos de 20: 256 alcanzan
         * para una hoja con columnas propias de la persona, y cortan una fila armada para gastar
         * memoria.
         */
        const val MAX_IMPORT_COLUMNS: Int = 256

        /**
         * Caracteres por celda. El texto más largo que acepta la app, una nota, tiene 1.000: una
         * celda de más de 4.096 no es un dato de negocio. Las celdas que caben aquí pero pasan el
         * largo de su campo son error de su fila, no del archivo.
         */
        const val MAX_IMPORT_CELL_CHARS: Int = 4_096
    }
}

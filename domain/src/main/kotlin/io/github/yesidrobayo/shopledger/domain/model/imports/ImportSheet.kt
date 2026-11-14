package io.github.yesidrobayo.shopledger.domain.model.imports

import io.github.yesidrobayo.shopledger.domain.model.settings.CsvSeparator

/**
 * Lo que trae un archivo para importar, ya leído y dentro de los topes, todavía sin interpretar.
 *
 * Cada celda es texto tal como estaba en el archivo, sin recortar ni convertir: nada se ejecuta ni
 * se abre. Reconocer las columnas y leer números, fechas y montos viene después, con las reglas de
 * cada campo.
 */
data class ImportSheet(
    /** Los encabezados del archivo: la primera fila que no está en blanco. */
    val header: List<String>,
    /** Las filas de datos, sin las filas en blanco, en el orden del archivo. */
    val rows: List<ImportRow>,
    /**
     * El separador de columnas con que se leyó un CSV, o `null` si vino de una hoja de cálculo.
     */
    val separator: CsvSeparator?,
)

/**
 * Una fila de datos del archivo.
 *
 * [number] es el número que la persona ve en su hoja de cálculo, con el encabezado y las filas en
 * blanco contados: así un error dice `Fila 17` y la persona encuentra la fila 17. Una celda con un
 * salto de línea adentro no suma filas, igual que en la hoja.
 */
data class ImportRow(
    /** El número de la fila en la hoja, desde 1. */
    val number: Int,
    /** Las celdas, en el orden de las columnas. Puede traer más o menos que el encabezado. */
    val cells: List<String>,
) {
    /** La celda de la columna [index], o vacía si la fila no llega a esa columna. */
    fun cell(index: Int): String = cells.getOrElse(index) { "" }
}

/** Cómo terminó leer un archivo para importar. */
sealed interface ImportFileRead {
    /**
     * Se leyó entero y dentro de los topes. [fileName] es el nombre que dio el selector, solo para
     * mostrarlo: nunca se usa como ruta.
     */
    data class Read(val sheet: ImportSheet, val fileName: String?) : ImportFileRead

    /** No se puede importar, por [reason]. No se procesó nada y la base no cambió. */
    data class Rejected(val reason: ImportRejection) : ImportFileRead
}

/** Por qué se rechazó un archivo entero, antes de importar nada. */
enum class ImportRejection {
    /** El archivo, o lo que descomprime un `.xlsx`, supera su tope de bytes. */
    TOO_LARGE,

    /** Trae más filas de datos que el tope. */
    TOO_MANY_ROWS,

    /** Una fila trae más columnas que el tope. */
    TOO_MANY_COLUMNS,

    /** Una celda trae más caracteres que el tope. */
    CELL_TOO_LARGE,

    /** No trae filas de datos: está vacío, en blanco o solo tiene el encabezado. */
    EMPTY,

    /** Está cortado o mal armado: una comilla que no cierra o texto pegado a una comilla final. */
    MALFORMED,

    /** No es texto UTF-8: otra codificación, que rompería las tildes sin avisar. */
    ENCODING,

    /** No es un tipo de archivo que se pueda leer: un binario, o una hoja de cálculo dañada. */
    UNSUPPORTED_TYPE,

    /** No se pudo abrir ni leer: el documento ya no está, o se perdió el permiso. */
    UNREADABLE,
}

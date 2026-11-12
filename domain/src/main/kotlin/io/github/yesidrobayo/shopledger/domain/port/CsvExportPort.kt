package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import io.github.yesidrobayo.shopledger.domain.model.export.CsvDateRange
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExport
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExportProgress
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExportTable
import io.github.yesidrobayo.shopledger.domain.rule.export.CsvRowWriter

/**
 * Exportar los datos del negocio a un `.zip` con un CSV por cada archivo de [CsvExportTable], en el
 * documento que la persona creó con el selector del sistema. Solo lee la base, en una sola lectura
 * para que todos los archivos cuenten lo mismo, y sin red. Lo implementa la capa de datos.
 */
interface CsvExportPort {
    /**
     * Escribe la exportación en el documento de [request] y la relee. Llama a [progress] al terminar
     * cada archivo, desde el hilo donde exporta.
     */
    suspend fun export(request: CsvExportRequest, progress: (CsvExportProgress) -> Unit): CsvExport
}

/**
 * Lo que una exportación necesita además de la base.
 *
 * @property documentUri el documento ya creado por el selector.
 * @property rows escribe cada línea con el formato regional del negocio.
 * @property naming los nombres de archivos, columnas y filas iniciales.
 * @property range las fechas que entran, o `null` para exportar todo.
 */
class CsvExportRequest(
    val documentUri: String,
    val rows: CsvRowWriter,
    val naming: CsvNaming,
    val range: CsvDateRange? = null,
)

/**
 * Los nombres que la exportación no guarda en la base: los de los archivos, los de las columnas y el
 * de cada fila inicial que nadie renombró, como la unidad o la caja. Los da la interfaz, que tiene
 * los textos.
 */
interface CsvNaming {
    /** El nombre del archivo de [table] dentro del `.zip`, con su extensión. */
    fun fileName(table: CsvExportTable): String

    /** El encabezado de la columna con la clave [column]. */
    fun header(column: String): String

    /** El nombre visible de la fila inicial [systemKey] de [table]. */
    fun seedName(table: SeedTable, systemKey: String): String
}

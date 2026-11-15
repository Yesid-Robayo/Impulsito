package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.export.CsvDateRange
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExport
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExportProgress
import io.github.yesidrobayo.shopledger.domain.model.report.SummaryFile
import io.github.yesidrobayo.shopledger.domain.rule.export.CsvRowWriter

/**
 * Escribe el paquete del `Resumen de gestión`: un `.zip` con el PDF de resumen y un CSV por cada
 * archivo pedido, con las fechas del período. Lee la base en una sola lectura, para que todos los
 * archivos cuenten lo mismo, y sin red. Lo implementa la capa de datos.
 */
interface ManagementSummaryPackagePort {
    /**
     * Escribe el paquete de [request] y lo relee. Llama a [progress] al terminar cada archivo. Si
     * algo falla, no deja el archivo. Al terminar bien, anota la exportación en la auditoría, sin
     * montos ni nombres.
     */
    suspend fun write(
        request: SummaryPackageRequest,
        progress: (CsvExportProgress) -> Unit,
    ): CsvExport
}

/**
 * Lo que el paquete necesita además de la base.
 *
 * @property targetPath el archivo que se crea, dentro de la carpeta de lo que sale de la app.
 * @property files los archivos CSV, en el orden en que entran.
 * @property rows escribe cada línea con el formato regional del negocio.
 * @property naming los nombres de archivos y columnas en el idioma activo.
 * @property range las fechas del período: filtran documentos y movimientos, y el inventario al
 *   cierre se suma hasta su último día.
 * @property pdfName el nombre del PDF dentro del paquete, con su extensión.
 * @property pdfPath el PDF ya escrito por la interfaz, que entra tal cual como primer archivo.
 */
class SummaryPackageRequest(
    val targetPath: String,
    val files: List<SummaryFile>,
    val rows: CsvRowWriter,
    val naming: SummaryNaming,
    val range: CsvDateRange,
    val pdfName: String,
    val pdfPath: String,
)

/** Los nombres de la exportación, más el del archivo que solo lleva el resumen. */
interface SummaryNaming : CsvNaming {
    /** El nombre del inventario al cierre dentro del paquete, con su extensión. */
    fun closingInventoryFileName(): String
}

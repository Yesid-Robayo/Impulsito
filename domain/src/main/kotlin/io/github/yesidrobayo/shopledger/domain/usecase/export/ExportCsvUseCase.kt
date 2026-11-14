package io.github.yesidrobayo.shopledger.domain.usecase.export

import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.export.CsvDateRange
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExport
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExportProgress
import io.github.yesidrobayo.shopledger.domain.model.region.DecimalSeparator
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.CsvExportPort
import io.github.yesidrobayo.shopledger.domain.port.CsvExportRequest
import io.github.yesidrobayo.shopledger.domain.port.CsvNaming
import io.github.yesidrobayo.shopledger.domain.rule.export.CsvRowWriter
import io.github.yesidrobayo.shopledger.domain.rule.export.SheetCellWriter
import io.github.yesidrobayo.shopledger.domain.rule.region.CsvFormat

/**
 * `Exportar a CSV`: arma el formato de las celdas con la configuración regional guardada del negocio,
 * nunca con la del teléfono, y pide la exportación a [export].
 *
 * El separador de columnas, los decimales de la moneda y la zona salen de los ajustes; el separador
 * decimal sale del formato regional por [decimalSeparator], que solo la interfaz sabe leer. Con una
 * zona que este teléfono no conoce, como la de una copia de un teléfono más nuevo, no exporta: las
 * fechas y horas saldrían en otra zona sin decirlo.
 *
 * Con un rango, solo entran los documentos y movimientos de esas fechas de negocio; los catálogos van
 * completos.
 *
 * Con `spreadsheet`, la exportación es un `.xlsx` con una hoja por archivo: los números van como
 * celdas numéricas con punto, que la hoja muestra con la región de quien la abre, y el texto como
 * texto neutralizado. Los encabezados y los nombres de las hojas salen en el idioma activo.
 */
class ExportCsvUseCase(
    private val settings: AppSettingsRepository,
    private val export: CsvExportPort,
    private val decimalSeparator: (formatLocaleTag: String) -> DecimalSeparator,
) {
    suspend operator fun invoke(
        documentUri: String,
        naming: CsvNaming,
        range: CsvDateRange? = null,
        spreadsheet: Boolean = false,
        progress: (CsvExportProgress) -> Unit,
    ): CsvExport {
        val regional = settings.get().regional
        val zone = BusinessTimeZone.of(regional.businessTimeZoneId) ?: return CsvExport.Failed
        val format =
            CsvFormat(
                regional.csvSeparator,
                decimalSeparator(regional.formatLocaleTag),
                regional.currency.decimals,
            )
        return export.export(
            CsvExportRequest(
                documentUri = documentUri,
                rows = CsvRowWriter(format, zone),
                naming = naming,
                range = range,
                spreadsheet = if (spreadsheet) {
                    SheetCellWriter(regional.currency.decimals, zone)
                } else {
                    null
                },
            ),
            progress,
        )
    }
}

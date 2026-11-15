package io.github.yesidrobayo.shopledger.domain.usecase.report

import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.export.CsvDateRange
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExport
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExportProgress
import io.github.yesidrobayo.shopledger.domain.model.region.DecimalSeparator
import io.github.yesidrobayo.shopledger.domain.model.report.SummaryContent
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.ManagementSummaryPackagePort
import io.github.yesidrobayo.shopledger.domain.port.SummaryNaming
import io.github.yesidrobayo.shopledger.domain.port.SummaryPackageRequest
import io.github.yesidrobayo.shopledger.domain.rule.export.CsvRowWriter
import io.github.yesidrobayo.shopledger.domain.rule.region.CsvFormat
import io.github.yesidrobayo.shopledger.domain.rule.report.ManagementSummaryRules

/**
 * `Generar resumen`: arma el paquete del `Resumen de gestión` de un período con el PDF que ya
 * dibujó la interfaz y los CSV de lo que la persona dejó encendido.
 *
 * Los CSV siguen el idioma activo, el separador de columnas guardado y el separador decimal del
 * formato regional del negocio, igual que `Exportar a CSV`, nunca la región del teléfono. Con una
 * zona que este teléfono no conoce no genera: las horas saldrían en otra zona sin decirlo.
 *
 * Solo lee la base: no escribe ningún dato del negocio, salvo la marca de exportación en la
 * auditoría, sin montos ni nombres.
 */
class CreateManagementSummaryUseCase(
    private val settings: AppSettingsRepository,
    private val packages: ManagementSummaryPackagePort,
    private val decimalSeparator: (formatLocaleTag: String) -> DecimalSeparator,
) {
    /**
     * Escribe el paquete de [period] donde dice [target], con su PDF, y los archivos de [chosen]
     * que [offered] ofrece.
     */
    suspend operator fun invoke(
        period: ReportPeriod,
        chosen: Set<SummaryContent>,
        offered: List<SummaryContent>,
        target: SummaryTarget,
        naming: SummaryNaming,
        progress: (CsvExportProgress) -> Unit,
    ): CsvExport {
        val regional = settings.get().regional
        val zone = BusinessTimeZone.of(regional.businessTimeZoneId) ?: return CsvExport.Failed
        val range = CsvDateRange.of(period.start, period.end) ?: return CsvExport.Failed
        val format =
            CsvFormat(
                regional.csvSeparator,
                decimalSeparator(regional.formatLocaleTag),
                regional.currency.decimals,
            )
        return packages.write(
            SummaryPackageRequest(
                targetPath = target.path,
                files = ManagementSummaryRules.filesOf(chosen, offered),
                rows = CsvRowWriter(format, zone),
                naming = naming,
                range = range,
                pdfName = target.pdfName,
                pdfPath = target.pdfPath,
            ),
            progress,
        )
    }
}

/**
 * Dónde queda el paquete y con qué PDF.
 *
 * @property path el archivo del paquete, en la carpeta de lo que sale de la app.
 * @property pdfName el nombre del PDF dentro del paquete.
 * @property pdfPath el PDF ya escrito, en una carpeta temporal.
 */
data class SummaryTarget(val path: String, val pdfName: String, val pdfPath: String)

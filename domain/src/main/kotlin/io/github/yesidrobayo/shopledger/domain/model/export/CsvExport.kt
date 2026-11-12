package io.github.yesidrobayo.shopledger.domain.model.export

import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime

/**
 * Cómo terminó `Exportar a CSV`. Ninguno cambia los datos: exportar solo lee la base.
 */
sealed interface CsvExport {
    /**
     * El archivo quedó escrito y releído con el mismo tamaño y la misma suma.
     *
     * @property files los archivos CSV que lleva.
     * @property rows las filas de datos, sin contar los encabezados.
     */
    data class Saved(val files: Int, val rows: Long) : CsvExport

    /** No hubo espacio en el lugar elegido. El archivo a medias se borró. */
    data object NoSpace : CsvExport

    /**
     * Otra falla: la base no abrió, la región del negocio no se pudo leer o el lugar elegido dejó de
     * responder. El archivo elegido se borró si alcanzó a escribirse.
     */
    data object Failed : CsvExport
}

/**
 * Cuánto lleva la exportación: [done] de [total] archivos escritos.
 */
data class CsvExportProgress(val done: Int, val total: Int)

/**
 * El nombre que se sugiere al guardar la exportación: `<app_name>-export-AAAAMMDD-HHmm.zip`, con la
 * hora del negocio. La parte fija no se traduce, como el nombre de la app.
 */
object CsvExportFileName {
    private const val MIDDLE = "-export-"
    private const val SUFFIX = ".zip"
    private const val YEAR_DIGITS = 4

    /**
     * El nombre para una exportación de la app [appName] en [at]. Sin zona del negocio, que solo
     * falta antes de configurar la región, la hora va en UTC: el nombre solo orienta.
     */
    fun of(appName: String, at: Instant, zone: BusinessTimeZone?): String {
        val time = at.toLocalDateTime(zone?.timeZone ?: TimeZone.UTC)
        return buildString {
            append(appName)
            append(MIDDLE)
            append(time.year.toString().padStart(YEAR_DIGITS, '0'))
            append(time.month.number.toString().padStart(2, '0'))
            append(time.day.toString().padStart(2, '0'))
            append('-')
            append(time.hour.toString().padStart(2, '0'))
            append(time.minute.toString().padStart(2, '0'))
            append(SUFFIX)
        }
    }
}

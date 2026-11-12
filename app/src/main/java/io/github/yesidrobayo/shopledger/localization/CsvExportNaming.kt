package io.github.yesidrobayo.shopledger.localization

import android.content.res.Resources
import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExportTable
import io.github.yesidrobayo.shopledger.domain.port.CsvNaming

/**
 * Los nombres de la exportación CSV: cada archivo y cada columna con su clave estable en inglés, como
 * `sales.csv` y `sale_id`, y cada fila inicial que nadie renombró con su nombre en el idioma de la
 * interfaz, como la unidad o la caja.
 *
 * [resources] da los recursos en el idioma elegido para la app cada vez que se pide un nombre.
 */
class CsvExportNaming(private val resources: () -> Resources) : CsvNaming {
    override fun fileName(table: CsvExportTable): String = table.key + EXTENSION

    override fun header(column: String): String = column

    override fun seedName(table: SeedTable, systemKey: String): String =
        SeedText.displayName(resources(), table, systemKey, name = null)

    private companion object {
        const val EXTENSION = ".csv"
    }
}

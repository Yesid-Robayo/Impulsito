package io.github.yesidrobayo.shopledger.localization

import android.content.res.Resources
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.port.CsvNaming
import io.github.yesidrobayo.shopledger.domain.port.SummaryNaming

/**
 * Los nombres del paquete del resumen de gestión en el idioma de la interfaz: los mismos archivos
 * y encabezados que la exportación completa, más el del inventario al cierre, que solo lleva el
 * resumen. [resources] da los recursos en el idioma elegido para la app cada vez que se pide uno.
 */
class SummaryExportNaming(private val resources: () -> Resources) :
    SummaryNaming,
    CsvNaming by CsvExportNaming(resources) {
    override fun closingInventoryFileName(): String =
        resources().getString(R.string.csv_file_closing_inventory)
}

package io.github.yesidrobayo.shopledger.ui.experience

import androidx.annotation.StringRes
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.experience.BusinessTool
import io.github.yesidrobayo.shopledger.domain.model.experience.ToolGroup
import io.github.yesidrobayo.shopledger.navigation.AppNavKey

/**
 * Una herramienta que el centro `Negocio` ofrece: su nombre, la línea que dice para qué sirve y la
 * pantalla que abre.
 */
data class HubEntry(
    val tool: BusinessTool,
    @param:StringRes val name: Int,
    @param:StringRes val description: Int,
    val destination: AppNavKey,
)

/**
 * Las herramientas que el centro ofrece hoy, en el orden de [BusinessTool].
 *
 * **Cómo agrega su entrada una tarea posterior.** Cuando la pantalla de una herramienta existe, su
 * tarea agrega aquí una línea con su [BusinessTool], los textos `hub_tool_<clave>` y
 * `hub_tool_<clave>_desc` de la redacción en los tres idiomas, y su clave de navegación. Si deja un
 * dato vivo, como `Por cobrar`, lo agrega con su consulta en esa misma tarea. Saber si está en uso
 * ya lo resuelve la consulta de uso de módulos, que cubre todas las herramientas desde el
 * principio: no hay nada más que tocar.
 */
val HUB_ENTRIES: List<HubEntry> =
    listOf(
        HubEntry(
            BusinessTool.UNITS,
            R.string.hub_tool_units,
            R.string.hub_tool_units_desc,
            AppNavKey.Units,
        ),
    )

/** El nombre de cada grupo, de la redacción. */
@StringRes
fun groupTitle(group: ToolGroup): Int = when (group) {
    ToolGroup.MONEY -> R.string.hub_group_money
    ToolGroup.SALES_AND_DOCUMENTS -> R.string.hub_group_sales_documents
    ToolGroup.PRICES -> R.string.hub_group_prices
    ToolGroup.CATALOG_AND_INVENTORY -> R.string.hub_group_catalog_inventory
    ToolGroup.REPORTS -> R.string.hub_group_reports
}

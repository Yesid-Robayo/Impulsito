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
            BusinessTool.CUSTOMERS,
            R.string.hub_tool_customers,
            R.string.hub_tool_customers_desc,
            AppNavKey.Customers(),
        ),
        HubEntry(
            BusinessTool.LOYALTY,
            R.string.hub_tool_loyalty,
            R.string.hub_tool_loyalty_desc,
            AppNavKey.LoyaltyProgram,
        ),
        HubEntry(
            BusinessTool.MONEY_ACCOUNTS,
            R.string.hub_tool_money_accounts,
            R.string.hub_tool_money_accounts_desc,
            AppNavKey.MoneyAccounts(),
        ),
        HubEntry(
            BusinessTool.PAYABLES,
            R.string.hub_tool_payables,
            R.string.hub_tool_payables_desc,
            AppNavKey.Payables,
        ),
        HubEntry(
            BusinessTool.CASH_REGISTER,
            R.string.hub_tool_cash,
            R.string.hub_tool_cash_desc,
            AppNavKey.CashRegister,
        ),
        HubEntry(
            BusinessTool.HELD_SALES,
            R.string.hub_tool_drafts,
            R.string.hub_tool_drafts_desc,
            AppNavKey.HeldSales,
        ),
        HubEntry(
            BusinessTool.SHAREABLE_CATALOG,
            R.string.hub_tool_catalog,
            R.string.hub_tool_catalog_desc,
            AppNavKey.ShareableCatalog,
        ),
        HubEntry(
            BusinessTool.QUOTES,
            R.string.hub_tool_quotes,
            R.string.hub_tool_quotes_desc,
            AppNavKey.Quotes(),
        ),
        HubEntry(
            BusinessTool.PENDING_FISCAL,
            R.string.hub_tool_fiscal,
            R.string.hub_tool_fiscal_desc,
            AppNavKey.PendingFiscal(),
        ),
        HubEntry(
            BusinessTool.PENDING_FISCAL,
            R.string.hub_tool_fiscal,
            R.string.hub_tool_fiscal_desc,
            AppNavKey.PendingFiscal(),
        ),
        HubEntry(
            BusinessTool.ORDERS,
            R.string.hub_tool_orders,
            R.string.hub_tool_orders_desc,
            AppNavKey.Orders(),
        ),
        HubEntry(
            BusinessTool.LABELS,
            R.string.hub_tool_labels,
            R.string.hub_tool_labels_desc,
            AppNavKey.Labels(),
        ),
        HubEntry(
            BusinessTool.PRICE_LISTS,
            R.string.hub_tool_price_lists,
            R.string.hub_tool_price_lists_desc,
            AppNavKey.PriceLists,
        ),
        HubEntry(
            BusinessTool.REFERENCE_CURRENCY,
            R.string.hub_tool_reference_currency,
            R.string.hub_tool_reference_currency_desc,
            AppNavKey.ReferenceCurrency,
        ),
        HubEntry(
            BusinessTool.UNITS,
            R.string.hub_tool_units,
            R.string.hub_tool_units_desc,
            AppNavKey.Units,
        ),
        HubEntry(
            BusinessTool.COMBOS,
            R.string.hub_tool_combos,
            R.string.hub_tool_combos_desc,
            AppNavKey.Combos,
        ),
        HubEntry(
            BusinessTool.CUSTOM_FIELDS,
            R.string.hub_tool_fields,
            R.string.hub_tool_fields_desc,
            AppNavKey.CustomFields,
        ),
        HubEntry(
            BusinessTool.PRODUCTION,
            R.string.hub_tool_production,
            R.string.hub_tool_production_desc,
            AppNavKey.Production,
        ),
        HubEntry(
            BusinessTool.LOTS,
            R.string.hub_tool_lots,
            R.string.hub_tool_lots_desc,
            AppNavKey.Lots(),
        ),
        HubEntry(
            BusinessTool.LOCATIONS,
            R.string.hub_tool_locations,
            R.string.hub_tool_locations_desc,
            AppNavKey.Locations,
        ),
        HubEntry(
            BusinessTool.STOCK_COUNT,
            R.string.hub_tool_count,
            R.string.hub_tool_count_desc,
            AppNavKey.StockCount(),
        ),
        HubEntry(
            BusinessTool.REPLENISHMENT,
            R.string.hub_tool_reorder,
            R.string.hub_tool_reorder_desc,
            AppNavKey.Replenishment(),
        ),
        HubEntry(
            BusinessTool.SUPPLIER_RETURNS,
            R.string.hub_tool_supplier_returns,
            R.string.hub_tool_supplier_returns_desc,
            AppNavKey.SupplierReturn(),
        ),
        HubEntry(
            BusinessTool.IMPORT_DATA,
            R.string.hub_tool_import,
            R.string.hub_tool_import_desc,
            AppNavKey.ImportData,
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

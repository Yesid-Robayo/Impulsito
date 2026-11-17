package io.github.yesidrobayo.shopledger.ui.help

import androidx.annotation.RawRes
import androidx.annotation.StringRes
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.navigation.AppNavKey

/** Los grupos de la ayuda, en el orden en que se leen. */
enum class HelpGroup(@param:StringRes val title: Int) {
    START(R.string.help_group_start),
    SELL(R.string.help_group_sell),
    BUY_SPEND(R.string.help_group_buy_spend),
    INVENTORY(R.string.help_group_inventory),
    MONEY(R.string.help_group_money),
    NUMBERS(R.string.help_group_numbers),
    DOCUMENTS(R.string.help_group_documents),
    BACKUP_SECURITY(R.string.help_group_backup_security),
    SETTINGS(R.string.help_group_settings),
    GLOSSARY(R.string.help_group_glossary),
}

/**
 * Un artículo de la ayuda. El título es un recurso, así la lista se ve al instante, y el cuerpo
 * vive en `raw/`, `raw-es/` y `raw-pt/`: Android elige el del idioma de la interfaz y cae en
 * inglés si falta uno.
 *
 * @property key la clave estable, que es también el nombre del archivo. No se traduce ni cambia:
 *   la navegación la guarda para volver al artículo después de cerrar el proceso.
 */
enum class HelpArticleId(
    val key: String,
    val group: HelpGroup,
    @param:StringRes val title: Int,
    @param:RawRes val body: Int,
) {
    GETTING_STARTED(
        "help_getting_started",
        HelpGroup.START,
        R.string.help_article_getting_started_title,
        R.raw.help_getting_started,
    ),
    BUSINESS_HUB(
        "help_business_hub",
        HelpGroup.START,
        R.string.help_article_business_hub_title,
        R.raw.help_business_hub,
    ),
    FIRST_SALE(
        "help_first_sale",
        HelpGroup.SELL,
        R.string.help_article_first_sale_title,
        R.raw.help_first_sale,
    ),
    HELD_SALES(
        "help_held_sales",
        HelpGroup.SELL,
        R.string.help_article_held_sales_title,
        R.raw.help_held_sales,
    ),
    PAYMENTS_FEES(
        "help_payments_fees",
        HelpGroup.SELL,
        R.string.help_article_payments_fees_title,
        R.raw.help_payments_fees,
    ),
    RECORDS(
        "help_records",
        HelpGroup.SELL,
        R.string.help_article_records_title,
        R.raw.help_records,
    ),
    RETURNS_VOIDS(
        "help_returns_voids",
        HelpGroup.SELL,
        R.string.help_article_returns_voids_title,
        R.raw.help_returns_voids,
    ),
    QUOTES_ORDERS(
        "help_quotes_orders",
        HelpGroup.SELL,
        R.string.help_article_quotes_orders_title,
        R.raw.help_quotes_orders,
    ),
    PURCHASES_SUPPLIERS(
        "help_purchases_suppliers",
        HelpGroup.BUY_SPEND,
        R.string.help_article_purchases_suppliers_title,
        R.raw.help_purchases_suppliers,
    ),
    PAYABLES(
        "help_payables",
        HelpGroup.BUY_SPEND,
        R.string.help_article_payables_title,
        R.raw.help_payables,
    ),
    EXPENSES(
        "help_expenses",
        HelpGroup.BUY_SPEND,
        R.string.help_article_expenses_title,
        R.raw.help_expenses,
    ),
    RESTOCK(
        "help_restock",
        HelpGroup.BUY_SPEND,
        R.string.help_article_restock_title,
        R.raw.help_restock,
    ),
    PRODUCTS_COSTS(
        "help_products_costs",
        HelpGroup.INVENTORY,
        R.string.help_article_products_costs_title,
        R.raw.help_products_costs,
    ),
    RECIPES_PRODUCTION(
        "help_recipes_production",
        HelpGroup.INVENTORY,
        R.string.help_article_recipes_production_title,
        R.raw.help_recipes_production,
    ),
    VARIANTS_EXTRAS(
        "help_variants_extras",
        HelpGroup.INVENTORY,
        R.string.help_article_variants_extras_title,
        R.raw.help_variants_extras,
    ),
    CATEGORIES_FIELDS(
        "help_categories_fields",
        HelpGroup.INVENTORY,
        R.string.help_article_categories_fields_title,
        R.raw.help_categories_fields,
    ),
    PRICE_LISTS(
        "help_price_lists",
        HelpGroup.INVENTORY,
        R.string.help_article_price_lists_title,
        R.raw.help_price_lists,
    ),
    STOCK_COUNTS(
        "help_stock_counts",
        HelpGroup.INVENTORY,
        R.string.help_article_stock_counts_title,
        R.raw.help_stock_counts,
    ),
    LOCATIONS_TRANSFERS(
        "help_locations_transfers",
        HelpGroup.INVENTORY,
        R.string.help_article_locations_transfers_title,
        R.raw.help_locations_transfers,
    ),
    UNITS("help_units", HelpGroup.INVENTORY, R.string.help_article_units_title, R.raw.help_units),
    LOTS("help_lots", HelpGroup.INVENTORY, R.string.help_article_lots_title, R.raw.help_lots),
    SCANNER_LABELS(
        "help_scanner_labels",
        HelpGroup.INVENTORY,
        R.string.help_article_scanner_labels_title,
        R.raw.help_scanner_labels,
    ),
    PHOTOS_CATALOG(
        "help_photos_catalog",
        HelpGroup.INVENTORY,
        R.string.help_article_photos_catalog_title,
        R.raw.help_photos_catalog,
    ),
    CUSTOMERS_POINTS(
        "help_customers_points",
        HelpGroup.MONEY,
        R.string.help_article_customers_points_title,
        R.raw.help_customers_points,
    ),
    CREDIT("help_credit", HelpGroup.MONEY, R.string.help_article_credit_title, R.raw.help_credit),
    MONEY_ACCOUNTS(
        "help_money_accounts",
        HelpGroup.MONEY,
        R.string.help_article_money_accounts_title,
        R.raw.help_money_accounts,
    ),
    TAXES("help_taxes", HelpGroup.MONEY, R.string.help_article_taxes_title, R.raw.help_taxes),
    NUMBERS(
        "help_numbers",
        HelpGroup.NUMBERS,
        R.string.help_article_numbers_title,
        R.raw.help_numbers,
    ),
    MARGIN_PROFIT(
        "help_margin_profit",
        HelpGroup.NUMBERS,
        R.string.help_article_margin_profit_title,
        R.raw.help_margin_profit,
    ),
    REPLACEMENT_COST(
        "help_replacement_cost",
        HelpGroup.NUMBERS,
        R.string.help_article_replacement_cost_title,
        R.raw.help_replacement_cost,
    ),
    PRICE_INDEX(
        "help_price_index",
        HelpGroup.NUMBERS,
        R.string.help_article_price_index_title,
        R.raw.help_price_index,
    ),
    GOALS_LIMITS(
        "help_goals_limits",
        HelpGroup.NUMBERS,
        R.string.help_article_goals_limits_title,
        R.raw.help_goals_limits,
    ),
    RECEIPTS(
        "help_receipts",
        HelpGroup.DOCUMENTS,
        R.string.help_article_receipts_title,
        R.raw.help_receipts,
    ),
    IMPORT_EXPORT(
        "help_import_export",
        HelpGroup.DOCUMENTS,
        R.string.help_article_import_export_title,
        R.raw.help_import_export,
    ),
    NOT_ACCOUNTING(
        "help_not_accounting",
        HelpGroup.DOCUMENTS,
        R.string.help_article_not_accounting_title,
        R.raw.help_not_accounting,
    ),
    BACKUP_LIMITS(
        "help_backup_limits",
        HelpGroup.BACKUP_SECURITY,
        R.string.help_article_backup_limits_title,
        R.raw.help_backup_limits,
    ),
    RESTORE(
        "help_restore",
        HelpGroup.BACKUP_SECURITY,
        R.string.help_article_restore_title,
        R.raw.help_restore,
    ),
    RECOVERY_CODE(
        "help_recovery_code",
        HelpGroup.BACKUP_SECURITY,
        R.string.help_article_recovery_code_title,
        R.raw.help_recovery_code,
    ),
    DESTINATIONS(
        "help_destinations",
        HelpGroup.BACKUP_SECURITY,
        R.string.help_article_destinations_title,
        R.raw.help_destinations,
    ),
    CLOUD_CHECK(
        "help_cloud_check",
        HelpGroup.BACKUP_SECURITY,
        R.string.help_article_cloud_check_title,
        R.raw.help_cloud_check,
    ),
    OFFSITE(
        "help_offsite",
        HelpGroup.BACKUP_SECURITY,
        R.string.help_article_offsite_title,
        R.raw.help_offsite,
    ),
    LOCK_DISCREET(
        "help_lock_discreet",
        HelpGroup.BACKUP_SECURITY,
        R.string.help_article_lock_discreet_title,
        R.raw.help_lock_discreet,
    ),
    DATA_TRANSPARENCY(
        "help_data_transparency",
        HelpGroup.BACKUP_SECURITY,
        R.string.help_article_data_transparency_title,
        R.raw.help_data_transparency,
    ),
    AUDIT(
        "help_audit",
        HelpGroup.BACKUP_SECURITY,
        R.string.help_article_audit_title,
        R.raw.help_audit,
    ),
    DELETE_DATA(
        "help_delete_data",
        HelpGroup.BACKUP_SECURITY,
        R.string.help_article_delete_data_title,
        R.raw.help_delete_data,
    ),
    CLOUD_ACCOUNT(
        "help_cloud_account",
        HelpGroup.BACKUP_SECURITY,
        R.string.help_article_cloud_account_title,
        R.raw.help_cloud_account,
    ),
    REGION_LANGUAGE(
        "help_region_language",
        HelpGroup.SETTINGS,
        R.string.help_article_region_language_title,
        R.raw.help_region_language,
    ),
    BUSINESS_PREFERENCES(
        "help_business_preferences",
        HelpGroup.SETTINGS,
        R.string.help_article_business_preferences_title,
        R.raw.help_business_preferences,
    ),
    WIDGET_TILE(
        "help_widget_tile",
        HelpGroup.SETTINGS,
        R.string.help_article_widget_tile_title,
        R.raw.help_widget_tile,
    ),
    UPDATES(
        "help_updates",
        HelpGroup.SETTINGS,
        R.string.help_article_updates_title,
        R.raw.help_updates,
    ),
    GLOSSARY(
        "help_glossary",
        HelpGroup.GLOSSARY,
        R.string.help_article_glossary_title,
        R.raw.help_glossary,
    ),
    ;

    companion object {
        /** El artículo de [key], o `null` si ninguno se llama así. */
        fun ofKey(key: String?): HelpArticleId? = entries.firstOrNull { it.key == key }
    }
}

/**
 * La pantalla a la que lleva un artículo, como `Abrir Respaldo`. Solo pantallas de la app: ningún
 * artículo abre una dirección de internet.
 *
 * @property screen el nombre de la pantalla, que va en `Abrir {screen}`.
 */
data class HelpTarget(val key: AppNavKey, @param:StringRes val screen: Int)

/** Adónde lleva [this] al pie, o `null` si es solo de lectura. */
fun HelpArticleId.target(): HelpTarget? = when (this) {
    HelpArticleId.BUSINESS_HUB -> HelpTarget(AppNavKey.BusinessHub, R.string.hub_title)

    HelpArticleId.HELD_SALES -> HelpTarget(AppNavKey.HeldSales, R.string.draft_title)

    HelpArticleId.PAYMENTS_FEES ->
        HelpTarget(AppNavKey.PaymentMethods, R.string.payment_method_title)

    HelpArticleId.RECORDS -> HelpTarget(AppNavKey.SaleHistory, R.string.nav_records)

    HelpArticleId.RETURNS_VOIDS -> HelpTarget(AppNavKey.SaleHistory, R.string.nav_records)

    HelpArticleId.QUOTES_ORDERS -> HelpTarget(AppNavKey.Quotes(), R.string.quote_title)

    HelpArticleId.PURCHASES_SUPPLIERS ->
        HelpTarget(AppNavKey.PurchaseHistory(), R.string.records_tab_purchases)

    HelpArticleId.PAYABLES -> HelpTarget(AppNavKey.Payables, R.string.payable_title)

    HelpArticleId.EXPENSES -> HelpTarget(AppNavKey.Expenses(), R.string.records_tab_expenses)

    HelpArticleId.RESTOCK -> HelpTarget(AppNavKey.Replenishment(), R.string.reorder_title)

    HelpArticleId.PRODUCTS_COSTS -> HelpTarget(AppNavKey.ProductList, R.string.nav_products)

    HelpArticleId.RECIPES_PRODUCTION -> HelpTarget(AppNavKey.ProductList, R.string.nav_products)

    HelpArticleId.CATEGORIES_FIELDS -> HelpTarget(AppNavKey.Categories, R.string.categories_title)

    HelpArticleId.PRICE_LISTS -> HelpTarget(AppNavKey.PriceLists, R.string.price_list_title)

    HelpArticleId.STOCK_COUNTS -> HelpTarget(AppNavKey.StockCount(), R.string.count_title)

    HelpArticleId.LOCATIONS_TRANSFERS -> HelpTarget(AppNavKey.Locations, R.string.location_title)

    HelpArticleId.UNITS -> HelpTarget(AppNavKey.Units, R.string.unit_title)

    HelpArticleId.LOTS -> HelpTarget(AppNavKey.Lots(), R.string.lot_title)

    HelpArticleId.SCANNER_LABELS -> HelpTarget(AppNavKey.Labels(), R.string.label_title)

    HelpArticleId.PHOTOS_CATALOG -> HelpTarget(AppNavKey.ShareableCatalog, R.string.catalog_title)

    HelpArticleId.CUSTOMERS_POINTS -> HelpTarget(AppNavKey.Customers(), R.string.customer_title)

    HelpArticleId.CREDIT -> HelpTarget(AppNavKey.Customers(), R.string.customer_title)

    HelpArticleId.MONEY_ACCOUNTS -> HelpTarget(AppNavKey.MoneyAccounts(), R.string.money_title)

    HelpArticleId.TAXES -> HelpTarget(AppNavKey.TaxSettings, R.string.tax_title)

    HelpArticleId.NUMBERS -> HelpTarget(AppNavKey.Statistics, R.string.nav_numbers)

    HelpArticleId.PRICE_INDEX -> HelpTarget(AppNavKey.PriceIndex, R.string.index_title)

    HelpArticleId.GOALS_LIMITS -> HelpTarget(AppNavKey.GoalsAndLimits, R.string.goal_screen_title)

    HelpArticleId.RECEIPTS -> HelpTarget(AppNavKey.ReceiptTemplate, R.string.receipt_template_title)

    HelpArticleId.IMPORT_EXPORT -> HelpTarget(AppNavKey.ImportData, R.string.import_title)

    HelpArticleId.BACKUP_LIMITS -> HelpTarget(AppNavKey.Backup, R.string.backup_title)

    HelpArticleId.RESTORE -> HelpTarget(AppNavKey.Backup, R.string.backup_title)

    HelpArticleId.RECOVERY_CODE -> HelpTarget(AppNavKey.BackupEncryption, R.string.encryption_title)

    HelpArticleId.DESTINATIONS ->
        HelpTarget(AppNavKey.BackupDestinationChoice(), R.string.destination_title)

    HelpArticleId.CLOUD_CHECK -> HelpTarget(AppNavKey.Backup, R.string.backup_title)

    HelpArticleId.OFFSITE -> HelpTarget(AppNavKey.LocalCopies, R.string.offsite_title)

    HelpArticleId.LOCK_DISCREET -> HelpTarget(AppNavKey.Settings, R.string.settings_title)

    HelpArticleId.DATA_TRANSPARENCY ->
        HelpTarget(AppNavKey.DataTransparency, R.string.transparency_title)

    HelpArticleId.AUDIT -> HelpTarget(AppNavKey.AuditLog, R.string.audit_title)

    HelpArticleId.DELETE_DATA -> HelpTarget(AppNavKey.StorageAndDeletion(), R.string.storage_title)

    HelpArticleId.CLOUD_ACCOUNT ->
        HelpTarget(AppNavKey.CloudAccountSecurity, R.string.account_title)

    HelpArticleId.REGION_LANGUAGE -> HelpTarget(AppNavKey.RegionalSettings(), R.string.region_title)

    HelpArticleId.BUSINESS_PREFERENCES -> HelpTarget(AppNavKey.Settings, R.string.settings_title)

    // Sin una sola pantalla que los resuma, o que pide un producto: se leen y nada más.

    HelpArticleId.GETTING_STARTED,
    HelpArticleId.FIRST_SALE,
    HelpArticleId.VARIANTS_EXTRAS,
    HelpArticleId.MARGIN_PROFIT,
    HelpArticleId.REPLACEMENT_COST,
    HelpArticleId.NOT_ACCOUNTING,
    HelpArticleId.WIDGET_TILE,
    HelpArticleId.UPDATES,
    HelpArticleId.GLOSSARY,
    -> null
}

/** Los artículos de `Relacionados`, al pie de [this]: lo que suele leerse después. */
fun HelpArticleId.related(): List<HelpArticleId> = when (this) {
    HelpArticleId.GETTING_STARTED ->
        listOf(HelpArticleId.FIRST_SALE, HelpArticleId.BUSINESS_HUB, HelpArticleId.DESTINATIONS)

    HelpArticleId.BUSINESS_HUB ->
        listOf(
            HelpArticleId.GETTING_STARTED,
            HelpArticleId.REGION_LANGUAGE,
            HelpArticleId.MONEY_ACCOUNTS,
        )

    HelpArticleId.FIRST_SALE ->
        listOf(HelpArticleId.PAYMENTS_FEES, HelpArticleId.HELD_SALES, HelpArticleId.RECEIPTS)

    HelpArticleId.HELD_SALES ->
        listOf(
            HelpArticleId.FIRST_SALE,
            HelpArticleId.PAYMENTS_FEES,
            HelpArticleId.CUSTOMERS_POINTS,
        )

    HelpArticleId.PAYMENTS_FEES -> listOf(HelpArticleId.MONEY_ACCOUNTS, HelpArticleId.FIRST_SALE)

    HelpArticleId.RECORDS ->
        listOf(
            HelpArticleId.RETURNS_VOIDS,
            HelpArticleId.PURCHASES_SUPPLIERS,
            HelpArticleId.EXPENSES,
        )

    HelpArticleId.RETURNS_VOIDS ->
        listOf(HelpArticleId.FIRST_SALE, HelpArticleId.CREDIT, HelpArticleId.STOCK_COUNTS)

    HelpArticleId.QUOTES_ORDERS ->
        listOf(HelpArticleId.NOT_ACCOUNTING, HelpArticleId.RECEIPTS, HelpArticleId.CREDIT)

    HelpArticleId.PURCHASES_SUPPLIERS ->
        listOf(HelpArticleId.REPLACEMENT_COST, HelpArticleId.PAYABLES, HelpArticleId.RESTOCK)

    HelpArticleId.PAYABLES ->
        listOf(
            HelpArticleId.PURCHASES_SUPPLIERS,
            HelpArticleId.MONEY_ACCOUNTS,
            HelpArticleId.CREDIT,
        )

    HelpArticleId.EXPENSES ->
        listOf(
            HelpArticleId.MARGIN_PROFIT,
            HelpArticleId.MONEY_ACCOUNTS,
            HelpArticleId.PURCHASES_SUPPLIERS,
        )

    HelpArticleId.RESTOCK ->
        listOf(
            HelpArticleId.STOCK_COUNTS,
            HelpArticleId.RECIPES_PRODUCTION,
            HelpArticleId.PURCHASES_SUPPLIERS,
        )

    HelpArticleId.PRODUCTS_COSTS ->
        listOf(HelpArticleId.MARGIN_PROFIT, HelpArticleId.RECIPES_PRODUCTION, HelpArticleId.UNITS)

    HelpArticleId.RECIPES_PRODUCTION ->
        listOf(HelpArticleId.PRODUCTS_COSTS, HelpArticleId.RESTOCK, HelpArticleId.STOCK_COUNTS)

    HelpArticleId.VARIANTS_EXTRAS ->
        listOf(
            HelpArticleId.PRODUCTS_COSTS,
            HelpArticleId.FIRST_SALE,
            HelpArticleId.CATEGORIES_FIELDS,
        )

    HelpArticleId.CATEGORIES_FIELDS ->
        listOf(HelpArticleId.UNITS, HelpArticleId.PRODUCTS_COSTS, HelpArticleId.LOCATIONS_TRANSFERS)

    HelpArticleId.PRICE_LISTS ->
        listOf(
            HelpArticleId.PRODUCTS_COSTS,
            HelpArticleId.MARGIN_PROFIT,
            HelpArticleId.CUSTOMERS_POINTS,
        )

    HelpArticleId.STOCK_COUNTS ->
        listOf(HelpArticleId.UNITS, HelpArticleId.LOTS, HelpArticleId.LOCATIONS_TRANSFERS)

    HelpArticleId.LOCATIONS_TRANSFERS ->
        listOf(HelpArticleId.STOCK_COUNTS, HelpArticleId.RESTOCK, HelpArticleId.LOTS)

    HelpArticleId.UNITS -> listOf(HelpArticleId.STOCK_COUNTS, HelpArticleId.PRODUCTS_COSTS)

    HelpArticleId.LOTS -> listOf(HelpArticleId.STOCK_COUNTS, HelpArticleId.UNITS)

    HelpArticleId.SCANNER_LABELS ->
        listOf(HelpArticleId.FIRST_SALE, HelpArticleId.STOCK_COUNTS, HelpArticleId.RECEIPTS)

    HelpArticleId.PHOTOS_CATALOG ->
        listOf(HelpArticleId.RECEIPTS, HelpArticleId.OFFSITE, HelpArticleId.BACKUP_LIMITS)

    HelpArticleId.CUSTOMERS_POINTS ->
        listOf(HelpArticleId.CREDIT, HelpArticleId.HELD_SALES, HelpArticleId.RETURNS_VOIDS)

    HelpArticleId.CREDIT ->
        listOf(HelpArticleId.CUSTOMERS_POINTS, HelpArticleId.MONEY_ACCOUNTS, HelpArticleId.RECEIPTS)

    HelpArticleId.MONEY_ACCOUNTS -> listOf(HelpArticleId.PAYMENTS_FEES, HelpArticleId.CREDIT)

    HelpArticleId.TAXES -> listOf(HelpArticleId.NOT_ACCOUNTING, HelpArticleId.RECEIPTS)

    HelpArticleId.NUMBERS ->
        listOf(HelpArticleId.MARGIN_PROFIT, HelpArticleId.PRICE_INDEX, HelpArticleId.NOT_ACCOUNTING)

    HelpArticleId.MARGIN_PROFIT ->
        listOf(HelpArticleId.REPLACEMENT_COST, HelpArticleId.NOT_ACCOUNTING, HelpArticleId.GLOSSARY)

    HelpArticleId.REPLACEMENT_COST ->
        listOf(HelpArticleId.MARGIN_PROFIT, HelpArticleId.PRODUCTS_COSTS)

    HelpArticleId.PRICE_INDEX -> listOf(HelpArticleId.NUMBERS, HelpArticleId.IMPORT_EXPORT)

    HelpArticleId.GOALS_LIMITS -> listOf(HelpArticleId.NUMBERS, HelpArticleId.MARGIN_PROFIT)

    HelpArticleId.RECEIPTS -> listOf(HelpArticleId.NOT_ACCOUNTING, HelpArticleId.FIRST_SALE)

    HelpArticleId.IMPORT_EXPORT -> listOf(HelpArticleId.OFFSITE, HelpArticleId.PRODUCTS_COSTS)

    HelpArticleId.NOT_ACCOUNTING -> listOf(HelpArticleId.TAXES, HelpArticleId.RECEIPTS)

    HelpArticleId.BACKUP_LIMITS ->
        listOf(HelpArticleId.DESTINATIONS, HelpArticleId.CLOUD_CHECK, HelpArticleId.RESTORE)

    HelpArticleId.RESTORE ->
        listOf(HelpArticleId.RECOVERY_CODE, HelpArticleId.OFFSITE, HelpArticleId.BACKUP_LIMITS)

    HelpArticleId.RECOVERY_CODE -> listOf(HelpArticleId.RESTORE, HelpArticleId.BACKUP_LIMITS)

    HelpArticleId.DESTINATIONS ->
        listOf(HelpArticleId.BACKUP_LIMITS, HelpArticleId.CLOUD_CHECK, HelpArticleId.CLOUD_ACCOUNT)

    HelpArticleId.CLOUD_CHECK -> listOf(HelpArticleId.DESTINATIONS, HelpArticleId.BACKUP_LIMITS)

    HelpArticleId.OFFSITE -> listOf(HelpArticleId.RESTORE, HelpArticleId.BACKUP_LIMITS)

    HelpArticleId.LOCK_DISCREET -> listOf(HelpArticleId.RECOVERY_CODE, HelpArticleId.WIDGET_TILE)

    HelpArticleId.DATA_TRANSPARENCY ->
        listOf(HelpArticleId.CLOUD_ACCOUNT, HelpArticleId.DESTINATIONS, HelpArticleId.DELETE_DATA)

    HelpArticleId.AUDIT ->
        listOf(HelpArticleId.DELETE_DATA, HelpArticleId.RESTORE, HelpArticleId.LOCK_DISCREET)

    HelpArticleId.DELETE_DATA -> listOf(HelpArticleId.IMPORT_EXPORT, HelpArticleId.OFFSITE)

    HelpArticleId.CLOUD_ACCOUNT -> listOf(HelpArticleId.DESTINATIONS, HelpArticleId.CLOUD_CHECK)

    HelpArticleId.REGION_LANGUAGE ->
        listOf(
            HelpArticleId.WIDGET_TILE,
            HelpArticleId.IMPORT_EXPORT,
            HelpArticleId.GETTING_STARTED,
        )

    HelpArticleId.BUSINESS_PREFERENCES ->
        listOf(
            HelpArticleId.MARGIN_PROFIT,
            HelpArticleId.REPLACEMENT_COST,
            HelpArticleId.PAYMENTS_FEES,
        )

    HelpArticleId.WIDGET_TILE ->
        listOf(HelpArticleId.LOCK_DISCREET, HelpArticleId.FIRST_SALE, HelpArticleId.REGION_LANGUAGE)

    HelpArticleId.UPDATES -> listOf(HelpArticleId.RESTORE, HelpArticleId.BACKUP_LIMITS)

    HelpArticleId.GLOSSARY -> listOf(HelpArticleId.MARGIN_PROFIT, HelpArticleId.NOT_ACCOUNTING)
}

/**
 * La ayuda de una pantalla, la que abre su `HelpLink`. Con un solo artículo, llega directo a él;
 * con más, abre la lista con `Para esta pantalla` arriba. Varias pantallas comparten tema, como
 * inventario, ajuste y conteo.
 *
 * @property key la clave estable que guarda la navegación.
 */
enum class HelpTopic(val key: String, val articles: List<HelpArticleId>) {
    ONBOARDING("onboarding", listOf(HelpArticleId.GETTING_STARTED, HelpArticleId.RESTORE)),
    HOME("home", listOf(HelpArticleId.GETTING_STARTED, HelpArticleId.BUSINESS_HUB)),
    BUSINESS_HUB("business_hub", listOf(HelpArticleId.BUSINESS_HUB)),
    NEW_SALE("new_sale", listOf(HelpArticleId.FIRST_SALE, HelpArticleId.HELD_SALES)),
    HELD_SALES("held_sales", listOf(HelpArticleId.HELD_SALES)),
    RECORDS("records", listOf(HelpArticleId.RECORDS, HelpArticleId.RETURNS_VOIDS)),
    RETURNS("returns", listOf(HelpArticleId.RETURNS_VOIDS)),
    QUOTES_ORDERS("quotes_orders", listOf(HelpArticleId.QUOTES_ORDERS)),
    PURCHASES("purchases", listOf(HelpArticleId.PURCHASES_SUPPLIERS)),
    PAYABLES("payables", listOf(HelpArticleId.PAYABLES)),
    EXPENSES("expenses", listOf(HelpArticleId.EXPENSES)),
    RESTOCK("restock", listOf(HelpArticleId.RESTOCK)),
    PRODUCT_EDIT(
        "product_edit",
        listOf(
            HelpArticleId.PRODUCTS_COSTS,
            HelpArticleId.VARIANTS_EXTRAS,
            HelpArticleId.CATEGORIES_FIELDS,
            HelpArticleId.PHOTOS_CATALOG,
        ),
    ),
    PRODUCT_DETAIL(
        "product_detail",
        listOf(
            HelpArticleId.MARGIN_PROFIT,
            HelpArticleId.REPLACEMENT_COST,
            HelpArticleId.RECIPES_PRODUCTION,
        ),
    ),
    RECIPES("recipes", listOf(HelpArticleId.RECIPES_PRODUCTION)),
    VARIANTS_EXTRAS("variants_extras", listOf(HelpArticleId.VARIANTS_EXTRAS)),
    CATALOG_SETUP("catalog_setup", listOf(HelpArticleId.CATEGORIES_FIELDS)),
    PRICE_LISTS("price_lists", listOf(HelpArticleId.PRICE_LISTS)),
    LOCATIONS("locations", listOf(HelpArticleId.LOCATIONS_TRANSFERS)),
    LABELS("labels", listOf(HelpArticleId.SCANNER_LABELS)),
    CATALOG("catalog", listOf(HelpArticleId.PHOTOS_CATALOG)),
    STATISTICS("statistics", listOf(HelpArticleId.NUMBERS, HelpArticleId.MARGIN_PROFIT)),
    MANAGEMENT_SUMMARY(
        "management_summary",
        listOf(HelpArticleId.NUMBERS, HelpArticleId.NOT_ACCOUNTING),
    ),
    PRICE_INDEX("price_index", listOf(HelpArticleId.PRICE_INDEX)),
    GOALS("goals", listOf(HelpArticleId.GOALS_LIMITS)),
    PAYMENTS("payments", listOf(HelpArticleId.PAYMENTS_FEES)),
    CUSTOMERS("customers", listOf(HelpArticleId.CUSTOMERS_POINTS, HelpArticleId.CREDIT)),
    MONEY_ACCOUNTS("money_accounts", listOf(HelpArticleId.MONEY_ACCOUNTS)),
    TAXES("taxes", listOf(HelpArticleId.TAXES)),
    INVENTORY("inventory", listOf(HelpArticleId.STOCK_COUNTS, HelpArticleId.RESTOCK)),
    UNITS("units", listOf(HelpArticleId.UNITS)),
    LOTS("lots", listOf(HelpArticleId.LOTS)),
    RECEIPTS("receipts", listOf(HelpArticleId.RECEIPTS)),
    IMPORT("import", listOf(HelpArticleId.IMPORT_EXPORT)),
    NOT_ACCOUNTING("not_accounting", listOf(HelpArticleId.NOT_ACCOUNTING)),
    BACKUP(
        "backup",
        listOf(HelpArticleId.BACKUP_LIMITS, HelpArticleId.RESTORE, HelpArticleId.CLOUD_CHECK),
    ),
    ENCRYPTION("encryption", listOf(HelpArticleId.RECOVERY_CODE)),
    DESTINATIONS("destinations", listOf(HelpArticleId.DESTINATIONS)),
    LOCAL_COPIES("local_copies", listOf(HelpArticleId.OFFSITE)),
    LOCK_DISCREET(
        "lock_discreet",
        listOf(
            HelpArticleId.LOCK_DISCREET,
            HelpArticleId.BUSINESS_PREFERENCES,
            HelpArticleId.REGION_LANGUAGE,
            HelpArticleId.WIDGET_TILE,
        ),
    ),
    TRANSPARENCY("transparency", listOf(HelpArticleId.DATA_TRANSPARENCY)),
    AUDIT("audit", listOf(HelpArticleId.AUDIT)),
    STORAGE("storage", listOf(HelpArticleId.DELETE_DATA)),
    CLOUD_ACCOUNT("cloud_account", listOf(HelpArticleId.CLOUD_ACCOUNT)),
    REGION("region", listOf(HelpArticleId.REGION_LANGUAGE)),
    UPDATES("updates", listOf(HelpArticleId.UPDATES)),
    ;

    /** El artículo que nombra el `HelpLink`: el primero, que es el de la tarea de la pantalla. */
    val primary: HelpArticleId get() = articles.first()

    /**
     * La entrada de la ayuda: directo al artículo si hay uno solo, o la lista. La clave del tema
     * va siempre, para saber que se llegó desde la pantalla.
     */
    fun navKey(): AppNavKey.Help = AppNavKey.Help(
        articleKey = if (articles.size == 1) primary.key else null,
        topicKey = key,
    )

    companion object {
        /** El tema de [key], o `null` si ninguno se llama así. */
        fun ofKey(key: String?): HelpTopic? = entries.firstOrNull { it.key == key }
    }
}

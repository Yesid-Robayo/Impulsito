package io.github.yesidrobayo.shopledger.ui.help

import androidx.annotation.RawRes
import androidx.annotation.StringRes
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.navigation.AppNavKey

/** Los grupos de la ayuda, en el orden en que se leen. */
enum class HelpGroup(@param:StringRes val title: Int) {
    START(R.string.help_group_start),
    MONEY(R.string.help_group_money),
    INVENTORY(R.string.help_group_inventory),
    DOCUMENTS(R.string.help_group_documents),
    BACKUP_SECURITY(R.string.help_group_backup_security),
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
    FIRST_SALE(
        "help_first_sale",
        HelpGroup.START,
        R.string.help_article_first_sale_title,
        R.raw.help_first_sale,
    ),
    PRODUCTS_COSTS(
        "help_products_costs",
        HelpGroup.START,
        R.string.help_article_products_costs_title,
        R.raw.help_products_costs,
    ),
    MARGIN_PROFIT(
        "help_margin_profit",
        HelpGroup.MONEY,
        R.string.help_article_margin_profit_title,
        R.raw.help_margin_profit,
    ),
    REPLACEMENT_COST(
        "help_replacement_cost",
        HelpGroup.MONEY,
        R.string.help_article_replacement_cost_title,
        R.raw.help_replacement_cost,
    ),
    PAYMENTS_FEES(
        "help_payments_fees",
        HelpGroup.MONEY,
        R.string.help_article_payments_fees_title,
        R.raw.help_payments_fees,
    ),
    CREDIT("help_credit", HelpGroup.MONEY, R.string.help_article_credit_title, R.raw.help_credit),
    MONEY_ACCOUNTS(
        "help_money_accounts",
        HelpGroup.MONEY,
        R.string.help_article_money_accounts_title,
        R.raw.help_money_accounts,
    ),
    TAXES("help_taxes", HelpGroup.MONEY, R.string.help_article_taxes_title, R.raw.help_taxes),
    STOCK_COUNTS(
        "help_stock_counts",
        HelpGroup.INVENTORY,
        R.string.help_article_stock_counts_title,
        R.raw.help_stock_counts,
    ),
    UNITS("help_units", HelpGroup.INVENTORY, R.string.help_article_units_title, R.raw.help_units),
    LOTS("help_lots", HelpGroup.INVENTORY, R.string.help_article_lots_title, R.raw.help_lots),
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
    UPDATES(
        "help_updates",
        HelpGroup.BACKUP_SECURITY,
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
    HelpArticleId.PRODUCTS_COSTS -> HelpTarget(AppNavKey.ProductList, R.string.nav_products)

    HelpArticleId.PAYMENTS_FEES ->
        HelpTarget(AppNavKey.PaymentMethods, R.string.payment_method_title)

    HelpArticleId.CREDIT -> HelpTarget(AppNavKey.Customers(), R.string.customer_title)

    HelpArticleId.MONEY_ACCOUNTS -> HelpTarget(AppNavKey.MoneyAccounts(), R.string.money_title)

    HelpArticleId.TAXES -> HelpTarget(AppNavKey.TaxSettings, R.string.tax_title)

    HelpArticleId.STOCK_COUNTS -> HelpTarget(AppNavKey.StockCount(), R.string.count_title)

    HelpArticleId.UNITS -> HelpTarget(AppNavKey.Units, R.string.unit_title)

    HelpArticleId.LOTS -> HelpTarget(AppNavKey.Lots(), R.string.lot_title)

    HelpArticleId.RECEIPTS ->
        HelpTarget(AppNavKey.ReceiptTemplate, R.string.receipt_template_title)

    HelpArticleId.IMPORT_EXPORT -> HelpTarget(AppNavKey.ImportData, R.string.import_title)

    HelpArticleId.BACKUP_LIMITS,
    HelpArticleId.RESTORE,
    HelpArticleId.CLOUD_CHECK,
    -> HelpTarget(AppNavKey.Backup, R.string.backup_title)

    HelpArticleId.RECOVERY_CODE ->
        HelpTarget(AppNavKey.BackupEncryption, R.string.encryption_title)

    HelpArticleId.DESTINATIONS ->
        HelpTarget(AppNavKey.BackupDestinationChoice(), R.string.destination_title)

    HelpArticleId.OFFSITE -> HelpTarget(AppNavKey.LocalCopies, R.string.offsite_title)

    HelpArticleId.LOCK_DISCREET -> HelpTarget(AppNavKey.Settings, R.string.settings_title)

    HelpArticleId.DELETE_DATA -> HelpTarget(AppNavKey.StorageAndDeletion, R.string.storage_title)

    HelpArticleId.CLOUD_ACCOUNT ->
        HelpTarget(AppNavKey.CloudAccountSecurity, R.string.account_title)

    // Sin pantalla propia todavía, o sin una sola pantalla que la resuma: se leen y nada más.
    HelpArticleId.GETTING_STARTED,
    HelpArticleId.FIRST_SALE,
    HelpArticleId.MARGIN_PROFIT,
    HelpArticleId.REPLACEMENT_COST,
    HelpArticleId.NOT_ACCOUNTING,
    HelpArticleId.UPDATES,
    HelpArticleId.GLOSSARY,
    -> null
}

/** Los artículos de `Relacionados`, al pie de [this]: lo que suele leerse después. */
fun HelpArticleId.related(): List<HelpArticleId> = when (this) {
    HelpArticleId.GETTING_STARTED ->
        listOf(HelpArticleId.FIRST_SALE, HelpArticleId.PRODUCTS_COSTS, HelpArticleId.DESTINATIONS)

    HelpArticleId.FIRST_SALE ->
        listOf(HelpArticleId.PAYMENTS_FEES, HelpArticleId.CREDIT, HelpArticleId.RECEIPTS)

    HelpArticleId.PRODUCTS_COSTS ->
        listOf(HelpArticleId.MARGIN_PROFIT, HelpArticleId.REPLACEMENT_COST, HelpArticleId.UNITS)

    HelpArticleId.MARGIN_PROFIT ->
        listOf(
            HelpArticleId.REPLACEMENT_COST,
            HelpArticleId.NOT_ACCOUNTING,
            HelpArticleId.GLOSSARY,
        )

    HelpArticleId.REPLACEMENT_COST ->
        listOf(HelpArticleId.MARGIN_PROFIT, HelpArticleId.PRODUCTS_COSTS)

    HelpArticleId.PAYMENTS_FEES -> listOf(HelpArticleId.MONEY_ACCOUNTS, HelpArticleId.FIRST_SALE)

    HelpArticleId.CREDIT -> listOf(HelpArticleId.MONEY_ACCOUNTS, HelpArticleId.RECEIPTS)

    HelpArticleId.MONEY_ACCOUNTS -> listOf(HelpArticleId.PAYMENTS_FEES, HelpArticleId.CREDIT)

    HelpArticleId.TAXES -> listOf(HelpArticleId.NOT_ACCOUNTING, HelpArticleId.RECEIPTS)

    HelpArticleId.STOCK_COUNTS -> listOf(HelpArticleId.UNITS, HelpArticleId.LOTS)

    HelpArticleId.UNITS -> listOf(HelpArticleId.STOCK_COUNTS, HelpArticleId.PRODUCTS_COSTS)

    HelpArticleId.LOTS -> listOf(HelpArticleId.STOCK_COUNTS, HelpArticleId.UNITS)

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

    HelpArticleId.LOCK_DISCREET -> listOf(HelpArticleId.RECOVERY_CODE)

    HelpArticleId.DELETE_DATA -> listOf(HelpArticleId.IMPORT_EXPORT, HelpArticleId.OFFSITE)

    HelpArticleId.CLOUD_ACCOUNT -> listOf(HelpArticleId.DESTINATIONS, HelpArticleId.CLOUD_CHECK)

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
    HOME("home", listOf(HelpArticleId.GETTING_STARTED)),
    NEW_SALE("new_sale", listOf(HelpArticleId.FIRST_SALE)),
    PRODUCT_EDIT("product_edit", listOf(HelpArticleId.PRODUCTS_COSTS)),
    PRODUCT_DETAIL(
        "product_detail",
        listOf(HelpArticleId.MARGIN_PROFIT, HelpArticleId.REPLACEMENT_COST),
    ),
    STATISTICS("statistics", listOf(HelpArticleId.MARGIN_PROFIT)),
    PAYMENTS("payments", listOf(HelpArticleId.PAYMENTS_FEES)),
    CUSTOMERS("customers", listOf(HelpArticleId.CREDIT)),
    MONEY_ACCOUNTS("money_accounts", listOf(HelpArticleId.MONEY_ACCOUNTS)),
    TAXES("taxes", listOf(HelpArticleId.TAXES)),
    INVENTORY("inventory", listOf(HelpArticleId.STOCK_COUNTS)),
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
    LOCK_DISCREET("lock_discreet", listOf(HelpArticleId.LOCK_DISCREET)),
    STORAGE("storage", listOf(HelpArticleId.DELETE_DATA)),
    CLOUD_ACCOUNT("cloud_account", listOf(HelpArticleId.CLOUD_ACCOUNT)),
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

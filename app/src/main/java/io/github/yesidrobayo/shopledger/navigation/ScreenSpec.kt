package io.github.yesidrobayo.shopledger.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.ui.components.TopBarNavigation

/**
 * Lo que la navegación sabe de cada pantalla antes de que tenga contenido: su título, cómo se sale
 * de ella y el icono de su sección.
 *
 * Las pantallas con un número de documento en el título, como una venta, usan aquí el nombre de su
 * lista: el número llega con su pantalla, que lee la base.
 */
data class ScreenSpec(
    @param:StringRes val title: Int,
    val navigation: TopBarNavigation,
    @param:DrawableRes val icon: Int,
)

private val home = R.drawable.ic_home
private val records = R.drawable.ic_receipt_long
private val products = R.drawable.ic_inventory_2
private val numbers = R.drawable.ic_bar_chart
private val sell = R.drawable.ic_sell
private val business = R.drawable.ic_business_center
private val settings = R.drawable.ic_settings

private fun root(@StringRes title: Int, @DrawableRes icon: Int) =
    ScreenSpec(title, TopBarNavigation.NONE, icon)

private fun detail(@StringRes title: Int, @DrawableRes icon: Int) =
    ScreenSpec(title, TopBarNavigation.BACK, icon)

private fun overlay(@StringRes title: Int, @DrawableRes icon: Int) =
    ScreenSpec(title, TopBarNavigation.CLOSE, icon)

/** La ficha de [key]. El `when` no tiene rama por defecto: una clave nueva no compila sin la suya. */
fun screenSpecOf(key: AppNavKey): ScreenSpec = when (key) {
    AppNavKey.Onboarding -> ScreenSpec(R.string.app_name, TopBarNavigation.NONE, home)

    AppNavKey.Home -> root(R.string.nav_home, home)

    AppNavKey.SaleHistory -> root(R.string.nav_records, records)

    AppNavKey.ProductList -> root(R.string.nav_products, products)

    AppNavKey.Statistics -> root(R.string.nav_numbers, numbers)

    is AppNavKey.NewSale -> overlay(R.string.sale_new_title, sell)

    AppNavKey.Checkout -> detail(R.string.checkout_title, sell)

    AppNavKey.HeldSales -> detail(R.string.draft_title, sell)

    is AppNavKey.Scanner -> overlay(R.string.scanner_title, sell)

    is AppNavKey.SaleDetail -> detail(R.string.records_tab_sales, records)

    is AppNavKey.SaleReturn -> detail(R.string.sale_return_title, records)

    is AppNavKey.Expenses -> detail(R.string.records_tab_expenses, records)

    is AppNavKey.PurchaseHistory -> detail(R.string.records_tab_purchases, records)

    is AppNavKey.PurchaseDetail -> detail(R.string.records_tab_purchases, records)

    is AppNavKey.Purchase -> detail(R.string.purchase_new_title, records)

    is AppNavKey.ProductEdit ->
        detail(
            if (key.productId ==
                null
            ) {
                R.string.product_new_title
            } else {
                R.string.action_edit
            },
            products,
        )

    is AppNavKey.ProductDetail -> detail(R.string.nav_products, products)

    is AppNavKey.BulkPriceUpdate -> detail(R.string.bulk_title, products)

    is AppNavKey.Inventory -> detail(R.string.term_inventory, products)

    is AppNavKey.StockAdjustment -> detail(R.string.stock_adjust_title, products)

    AppNavKey.Suppliers -> detail(R.string.product_tab_suppliers, products)

    is AppNavKey.SupplierDetail -> detail(R.string.product_tab_suppliers, products)

    AppNavKey.Categories -> detail(R.string.categories_title, products)

    is AppNavKey.Variants -> detail(R.string.variant_title, products)

    is AppNavKey.Components -> detail(R.string.combo_title, products)

    is AppNavKey.Extras -> detail(R.string.extras_title, products)

    AppNavKey.Combos -> detail(R.string.combo_title, business)

    AppNavKey.BusinessHub -> detail(R.string.hub_title, business)

    is AppNavKey.Customers -> detail(R.string.customer_title, business)

    is AppNavKey.CustomerDetail -> detail(R.string.customer_title, business)

    AppNavKey.LoyaltyProgram -> detail(R.string.loyalty_title, business)

    is AppNavKey.MoneyAccounts -> detail(R.string.money_title, business)

    AppNavKey.Payables -> detail(R.string.payable_title, business)

    is AppNavKey.PayableDetail -> detail(R.string.payable_title, business)

    AppNavKey.CashRegister -> detail(R.string.cash_title, business)

    is AppNavKey.SupplierReturn -> detail(R.string.supplier_return_title, business)

    is AppNavKey.SupplierReturnDetail -> detail(R.string.supplier_return_title, business)

    AppNavKey.PriceLists -> detail(R.string.price_list_title, business)

    AppNavKey.ReferenceCurrency -> detail(R.string.reference_title, business)

    AppNavKey.Units -> detail(R.string.unit_title, business)

    AppNavKey.CustomFields -> detail(R.string.field_title, business)

    is AppNavKey.Labels -> detail(R.string.label_title, business)

    is AppNavKey.Lots -> detail(R.string.lot_title, business)

    AppNavKey.Locations -> detail(R.string.location_title, business)

    is AppNavKey.NewStockTransfer -> detail(R.string.location_title, business)

    is AppNavKey.StockCount -> detail(R.string.count_title, business)

    AppNavKey.Production -> detail(R.string.production_title, business)

    is AppNavKey.ProductionForm -> detail(R.string.production_new_action, business)

    is AppNavKey.Replenishment -> detail(R.string.reorder_title, business)

    AppNavKey.ImportData -> detail(R.string.import_title, business)

    is AppNavKey.Quotes -> detail(R.string.quote_title, business)

    is AppNavKey.Orders -> detail(R.string.order_title, business)

    AppNavKey.PendingFiscal -> detail(R.string.fiscal_title, business)

    AppNavKey.ShareableCatalog -> detail(R.string.catalog_title, business)

    AppNavKey.ManagementSummary -> detail(R.string.report_title, numbers)

    AppNavKey.GoalsAndLimits -> detail(R.string.goal_screen_title, numbers)

    AppNavKey.RecurringExpenses -> detail(R.string.recurring_title, business)

    AppNavKey.PriceIndex -> detail(R.string.index_title, numbers)

    AppNavKey.Settings -> detail(R.string.settings_title, settings)

    AppNavKey.Backup -> detail(R.string.backup_title, settings)

    is AppNavKey.RegionalSettings -> detail(R.string.region_title, settings)

    AppNavKey.LanguageSettings -> detail(R.string.language_title, settings)

    AppNavKey.TaxSettings -> detail(R.string.tax_title, settings)

    AppNavKey.PaymentMethods -> detail(R.string.payment_method_title, settings)

    is AppNavKey.PaymentMethodEdit ->
        detail(
            if (key.methodId == null) {
                R.string.payment_method_new_action
            } else {
                R.string.payment_method_edit_title
            },
            settings,
        )

    AppNavKey.ReceiptTemplate -> detail(R.string.receipt_template_title, settings)

    AppNavKey.Printers -> detail(R.string.printer_title, settings)

    is AppNavKey.Help -> detail(R.string.help_title, settings)

    AppNavKey.BackupEncryption -> detail(R.string.encryption_title, settings)

    AppNavKey.LocalCopies -> detail(R.string.offsite_title, settings)

    AppNavKey.StorageAndDeletion -> detail(R.string.storage_title, settings)

    AppNavKey.DataTransparency -> detail(R.string.transparency_title, settings)

    AppNavKey.UpdatesAndApkCheck -> detail(R.string.update_title, settings)

    AppNavKey.AuditLog -> detail(R.string.audit_title, settings)

    AppNavKey.CloudAccountSecurity -> detail(R.string.account_title, settings)

    is AppNavKey.BackupDestinationChoice -> detail(R.string.destination_title, settings)

    is AppNavKey.WebDavConnect -> detail(R.string.webdav_title, settings)

    AppNavKey.DocumentSlotsSetup -> detail(R.string.slots_title, settings)
}

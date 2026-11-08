package io.github.yesidrobayo.shopledger.data.local.experience

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Si cada herramienta del centro `Negocio` tiene datos, en una sola fila de banderas 0 o 1. Las
 * herramientas que no dejan datos, como el catálogo compartible o importar, no tienen columna.
 */
internal data class ModuleUsageRow(
    @ColumnInfo(name = "customers") val customers: Long,
    @ColumnInfo(name = "loyalty") val loyalty: Long,
    @ColumnInfo(name = "money_accounts") val moneyAccounts: Long,
    @ColumnInfo(name = "payables") val payables: Long,
    @ColumnInfo(name = "cash_register") val cashRegister: Long,
    @ColumnInfo(name = "held_sales") val heldSales: Long,
    @ColumnInfo(name = "quotes") val quotes: Long,
    @ColumnInfo(name = "orders") val orders: Long,
    @ColumnInfo(name = "pending_fiscal") val pendingFiscal: Long,
    @ColumnInfo(name = "price_lists") val priceLists: Long,
    @ColumnInfo(name = "reference_currency") val referenceCurrency: Long,
    @ColumnInfo(name = "units") val units: Long,
    @ColumnInfo(name = "combos") val combos: Long,
    @ColumnInfo(name = "custom_fields") val customFields: Long,
    @ColumnInfo(name = "lots") val lots: Long,
    @ColumnInfo(name = "locations") val locations: Long,
    @ColumnInfo(name = "stock_count") val stockCount: Long,
    @ColumnInfo(name = "production") val production: Long,
    @ColumnInfo(name = "supplier_returns") val supplierReturns: Long,
    @ColumnInfo(name = "goals_and_limits") val goalsAndLimits: Long,
    @ColumnInfo(name = "recurring_expenses") val recurringExpenses: Long,
    @ColumnInfo(name = "price_index") val priceIndex: Long,
)

/**
 * La consulta de solo lectura que decide qué herramientas están en uso. Cada condición es la de la
 * tabla "Cuándo está en uso" del diseño, con `EXISTS`, que se detiene en la primera fila: cuesta lo
 * mismo con diez filas que con cien mil.
 *
 * No escribe nada ni tiene tabla propia. Room la vuelve a correr cuando cambia cualquiera de las
 * tablas que lee.
 */
@Dao
internal interface ModuleUsageDao {
    @Query(
        """
        SELECT
          EXISTS (SELECT 1 FROM customers WHERE archived_at IS NULL) AS customers,
          IFNULL((SELECT loyalty_enabled = 1 FROM app_settings WHERE id = 1), 0) AS loyalty,
          (
            (SELECT COUNT(*) FROM money_accounts WHERE archived_at IS NULL) > 1
            OR EXISTS (SELECT 1 FROM money_movements)
          ) AS money_accounts,
          EXISTS (SELECT 1 FROM supplier_ledger_entries) AS payables,
          EXISTS (SELECT 1 FROM cash_sessions) AS cash_register,
          EXISTS (SELECT 1 FROM sale_drafts) AS held_sales,
          EXISTS (SELECT 1 FROM quotes) AS quotes,
          EXISTS (SELECT 1 FROM orders) AS orders,
          (
            EXISTS (SELECT 1 FROM sale_fiscal_marks)
            OR IFNULL((SELECT fiscal_portal_url IS NOT NULL FROM app_settings WHERE id = 1), 0)
          ) AS pending_fiscal,
          (SELECT COUNT(*) FROM price_lists WHERE archived_at IS NULL) > 1 AS price_lists,
          IFNULL((SELECT reference_currency_code IS NOT NULL FROM app_settings WHERE id = 1), 0)
            AS reference_currency,
          (
            EXISTS (SELECT 1 FROM units WHERE system_key IS NULL)
            OR EXISTS (SELECT 1 FROM product_units)
          ) AS units,
          EXISTS (SELECT 1 FROM product_components) AS combos,
          (
            EXISTS (SELECT 1 FROM custom_field_definitions) OR EXISTS (SELECT 1 FROM tags)
          ) AS custom_fields,
          EXISTS (SELECT 1 FROM products WHERE tracks_lots = 1) AS lots,
          (SELECT COUNT(*) FROM locations WHERE archived_at IS NULL) > 1 AS locations,
          EXISTS (SELECT 1 FROM count_sessions) AS stock_count,
          EXISTS (SELECT 1 FROM production_batches) AS production,
          EXISTS (SELECT 1 FROM purchase_returns) AS supplier_returns,
          (
            EXISTS (SELECT 1 FROM business_goals)
            OR IFNULL((SELECT revenue_limit_amount IS NOT NULL FROM app_settings WHERE id = 1), 0)
          ) AS goals_and_limits,
          EXISTS (SELECT 1 FROM recurring_expenses WHERE archived_at IS NULL)
            AS recurring_expenses,
          EXISTS (SELECT 1 FROM price_index_entries) AS price_index
        """,
    )
    fun observe(): Flow<ModuleUsageRow>
}

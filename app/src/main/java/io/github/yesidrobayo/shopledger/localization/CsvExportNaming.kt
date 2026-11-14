package io.github.yesidrobayo.shopledger.localization

import android.content.res.Resources
import androidx.annotation.StringRes
import io.github.yesidrobayo.shopledger.R
import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExportTable
import io.github.yesidrobayo.shopledger.domain.port.CsvNaming

/**
 * Los nombres de la exportación CSV en el idioma de la interfaz: cada archivo con su nombre, como
 * `ventas.csv`, `sales.csv` o `vendas.csv`, cada columna con su encabezado, y cada fila inicial que
 * nadie renombró con su nombre, como la unidad o la caja. La clave estable en inglés de cada archivo y
 * columna no cambia: es la que reconoce la importación en cualquier idioma.
 *
 * Cada clave apunta a su recurso por una tabla escrita aquí, no por un nombre armado en tiempo de
 * ejecución: así el compilador comprueba que el recurso existe, y la optimización de recursos no lo
 * descarta. Una columna que esta tabla no conoce sale con su clave: nunca queda sin encabezado.
 *
 * [resources] da los recursos en el idioma elegido para la app cada vez que se pide un nombre.
 */
class CsvExportNaming(private val resources: () -> Resources) : CsvNaming {
    override fun fileName(table: CsvExportTable): String = resources().getString(fileRes(table))

    override fun header(column: String): String =
        headerRes(column)?.let { resources().getString(it) } ?: column

    override fun seedName(table: SeedTable, systemKey: String): String =
        SeedText.displayName(resources(), table, systemKey, name = null)

    companion object {
        /** El recurso del nombre del archivo de [table]. */
        @StringRes
        fun fileRes(table: CsvExportTable): Int = when (table) {
            CsvExportTable.PRODUCTS -> R.string.csv_file_products
            CsvExportTable.SUPPLIERS -> R.string.csv_file_suppliers
            CsvExportTable.SALES -> R.string.csv_file_sales
            CsvExportTable.SALE_LINES -> R.string.csv_file_sale_lines
            CsvExportTable.PURCHASES -> R.string.csv_file_purchases
            CsvExportTable.PURCHASE_LINES -> R.string.csv_file_purchase_lines
            CsvExportTable.STOCK_MOVEMENTS -> R.string.csv_file_stock_movements
            CsvExportTable.EXPENSES -> R.string.csv_file_expenses
            CsvExportTable.PRICE_HISTORY -> R.string.csv_file_price_history
            CsvExportTable.TAX_RATES -> R.string.csv_file_tax_rates
            CsvExportTable.PAYMENT_METHODS -> R.string.csv_file_payment_methods
            CsvExportTable.SALE_PAYMENTS -> R.string.csv_file_sale_payments
            CsvExportTable.CUSTOMERS -> R.string.csv_file_customers
            CsvExportTable.CUSTOMER_LEDGER -> R.string.csv_file_customer_ledger
            CsvExportTable.LOYALTY_LEDGER -> R.string.csv_file_loyalty_ledger
            CsvExportTable.MONEY_ACCOUNTS -> R.string.csv_file_money_accounts
            CsvExportTable.MONEY_MOVEMENTS -> R.string.csv_file_money_movements
            CsvExportTable.SUPPLIER_LEDGER -> R.string.csv_file_supplier_ledger
            CsvExportTable.CASH_SESSIONS -> R.string.csv_file_cash_sessions
            CsvExportTable.CASH_SESSION_COUNTS -> R.string.csv_file_cash_session_counts
            CsvExportTable.SALE_RETURNS -> R.string.csv_file_sale_returns
            CsvExportTable.SALE_RETURN_LINES -> R.string.csv_file_sale_return_lines
            CsvExportTable.PURCHASE_RETURNS -> R.string.csv_file_purchase_returns
            CsvExportTable.PURCHASE_RETURN_LINES -> R.string.csv_file_purchase_return_lines
            CsvExportTable.PRICE_LISTS -> R.string.csv_file_price_lists
            CsvExportTable.PRODUCT_PRICES -> R.string.csv_file_product_prices
            CsvExportTable.FX_RATES -> R.string.csv_file_fx_rates
            CsvExportTable.PRICE_INDEX -> R.string.csv_file_price_index
            CsvExportTable.UNITS -> R.string.csv_file_units
            CsvExportTable.PRODUCT_UNITS -> R.string.csv_file_product_units
            CsvExportTable.PRODUCT_COMPONENTS -> R.string.csv_file_product_components
            CsvExportTable.PRODUCT_MODIFIERS -> R.string.csv_file_product_modifiers
            CsvExportTable.CUSTOM_FIELDS -> R.string.csv_file_custom_fields
            CsvExportTable.PRODUCT_CUSTOM_VALUES -> R.string.csv_file_product_custom_values
            CsvExportTable.TAGS -> R.string.csv_file_tags
            CsvExportTable.PRODUCT_TAGS -> R.string.csv_file_product_tags
            CsvExportTable.MEDIA_ASSETS -> R.string.csv_file_media_assets
            CsvExportTable.SALE_LINE_COMPONENTS -> R.string.csv_file_sale_line_components
            CsvExportTable.LOCATIONS -> R.string.csv_file_locations
            CsvExportTable.STOCK_BALANCES -> R.string.csv_file_stock_balances
            CsvExportTable.STOCK_LOTS -> R.string.csv_file_stock_lots
            CsvExportTable.STOCK_TRANSFERS -> R.string.csv_file_stock_transfers
            CsvExportTable.STOCK_TRANSFER_LINES -> R.string.csv_file_stock_transfer_lines
            CsvExportTable.PRODUCTION_BATCHES -> R.string.csv_file_production_batches
            CsvExportTable.PRODUCTION_BATCH_LINES -> R.string.csv_file_production_batch_lines
            CsvExportTable.COUNT_SESSIONS -> R.string.csv_file_count_sessions
            CsvExportTable.COUNT_SESSION_LINES -> R.string.csv_file_count_session_lines
            CsvExportTable.QUOTES -> R.string.csv_file_quotes
            CsvExportTable.QUOTE_LINES -> R.string.csv_file_quote_lines
            CsvExportTable.ORDERS -> R.string.csv_file_orders
            CsvExportTable.ORDER_LINES -> R.string.csv_file_order_lines
            CsvExportTable.SALE_FISCAL_MARKS -> R.string.csv_file_sale_fiscal_marks
            CsvExportTable.DOCUMENT_ATTACHMENTS -> R.string.csv_file_document_attachments
            CsvExportTable.BUSINESS_GOALS -> R.string.csv_file_business_goals
            CsvExportTable.RECURRING_EXPENSES -> R.string.csv_file_recurring_expenses
        }

        /** El recurso del encabezado de la columna [column], o `null` si no tiene. */
        @StringRes
        fun headerRes(column: String): Int? = when (column) {
            "name" -> R.string.csv_header_name
            "category" -> R.string.csv_header_category
            "default_supplier" -> R.string.csv_header_default_supplier
            "base_unit" -> R.string.csv_header_base_unit
            "stock_mode" -> R.string.csv_header_stock_mode
            "barcode" -> R.string.csv_header_barcode
            "sale_price" -> R.string.csv_header_sale_price
            "average_cost" -> R.string.csv_header_average_cost
            "initial_cost" -> R.string.csv_header_initial_cost
            "stock_value" -> R.string.csv_header_stock_value
            "on_hand" -> R.string.csv_header_on_hand
            "min_stock" -> R.string.csv_header_min_stock
            "archived" -> R.string.csv_header_archived
            "usage" -> R.string.csv_header_usage
            "phone" -> R.string.csv_header_phone
            "email" -> R.string.csv_header_email
            "notes" -> R.string.csv_header_notes
            "balance" -> R.string.csv_header_balance
            "payment_terms_days" -> R.string.csv_header_payment_terms_days
            "sale_id" -> R.string.csv_header_sale_id
            "date" -> R.string.csv_header_date
            "time" -> R.string.csv_header_time
            "created_at" -> R.string.csv_header_created_at
            "customer" -> R.string.csv_header_customer
            "price_list" -> R.string.csv_header_price_list
            "net_total" -> R.string.csv_header_net_total
            "tax_total" -> R.string.csv_header_tax_total
            "order_adjustment" -> R.string.csv_header_order_adjustment
            "tip_amount" -> R.string.csv_header_tip_amount
            "cash_rounding_amount" -> R.string.csv_header_cash_rounding_amount
            "total" -> R.string.csv_header_total
            "amount_due" -> R.string.csv_header_amount_due
            "total_cost" -> R.string.csv_header_total_cost
            "sales_profit" -> R.string.csv_header_sales_profit
            "status" -> R.string.csv_header_status
            "voided_at" -> R.string.csv_header_voided_at
            "void_reason" -> R.string.csv_header_void_reason
            "void_note" -> R.string.csv_header_void_note
            "product" -> R.string.csv_header_product
            "quantity" -> R.string.csv_header_quantity
            "entered_quantity" -> R.string.csv_header_entered_quantity
            "unit" -> R.string.csv_header_unit
            "unit_price" -> R.string.csv_header_unit_price
            "unit_cost" -> R.string.csv_header_unit_cost
            "line_discount" -> R.string.csv_header_line_discount
            "order_adjustment_share" -> R.string.csv_header_order_adjustment_share
            "net_amount" -> R.string.csv_header_net_amount
            "tax_rate" -> R.string.csv_header_tax_rate
            "tax_amount" -> R.string.csv_header_tax_amount
            "line_total" -> R.string.csv_header_line_total
            "line_cost" -> R.string.csv_header_line_cost
            "stock_effect" -> R.string.csv_header_stock_effect
            "purchase_id" -> R.string.csv_header_purchase_id
            "supplier" -> R.string.csv_header_supplier
            "invoice_reference" -> R.string.csv_header_invoice_reference
            "cost_total" -> R.string.csv_header_cost_total
            "amount_paid" -> R.string.csv_header_amount_paid
            "due_date" -> R.string.csv_header_due_date
            "cost_amount" -> R.string.csv_header_cost_amount
            "lot" -> R.string.csv_header_lot
            "movement_id" -> R.string.csv_header_movement_id
            "location" -> R.string.csv_header_location
            "type" -> R.string.csv_header_type
            "reason" -> R.string.csv_header_reason
            "average_cost_after" -> R.string.csv_header_average_cost_after
            "stock_value_after" -> R.string.csv_header_stock_value_after
            "reverses_movement_id" -> R.string.csv_header_reverses_movement_id
            "note" -> R.string.csv_header_note
            "expense_id" -> R.string.csv_header_expense_id
            "description" -> R.string.csv_header_description
            "amount" -> R.string.csv_header_amount
            "money_account" -> R.string.csv_header_money_account
            "old_price" -> R.string.csv_header_old_price
            "new_price" -> R.string.csv_header_new_price
            "source" -> R.string.csv_header_source
            "month" -> R.string.csv_header_month
            "change_percent" -> R.string.csv_header_change_percent
            "id" -> R.string.csv_header_id
            "system_key" -> R.string.csv_header_system_key
            "kind" -> R.string.csv_header_kind
            "updated_at" -> R.string.csv_header_updated_at
            "rate" -> R.string.csv_header_rate
            "fee_percent" -> R.string.csv_header_fee_percent
            "fee_fixed" -> R.string.csv_header_fee_fixed
            "settlement_days" -> R.string.csv_header_settlement_days
            "price_adjustment_percent" -> R.string.csv_header_price_adjustment_percent
            "instructions" -> R.string.csv_header_instructions
            "instructions_image" -> R.string.csv_header_instructions_image
            "sort_order" -> R.string.csv_header_sort_order
            "payment_method" -> R.string.csv_header_payment_method
            "payment_method_kind" -> R.string.csv_header_payment_method_kind
            "fee_amount" -> R.string.csv_header_fee_amount
            "settlement_date" -> R.string.csv_header_settlement_date
            "tendered_amount" -> R.string.csv_header_tendered_amount
            "change_amount" -> R.string.csv_header_change_amount
            "tender_currency_code" -> R.string.csv_header_tender_currency_code
            "tender_currency_decimals" -> R.string.csv_header_tender_currency_decimals
            "tender_amount" -> R.string.csv_header_tender_amount
            "fx_rate" -> R.string.csv_header_fx_rate
            "points_balance" -> R.string.csv_header_points_balance
            "anonymized_at" -> R.string.csv_header_anonymized_at
            "balance_after" -> R.string.csv_header_balance_after
            "sale_return_id" -> R.string.csv_header_sale_return_id
            "reverses_entry_id" -> R.string.csv_header_reverses_entry_id
            "points" -> R.string.csv_header_points
            "currency_code" -> R.string.csv_header_currency_code
            "currency_decimals" -> R.string.csv_header_currency_decimals
            "available_date" -> R.string.csv_header_available_date
            "transfer_group_id" -> R.string.csv_header_transfer_group_id
            "sale_payment_id" -> R.string.csv_header_sale_payment_id
            "customer_ledger_entry_id" -> R.string.csv_header_customer_ledger_entry_id
            "supplier_ledger_entry_id" -> R.string.csv_header_supplier_ledger_entry_id
            "purchase_return_id" -> R.string.csv_header_purchase_return_id
            "cash_session_id" -> R.string.csv_header_cash_session_id
            "opening_amount" -> R.string.csv_header_opening_amount
            "expected_amount" -> R.string.csv_header_expected_amount
            "counted_amount" -> R.string.csv_header_counted_amount
            "difference_amount" -> R.string.csv_header_difference_amount
            "opened_at" -> R.string.csv_header_opened_at
            "closed_at" -> R.string.csv_header_closed_at
            "phase" -> R.string.csv_header_phase
            "denomination" -> R.string.csv_header_denomination
            "count" -> R.string.csv_header_count
            "resolution" -> R.string.csv_header_resolution
            "net_refund" -> R.string.csv_header_net_refund
            "tax_refund" -> R.string.csv_header_tax_refund
            "total_refund" -> R.string.csv_header_total_refund
            "cost_returned" -> R.string.csv_header_cost_returned
            "replacement_sale_id" -> R.string.csv_header_replacement_sale_id
            "sale_line_id" -> R.string.csv_header_sale_line_id
            "restock" -> R.string.csv_header_restock
            "purchase_line_id" -> R.string.csv_header_purchase_line_id
            "base_price_list" -> R.string.csv_header_base_price_list
            "adjustment_percent" -> R.string.csv_header_adjustment_percent
            "price" -> R.string.csv_header_price
            "source_note" -> R.string.csv_header_source_note
            "symbol" -> R.string.csv_header_symbol
            "allows_fraction" -> R.string.csv_header_allows_fraction
            "factor" -> R.string.csv_header_factor
            "for_sale" -> R.string.csv_header_for_sale
            "for_purchase" -> R.string.csv_header_for_purchase
            "parent_product" -> R.string.csv_header_parent_product
            "component_product" -> R.string.csv_header_component_product
            "modifier_product" -> R.string.csv_header_modifier_product
            "group_name" -> R.string.csv_header_group_name
            "field_type" -> R.string.csv_header_field_type
            "custom_field" -> R.string.csv_header_custom_field
            "value" -> R.string.csv_header_value
            "tag" -> R.string.csv_header_tag
            "sha256" -> R.string.csv_header_sha256
            "mime_type" -> R.string.csv_header_mime_type
            "width_px" -> R.string.csv_header_width_px
            "height_px" -> R.string.csv_header_height_px
            "size_bytes" -> R.string.csv_header_size_bytes
            "orphaned_at" -> R.string.csv_header_orphaned_at
            "tracked" -> R.string.csv_header_tracked
            "lot_code" -> R.string.csv_header_lot_code
            "is_unassigned" -> R.string.csv_header_is_unassigned
            "expiry_date" -> R.string.csv_header_expiry_date
            "expiry_notified_at" -> R.string.csv_header_expiry_notified_at
            "from_location" -> R.string.csv_header_from_location
            "to_location" -> R.string.csv_header_to_location
            "transfer_id" -> R.string.csv_header_transfer_id
            "production_batch_id" -> R.string.csv_header_production_batch_id
            "is_blind" -> R.string.csv_header_is_blind
            "started_at" -> R.string.csv_header_started_at
            "finished_at" -> R.string.csv_header_finished_at
            "count_session_id" -> R.string.csv_header_count_session_id
            "expected" -> R.string.csv_header_expected
            "counted" -> R.string.csv_header_counted
            "valid_until" -> R.string.csv_header_valid_until
            "converted_sale_id" -> R.string.csv_header_converted_sale_id
            "quote_id" -> R.string.csv_header_quote_id
            "order_id" -> R.string.csv_header_order_id
            "unit_factor" -> R.string.csv_header_unit_factor
            "tax_name" -> R.string.csv_header_tax_name
            "product_name" -> R.string.csv_header_product_name
            "delivery_address" -> R.string.csv_header_delivery_address
            "delivery_note" -> R.string.csv_header_delivery_note
            "shipping_amount" -> R.string.csv_header_shipping_amount
            "delivered_sale_id" -> R.string.csv_header_delivered_sale_id
            "external_reference" -> R.string.csv_header_external_reference
            "marked_at" -> R.string.csv_header_marked_at
            "media_asset" -> R.string.csv_header_media_asset
            "caption" -> R.string.csv_header_caption
            "sales_goal" -> R.string.csv_header_sales_goal
            "profit_goal" -> R.string.csv_header_profit_goal
            "recurrence" -> R.string.csv_header_recurrence
            "day_of_period" -> R.string.csv_header_day_of_period
            "next_due_date" -> R.string.csv_header_next_due_date
            "reminder_enabled" -> R.string.csv_header_reminder_enabled
            else -> null
        }
    }
}

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
            else -> null
        }
    }
}

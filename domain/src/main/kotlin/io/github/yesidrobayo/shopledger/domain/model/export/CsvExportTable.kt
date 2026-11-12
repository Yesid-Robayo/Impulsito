package io.github.yesidrobayo.shopledger.domain.model.export

/**
 * Los archivos de la exportación CSV, cada uno con su clave estable en inglés y sus columnas, en el
 * orden en que salen.
 *
 * La clave y los nombres de columna nunca cambian: de ellos salen el nombre del archivo y los
 * encabezados en el idioma de la interfaz, y la importación los reconoce en cualquier idioma. Una
 * columna nueva va al final de su archivo, para que una hoja armada sobre una exportación anterior
 * siga leyendo las mismas columnas en el mismo lugar.
 *
 * El orden de los archivos es el de los catálogos primero y los documentos y libros después.
 */
enum class CsvExportTable(
    /** La clave del archivo, como `sales`. */
    val key: String,
    /** Las claves de sus columnas, en orden. */
    val columns: List<String>,
) {
    PRODUCTS(
        "products",
        listOf(
            "name",
            "category",
            "default_supplier",
            "base_unit",
            "stock_mode",
            "barcode",
            "sale_price",
            "average_cost",
            "initial_cost",
            "stock_value",
            "on_hand",
            "min_stock",
            "archived",
        ),
    ),
    SUPPLIERS(
        "suppliers",
        listOf("name", "phone", "email", "notes", "balance", "payment_terms_days", "archived"),
    ),
    SALES(
        "sales",
        listOf(
            "sale_id",
            "date",
            "time",
            "created_at",
            "customer",
            "price_list",
            "net_total",
            "tax_total",
            "order_adjustment",
            "tip_amount",
            "cash_rounding_amount",
            "total",
            "amount_due",
            "total_cost",
            "sales_profit",
            "status",
            "voided_at",
            "void_reason",
            "void_note",
        ),
    ),
    SALE_LINES(
        "sale_lines",
        listOf(
            "sale_id",
            "product",
            "quantity",
            "base_unit",
            "entered_quantity",
            "unit",
            "unit_price",
            "unit_cost",
            "line_discount",
            "order_adjustment_share",
            "net_amount",
            "tax_rate",
            "tax_amount",
            "line_total",
            "line_cost",
            "stock_effect",
        ),
    ),
    PURCHASES(
        "purchases",
        listOf(
            "purchase_id",
            "date",
            "created_at",
            "supplier",
            "invoice_reference",
            "net_total",
            "tax_total",
            "cost_total",
            "total",
            "amount_paid",
            "due_date",
            "status",
            "voided_at",
            "void_reason",
            "void_note",
        ),
    ),
    PURCHASE_LINES(
        "purchase_lines",
        listOf(
            "purchase_id",
            "product",
            "quantity",
            "base_unit",
            "entered_quantity",
            "unit",
            "unit_cost",
            "tax_rate",
            "tax_amount",
            "cost_amount",
            "line_total",
            "lot",
        ),
    ),
    STOCK_MOVEMENTS(
        "stock_movements",
        listOf(
            "movement_id",
            "date",
            "time",
            "created_at",
            "product",
            "location",
            "lot",
            "type",
            "reason",
            "quantity",
            "unit_cost",
            "total_cost",
            "average_cost_after",
            "stock_value_after",
            "reverses_movement_id",
            "note",
        ),
    ),
    EXPENSES(
        "expenses",
        listOf(
            "expense_id",
            "date",
            "created_at",
            "category",
            "description",
            "amount",
            "money_account",
            "status",
            "voided_at",
            "void_reason",
            "void_note",
        ),
    ),
    PRICE_HISTORY(
        "price_history",
        listOf("date", "time", "product", "price_list", "old_price", "new_price", "source"),
    ),
}

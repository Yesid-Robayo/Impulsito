package io.github.yesidrobayo.shopledger.data.backup

/**
 * Los conteos neutros de una base: cuántos registros y cuánto catálogo tiene, y si está vacía. Los
 * lleva cada copia en sus metadatos, para decidir antes de bajarla si tiene menos datos que el
 * teléfono.
 *
 * No dependen de ninguna moneda, región ni idioma: solo cuentan filas. Los registros son las tablas
 * que solo crecen, porque nada se borra en ellas; el catálogo puede bajar, porque un producto sin
 * historial se puede eliminar. Las consultas son texto fijo, el mismo sobre la base del teléfono y
 * sobre la instantánea de una copia.
 */
internal object NeutralCounts {
    /** Las tablas que solo crecen: ventas, compras, gastos, devoluciones y los libros. */
    val RECORD_TABLES =
        listOf(
            "sales",
            "purchases",
            "expenses",
            "stock_movements",
            "sale_returns",
            "purchase_returns",
            "customer_ledger_entries",
            "supplier_ledger_entries",
            "money_movements",
            "loyalty_ledger_entries",
        )

    /** Las tablas del catálogo: productos, proveedores y clientes. */
    val CATALOG_TABLES = listOf("products", "suppliers", "customers")

    /** Las tablas que, con una sola fila, dicen que la base tiene algo del negocio. */
    val CONTENT_TABLES =
        listOf(
            "products",
            "suppliers",
            "customers",
            "sales",
            "purchases",
            "expenses",
            "quotes",
            "orders",
            "money_movements",
        )

    /** Cuántos registros tiene la base: una fila, una columna. */
    const val RECORD_COUNT =
        "SELECT (SELECT COUNT(*) FROM sales) + (SELECT COUNT(*) FROM purchases) + " +
            "(SELECT COUNT(*) FROM expenses) + (SELECT COUNT(*) FROM stock_movements) + " +
            "(SELECT COUNT(*) FROM sale_returns) + (SELECT COUNT(*) FROM purchase_returns) + " +
            "(SELECT COUNT(*) FROM customer_ledger_entries) + " +
            "(SELECT COUNT(*) FROM supplier_ledger_entries) + " +
            "(SELECT COUNT(*) FROM money_movements) + (SELECT COUNT(*) FROM loyalty_ledger_entries)"

    /** Cuánto catálogo tiene la base: una fila, una columna. */
    const val CATALOG_COUNT =
        "SELECT (SELECT COUNT(*) FROM products) + (SELECT COUNT(*) FROM suppliers) + " +
            "(SELECT COUNT(*) FROM customers)"

    /** Si la base no tiene nada del negocio: una fila, una columna, 1 o 0. */
    const val IS_EMPTY =
        "SELECT NOT EXISTS (SELECT 1 FROM products) AND NOT EXISTS (SELECT 1 FROM suppliers) " +
            "AND NOT EXISTS (SELECT 1 FROM customers) AND NOT EXISTS (SELECT 1 FROM sales) " +
            "AND NOT EXISTS (SELECT 1 FROM purchases) AND NOT EXISTS (SELECT 1 FROM expenses) " +
            "AND NOT EXISTS (SELECT 1 FROM quotes) AND NOT EXISTS (SELECT 1 FROM orders) " +
            "AND NOT EXISTS (SELECT 1 FROM money_movements)"
}

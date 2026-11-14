package io.github.yesidrobayo.shopledger.data.export

import androidx.sqlite.SQLiteStatement
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.SeedTable
import io.github.yesidrobayo.shopledger.domain.model.export.CsvCell
import io.github.yesidrobayo.shopledger.domain.model.export.CsvExportTable

/**
 * La consulta de un archivo de la exportación y cómo convierte cada fila en sus celdas.
 *
 * Las columnas del `SELECT` van en el orden de las columnas del archivo, y [cells] las lee en ese
 * orden con [CsvRow]. Un nombre de otra tabla sale con `LEFT JOIN`: un registro nunca se pierde
 * porque falte su categoría o su proveedor, solo queda esa celda vacía. Las filas salen en orden de
 * `id`, el orden en que se registraron.
 *
 * @param rangeFilter la condición que deja solo las fechas de negocio de un rango, con `?1` para la
 *   primera y `?2` para la última; `null` en los catálogos, que van completos.
 */
internal class CsvTableQuery(
    private val sql: String,
    private val rangeFilter: String? = null,
    val cells: (CsvRow) -> List<CsvCell>,
) {
    /** La consulta sin rango, o con su condición antes del orden, si este archivo se filtra. */
    fun sqlFor(ranged: Boolean): String = if (ranged && rangeFilter != null) {
        sql.replace(ORDER_BY, "WHERE $rangeFilter\n$ORDER_BY")
    } else {
        sql
    }

    /** Si esta consulta lleva las dos fechas del rango como parámetros. */
    fun usesRange(ranged: Boolean): Boolean = ranged && rangeFilter != null

    private companion object {
        const val ORDER_BY = "ORDER BY"
    }
}

/**
 * Lee la fila actual de una consulta, una columna tras otra, como celdas de la exportación. Un valor
 * nulo da una celda vacía.
 *
 * @param seedName el nombre visible de una fila inicial que nadie renombró.
 */
internal class CsvRow(
    private val statement: SQLiteStatement,
    private val seedName: (SeedTable, String) -> String,
) {
    private var index = 0

    /** Cuántas columnas de la fila se leyeron hasta ahora. */
    val columnsRead: Int
        get() = index

    fun text(): CsvCell = next { CsvCell.Text(statement.getText(it)) }

    fun code(): CsvCell = next { CsvCell.Code(statement.getText(it)) }

    fun amount(): CsvCell = next { CsvCell.Amount(Money(statement.getLong(it))) }

    fun quantity(): CsvCell = next { CsvCell.Quantity(QuantityMilli(statement.getLong(it))) }

    fun count(): CsvCell = next { CsvCell.Count(statement.getLong(it)) }

    fun percent(): CsvCell = next { CsvCell.Percent(BasisPoints(statement.getLong(it))) }

    fun day(): CsvCell = next { CsvCell.Day(statement.getText(it)) }

    fun moment(): CsvCell = next { CsvCell.Moment(statement.getLong(it)) }

    fun timeOfDay(): CsvCell = next { CsvCell.TimeOfDay(statement.getLong(it)) }

    /** Un monto que también sirve para una cuenta, como la ganancia; cero si es nulo. */
    fun amountValue(): Money {
        val at = index++
        return if (statement.isNull(at)) Money.ZERO else Money(statement.getLong(at))
    }

    /**
     * Un número con sus decimales en la columna siguiente, como el saldo de una cuenta con los de
     * su moneda: lee dos columnas. Con decimales fuera de lo que admite una celda, algo que solo
     * trae una base dañada, sale el entero guardado tal cual: la exportación sigue igual.
     */
    fun scaled(): CsvCell {
        val valueAt = index++
        val decimalsAt = index++
        if (statement.isNull(valueAt)) return CsvCell.Empty
        val value = statement.getLong(valueAt)
        val decimals = if (statement.isNull(decimalsAt)) -1L else statement.getLong(decimalsAt)
        return if (decimals in 0L..CsvCell.Scaled.MAX_DECIMALS.toLong()) {
            CsvCell.Scaled(value, decimals.toInt())
        } else {
            CsvCell.Count(value)
        }
    }

    /** Una cotización guardada en millonésimas, con sus seis decimales. */
    fun micro(): CsvCell =
        next { CsvCell.Scaled(statement.getLong(it), CsvCell.Scaled.MICRO_DECIMALS) }

    /**
     * El nombre de una fila de [table] que puede ser inicial: dos columnas, `system_key` y `name`.
     * Vale el nombre si la persona lo escribió; si no, el de la clave en el idioma de la interfaz.
     */
    fun seed(table: SeedTable): CsvCell {
        val keyAt = index++
        val nameAt = index++
        return when {
            !statement.isNull(nameAt) -> CsvCell.Text(statement.getText(nameAt))
            !statement.isNull(keyAt) -> CsvCell.Text(seedName(table, statement.getText(keyAt)))
            else -> CsvCell.Empty
        }
    }

    private inline fun next(read: (Int) -> CsvCell): CsvCell {
        val at = index++
        return if (statement.isNull(at)) CsvCell.Empty else read(at)
    }
}

/**
 * Las consultas de cada archivo de [CsvExportTable].
 *
 * Una clave foránea a un catálogo sale por su nombre, y una a un documento, a una línea o a un
 * archivo de fotos, por su número, que es el `id` de su propio archivo. Los montos en la moneda de
 * una cuenta salen con los decimales de esa moneda, y las cotizaciones, con seis. Cada consulta da
 * una fila por registro de su tabla: sus `LEFT JOIN` solo buscan un nombre por su clave.
 */
internal object CsvTableQueries {
    fun of(table: CsvExportTable): CsvTableQuery = when (table) {
        CsvExportTable.PRODUCTS -> PRODUCTS
        CsvExportTable.SUPPLIERS -> SUPPLIERS
        CsvExportTable.SALES -> SALES
        CsvExportTable.SALE_LINES -> SALE_LINES
        CsvExportTable.PURCHASES -> PURCHASES
        CsvExportTable.PURCHASE_LINES -> PURCHASE_LINES
        CsvExportTable.STOCK_MOVEMENTS -> STOCK_MOVEMENTS
        CsvExportTable.EXPENSES -> EXPENSES
        CsvExportTable.PRICE_HISTORY -> PRICE_HISTORY
        CsvExportTable.TAX_RATES -> TAX_RATES
        CsvExportTable.PAYMENT_METHODS -> PAYMENT_METHODS
        CsvExportTable.SALE_PAYMENTS -> SALE_PAYMENTS
        CsvExportTable.CUSTOMERS -> CUSTOMERS
        CsvExportTable.CUSTOMER_LEDGER -> CUSTOMER_LEDGER
        CsvExportTable.LOYALTY_LEDGER -> LOYALTY_LEDGER
        CsvExportTable.MONEY_ACCOUNTS -> MONEY_ACCOUNTS
        CsvExportTable.MONEY_MOVEMENTS -> MONEY_MOVEMENTS
        CsvExportTable.SUPPLIER_LEDGER -> SUPPLIER_LEDGER
        CsvExportTable.CASH_SESSIONS -> CASH_SESSIONS
        CsvExportTable.CASH_SESSION_COUNTS -> CASH_SESSION_COUNTS
        CsvExportTable.SALE_RETURNS -> SALE_RETURNS
        CsvExportTable.SALE_RETURN_LINES -> SALE_RETURN_LINES
        CsvExportTable.PURCHASE_RETURNS -> PURCHASE_RETURNS
        CsvExportTable.PURCHASE_RETURN_LINES -> PURCHASE_RETURN_LINES
        CsvExportTable.PRICE_LISTS -> PRICE_LISTS
        CsvExportTable.PRODUCT_PRICES -> PRODUCT_PRICES
        CsvExportTable.FX_RATES -> FX_RATES
        CsvExportTable.PRICE_INDEX -> PRICE_INDEX
        CsvExportTable.UNITS -> UNITS
        CsvExportTable.PRODUCT_UNITS -> PRODUCT_UNITS
        CsvExportTable.PRODUCT_COMPONENTS -> PRODUCT_COMPONENTS
        CsvExportTable.PRODUCT_MODIFIERS -> PRODUCT_MODIFIERS
        CsvExportTable.CUSTOM_FIELDS -> CUSTOM_FIELDS
        CsvExportTable.PRODUCT_CUSTOM_VALUES -> PRODUCT_CUSTOM_VALUES
        CsvExportTable.TAGS -> TAGS
        CsvExportTable.PRODUCT_TAGS -> PRODUCT_TAGS
        CsvExportTable.MEDIA_ASSETS -> MEDIA_ASSETS
        CsvExportTable.SALE_LINE_COMPONENTS -> SALE_LINE_COMPONENTS
        CsvExportTable.LOCATIONS -> LOCATIONS
        CsvExportTable.STOCK_BALANCES -> STOCK_BALANCES
        CsvExportTable.STOCK_LOTS -> STOCK_LOTS
        CsvExportTable.STOCK_TRANSFERS -> STOCK_TRANSFERS
        CsvExportTable.STOCK_TRANSFER_LINES -> STOCK_TRANSFER_LINES
        CsvExportTable.PRODUCTION_BATCHES -> PRODUCTION_BATCHES
        CsvExportTable.PRODUCTION_BATCH_LINES -> PRODUCTION_BATCH_LINES
        CsvExportTable.COUNT_SESSIONS -> COUNT_SESSIONS
        CsvExportTable.COUNT_SESSION_LINES -> COUNT_SESSION_LINES
        CsvExportTable.QUOTES -> QUOTES
        CsvExportTable.QUOTE_LINES -> QUOTE_LINES
        CsvExportTable.ORDERS -> ORDERS
        CsvExportTable.ORDER_LINES -> ORDER_LINES
        CsvExportTable.SALE_FISCAL_MARKS -> SALE_FISCAL_MARKS
        CsvExportTable.DOCUMENT_ATTACHMENTS -> DOCUMENT_ATTACHMENTS
        CsvExportTable.BUSINESS_GOALS -> BUSINESS_GOALS
        CsvExportTable.RECURRING_EXPENSES -> RECURRING_EXPENSES
    }

    private val PRODUCTS =
        CsvTableQuery(
            """
            SELECT p.name, c.name, s.name, u.system_key, u.name, p.stock_mode, p.barcode,
                p.sale_price, p.average_cost, p.initial_cost, p.stock_value, p.on_hand_milli,
                p.min_stock_milli, p.archived_at, p.usage
            FROM products p
            LEFT JOIN categories c ON c.id = p.category_id
            LEFT JOIN suppliers s ON s.id = p.default_supplier_id
            LEFT JOIN units u ON u.id = p.base_unit_id
            ORDER BY p.id
            """.trimIndent(),
        ) { row ->
            listOf(
                row.text(),
                row.text(),
                row.text(),
                row.seed(SeedTable.UNITS),
                row.text(),
                row.code(),
                row.amount(),
                row.amount(),
                row.amount(),
                row.amount(),
                row.quantity(),
                row.quantity(),
                row.moment(),
                row.code(),
            )
        }

    private val SUPPLIERS =
        CsvTableQuery(
            """
            SELECT name, phone, email, notes, balance, payment_terms_days, archived_at
            FROM suppliers
            ORDER BY id
            """.trimIndent(),
        ) { row ->
            listOf(
                row.text(),
                row.text(),
                row.text(),
                row.text(),
                row.amount(),
                row.count(),
                row.moment(),
            )
        }

    private val SALES =
        CsvTableQuery(
            """
            SELECT s.id, s.business_date, s.occurred_at, s.created_at, cu.name, pl.system_key,
                pl.name, s.net_total, s.tax_total, s.order_adjustment, s.tip_amount,
                s.cash_rounding_amount, s.total, s.amount_due, s.total_cost, s.status, s.voided_at,
                s.void_reason, s.void_note
            FROM sales s
            LEFT JOIN customers cu ON cu.id = s.customer_id
            LEFT JOIN price_lists pl ON pl.id = s.price_list_id
            ORDER BY s.id
            """.trimIndent(),
            rangeFilter = "s.business_date BETWEEN ?1 AND ?2",
        ) { row ->
            val id = row.count()
            val date = row.day()
            val time = row.timeOfDay()
            val createdAt = row.moment()
            val customer = row.text()
            val priceList = row.seed(SeedTable.PRICE_LISTS)
            val net = row.amountValue()
            val head = listOf(id, date, time, createdAt, customer, priceList, CsvCell.Amount(net))
            val amounts = List(AMOUNTS_BEFORE_COST) { row.amount() }
            val cost = row.amountValue()
            head + amounts +
                listOf(
                    CsvCell.Amount(cost),
                    // Ganancia de la venta: neto sin impuesto menos su costo.
                    CsvCell.Amount(net - cost),
                    row.text(),
                    row.moment(),
                    row.text(),
                    row.text(),
                )
        }

    /** Impuesto, ajuste, propina, redondeo, total y por cobrar, entre el neto y el costo. */
    private const val AMOUNTS_BEFORE_COST = 6

    private val SALE_LINES =
        CsvTableQuery(
            """
            SELECT l.sale_id, l.product_name_snapshot, l.quantity_milli, bu.system_key, bu.name,
                l.entered_quantity_milli, u.system_key, u.name, l.unit_price, l.unit_cost,
                l.line_discount, l.order_adjustment_share, l.net_amount, l.tax_rate_bp,
                l.tax_amount, l.line_total, l.line_cost, l.stock_effect
            FROM sale_lines l
            LEFT JOIN products p ON p.id = l.product_id
            LEFT JOIN units bu ON bu.id = p.base_unit_id
            LEFT JOIN units u ON u.id = l.unit_id
            ORDER BY l.id
            """.trimIndent(),
            rangeFilter =
                "l.sale_id IN (SELECT id FROM sales WHERE business_date BETWEEN ?1 AND ?2)",
        ) { row ->
            listOf(
                row.count(),
                row.text(),
                row.quantity(),
                row.seed(SeedTable.UNITS),
                row.quantity(),
                row.seed(SeedTable.UNITS),
                row.amount(),
                row.amount(),
                row.amount(),
                row.amount(),
                row.amount(),
                row.percent(),
                row.amount(),
                row.amount(),
                row.amount(),
                row.text(),
            )
        }

    private val PURCHASES =
        CsvTableQuery(
            """
            SELECT pu.id, pu.business_date, pu.created_at, su.name, pu.invoice_reference,
                pu.net_total, pu.tax_total, pu.cost_total, pu.total, pu.amount_paid, pu.due_date,
                pu.status, pu.voided_at, pu.void_reason, pu.void_note
            FROM purchases pu
            LEFT JOIN suppliers su ON su.id = pu.supplier_id
            ORDER BY pu.id
            """.trimIndent(),
            rangeFilter = "pu.business_date BETWEEN ?1 AND ?2",
        ) { row ->
            listOf(
                row.count(),
                row.day(),
                row.moment(),
                row.text(),
                row.text(),
                row.amount(),
                row.amount(),
                row.amount(),
                row.amount(),
                row.amount(),
                row.day(),
                row.text(),
                row.moment(),
                row.text(),
                row.text(),
            )
        }

    private val PURCHASE_LINES =
        CsvTableQuery(
            """
            SELECT l.purchase_id, p.name, l.quantity_milli, bu.system_key, bu.name,
                l.entered_quantity_milli, u.system_key, u.name, l.unit_cost, l.tax_rate_bp,
                l.tax_amount, l.cost_amount, l.line_total, lo.lot_code
            FROM purchase_lines l
            LEFT JOIN products p ON p.id = l.product_id
            LEFT JOIN units bu ON bu.id = p.base_unit_id
            LEFT JOIN units u ON u.id = l.unit_id
            LEFT JOIN stock_lots lo ON lo.id = l.lot_id
            ORDER BY l.id
            """.trimIndent(),
            rangeFilter =
                "l.purchase_id IN " +
                    "(SELECT id FROM purchases WHERE business_date BETWEEN ?1 AND ?2)",
        ) { row ->
            listOf(
                row.count(),
                row.text(),
                row.quantity(),
                row.seed(SeedTable.UNITS),
                row.quantity(),
                row.seed(SeedTable.UNITS),
                row.amount(),
                row.percent(),
                row.amount(),
                row.amount(),
                row.amount(),
                row.text(),
            )
        }

    private val STOCK_MOVEMENTS =
        CsvTableQuery(
            """
            SELECT m.id, m.business_date, m.occurred_at, m.created_at, p.name, lc.system_key,
                lc.name, lo.lot_code, m.type, m.reason, m.quantity_milli, m.unit_cost, m.total_cost,
                m.average_cost_after, m.stock_value_after, m.reverses_movement_id, m.note
            FROM stock_movements m
            LEFT JOIN products p ON p.id = m.product_id
            LEFT JOIN locations lc ON lc.id = m.location_id
            LEFT JOIN stock_lots lo ON lo.id = m.lot_id
            ORDER BY m.id
            """.trimIndent(),
            rangeFilter = "m.business_date BETWEEN ?1 AND ?2",
        ) { row ->
            listOf(
                row.count(),
                row.day(),
                row.timeOfDay(),
                row.moment(),
                row.text(),
                row.seed(SeedTable.LOCATIONS),
                row.text(),
                row.text(),
                row.text(),
                row.quantity(),
                row.amount(),
                row.amount(),
                row.amount(),
                row.amount(),
                row.count(),
                row.text(),
            )
        }

    private val EXPENSES =
        CsvTableQuery(
            """
            SELECT e.id, e.business_date, e.created_at, ec.system_key, ec.name, e.description,
                e.amount, ma.system_key, ma.name, e.status, e.voided_at, e.void_reason, e.void_note
            FROM expenses e
            LEFT JOIN expense_categories ec ON ec.id = e.expense_category_id
            LEFT JOIN money_accounts ma ON ma.id = e.money_account_id
            ORDER BY e.id
            """.trimIndent(),
            rangeFilter = "e.business_date BETWEEN ?1 AND ?2",
        ) { row ->
            listOf(
                row.count(),
                row.day(),
                row.moment(),
                row.seed(SeedTable.EXPENSE_CATEGORIES),
                row.text(),
                row.amount(),
                row.seed(SeedTable.MONEY_ACCOUNTS),
                row.text(),
                row.moment(),
                row.text(),
                row.text(),
            )
        }

    private val PRICE_HISTORY =
        CsvTableQuery(
            """
            SELECT h.business_date, h.occurred_at, p.name, pl.system_key, pl.name, h.old_price,
                h.new_price, h.source
            FROM product_price_history h
            LEFT JOIN products p ON p.id = h.product_id
            LEFT JOIN price_lists pl ON pl.id = h.price_list_id
            ORDER BY h.id
            """.trimIndent(),
            rangeFilter = "h.business_date BETWEEN ?1 AND ?2",
        ) { row ->
            listOf(
                row.day(),
                row.timeOfDay(),
                row.text(),
                row.seed(SeedTable.PRICE_LISTS),
                row.amount(),
                row.amount(),
                row.text(),
            )
        }

    private val TAX_RATES =
        CsvTableQuery(
            """
            SELECT id, name, rate_bp, archived_at, created_at, updated_at
            FROM tax_rates
            ORDER BY id
            """.trimIndent(),
        ) { row ->
            listOf(
                row.count(),
                row.text(),
                row.percent(),
                row.moment(),
                row.moment(),
                row.moment(),
            )
        }

    private val PAYMENT_METHODS =
        CsvTableQuery(
            """
            SELECT pm.id, pm.system_key, pm.system_key, pm.name, pm.kind, pm.fee_bp, pm.fee_fixed,
                pm.settlement_days, pm.price_adjustment_bp, ma.system_key, ma.name,
                pm.instructions_text, pm.instructions_media_id, pm.sort_order, pm.archived_at,
                pm.created_at, pm.updated_at
            FROM payment_methods pm
            LEFT JOIN money_accounts ma ON ma.id = pm.money_account_id
            ORDER BY pm.id
            """.trimIndent(),
        ) { row ->
            listOf(
                row.count(),
                row.text(),
                row.seed(SeedTable.PAYMENT_METHODS),
                row.text(),
                row.percent(),
                row.amount(),
                row.count(),
                row.percent(),
                row.seed(SeedTable.MONEY_ACCOUNTS),
                row.text(),
                row.count(),
                row.count(),
                row.moment(),
                row.moment(),
                row.moment(),
            )
        }

    private val SALE_PAYMENTS =
        CsvTableQuery(
            """
            SELECT sp.id, sp.sale_id, sp.method_name_snapshot, sp.method_kind_snapshot, sp.amount,
                sp.fee_amount, sp.settlement_date, ma.system_key, ma.name, sp.tendered_amount,
                sp.change_amount, sp.tender_currency_code, sp.tender_currency_decimals,
                sp.tender_amount, sp.tender_currency_decimals, sp.fx_rate_micro, sp.created_at
            FROM sale_payments sp
            LEFT JOIN money_accounts ma ON ma.id = sp.money_account_id
            ORDER BY sp.id
            """.trimIndent(),
            rangeFilter = "sp.sale_id IN ($SALES_IN_RANGE)",
        ) { row ->
            listOf(
                row.count(),
                row.count(),
                row.text(),
                row.text(),
                row.amount(),
                row.amount(),
                row.day(),
                row.seed(SeedTable.MONEY_ACCOUNTS),
                row.amount(),
                row.amount(),
                row.text(),
                row.count(),
                // Lo entregado en otra moneda, con los decimales de esa moneda.
                row.scaled(),
                row.micro(),
                row.moment(),
            )
        }

    private val CUSTOMERS =
        CsvTableQuery(
            """
            SELECT c.id, c.name, c.phone, c.email, c.notes, pl.system_key, pl.name, c.balance,
                c.points_balance, c.anonymized_at, c.archived_at, c.created_at, c.updated_at
            FROM customers c
            LEFT JOIN price_lists pl ON pl.id = c.price_list_id
            ORDER BY c.id
            """.trimIndent(),
        ) { row ->
            listOf(
                row.count(),
                row.text(),
                row.text(),
                row.text(),
                row.text(),
                row.seed(SeedTable.PRICE_LISTS),
                row.amount(),
                row.count(),
                row.moment(),
                row.moment(),
                row.moment(),
                row.moment(),
            )
        }

    private val CUSTOMER_LEDGER =
        CsvTableQuery(
            """
            SELECT e.id, c.name, e.type, e.amount, e.balance_after, e.sale_id, e.sale_return_id,
                e.reverses_entry_id, e.due_date, e.note, e.occurred_at, e.business_date,
                e.created_at
            FROM customer_ledger_entries e
            LEFT JOIN customers c ON c.id = e.customer_id
            ORDER BY e.id
            """.trimIndent(),
            rangeFilter = "e.business_date BETWEEN ?1 AND ?2",
        ) { row ->
            listOf(
                row.count(),
                row.text(),
                row.text(),
                row.amount(),
                row.amount(),
                row.count(),
                row.count(),
                row.count(),
                row.day(),
                row.text(),
                row.timeOfDay(),
                row.day(),
                row.moment(),
            )
        }

    private val LOYALTY_LEDGER =
        CsvTableQuery(
            """
            SELECT e.id, c.name, e.type, e.points, e.balance_after, e.sale_id, e.reverses_entry_id,
                e.note, e.occurred_at, e.business_date, e.created_at
            FROM loyalty_ledger_entries e
            LEFT JOIN customers c ON c.id = e.customer_id
            ORDER BY e.id
            """.trimIndent(),
            rangeFilter = "e.business_date BETWEEN ?1 AND ?2",
        ) { row ->
            listOf(
                row.count(),
                row.text(),
                row.text(),
                row.count(),
                row.count(),
                row.count(),
                row.count(),
                row.text(),
                row.timeOfDay(),
                row.day(),
                row.moment(),
            )
        }

    private val MONEY_ACCOUNTS =
        CsvTableQuery(
            """
            SELECT id, system_key, system_key, name, kind, currency_code, currency_decimals,
                balance, currency_decimals, archived_at, created_at, updated_at
            FROM money_accounts
            ORDER BY id
            """.trimIndent(),
        ) { row ->
            listOf(
                row.count(),
                row.text(),
                row.seed(SeedTable.MONEY_ACCOUNTS),
                row.text(),
                row.text(),
                row.count(),
                // El saldo va en la moneda de la cuenta, que puede no ser la del negocio.
                row.scaled(),
                row.moment(),
                row.moment(),
                row.moment(),
            )
        }

    private val MONEY_MOVEMENTS =
        CsvTableQuery(
            """
            SELECT m.id, ma.system_key, ma.name, m.type, m.amount, ma.currency_decimals,
                m.balance_after, ma.currency_decimals, m.available_date, m.transfer_group_id,
                m.fx_rate_micro, m.sale_payment_id, m.customer_ledger_entry_id,
                m.supplier_ledger_entry_id, m.expense_id, m.purchase_id, m.sale_return_id,
                m.purchase_return_id, m.cash_session_id, m.reverses_movement_id, m.note,
                m.occurred_at, m.business_date, m.created_at
            FROM money_movements m
            LEFT JOIN money_accounts ma ON ma.id = m.money_account_id
            ORDER BY m.id
            """.trimIndent(),
            rangeFilter = "m.business_date BETWEEN ?1 AND ?2",
        ) { row ->
            val head =
                listOf(
                    row.count(),
                    row.seed(SeedTable.MONEY_ACCOUNTS),
                    row.text(),
                    // Monto y saldo, en la moneda de su cuenta.
                    row.scaled(),
                    row.scaled(),
                    row.day(),
                    row.code(),
                    row.micro(),
                )
            // Los documentos que lo originaron y el movimiento que compensa, por su número.
            val links = List(MONEY_MOVEMENT_LINKS) { row.count() }
            head + links + listOf(row.text(), row.timeOfDay(), row.day(), row.moment())
        }

    /** Pago de venta, asientos, gasto, compra, devoluciones, sesión de caja y compensado. */
    private const val MONEY_MOVEMENT_LINKS = 9

    private val SUPPLIER_LEDGER =
        CsvTableQuery(
            """
            SELECT e.id, s.name, e.type, e.amount, e.balance_after, e.purchase_id,
                e.purchase_return_id, e.reverses_entry_id, e.due_date, e.note, e.occurred_at,
                e.business_date, e.created_at
            FROM supplier_ledger_entries e
            LEFT JOIN suppliers s ON s.id = e.supplier_id
            ORDER BY e.id
            """.trimIndent(),
            rangeFilter = "e.business_date BETWEEN ?1 AND ?2",
        ) { row ->
            listOf(
                row.count(),
                row.text(),
                row.text(),
                row.amount(),
                row.amount(),
                row.count(),
                row.count(),
                row.count(),
                row.day(),
                row.text(),
                row.timeOfDay(),
                row.day(),
                row.moment(),
            )
        }

    private val CASH_SESSIONS =
        CsvTableQuery(
            """
            SELECT cs.id, ma.system_key, ma.name, cs.status, cs.opening_amount,
                ma.currency_decimals, cs.expected_amount, ma.currency_decimals, cs.counted_amount,
                ma.currency_decimals, cs.difference_amount, ma.currency_decimals, cs.opened_at,
                cs.closed_at, cs.business_date, cs.note
            FROM cash_sessions cs
            LEFT JOIN money_accounts ma ON ma.id = cs.money_account_id
            ORDER BY cs.id
            """.trimIndent(),
            rangeFilter = "cs.business_date BETWEEN ?1 AND ?2",
        ) { row ->
            listOf(
                row.count(),
                row.seed(SeedTable.MONEY_ACCOUNTS),
                row.text(),
                // Apertura, esperado, contado y diferencia, en la moneda de la cuenta.
                row.scaled(),
                row.scaled(),
                row.scaled(),
                row.scaled(),
                row.moment(),
                row.moment(),
                row.day(),
                row.text(),
            )
        }

    private val CASH_SESSION_COUNTS =
        CsvTableQuery(
            """
            SELECT c.id, c.cash_session_id, c.phase, d.value, c.count
            FROM cash_session_counts c
            LEFT JOIN cash_denominations d ON d.id = c.denomination_id
            ORDER BY c.id
            """.trimIndent(),
            rangeFilter =
                "c.cash_session_id IN " +
                    "(SELECT id FROM cash_sessions WHERE business_date BETWEEN ?1 AND ?2)",
        ) { row ->
            listOf(row.count(), row.count(), row.text(), row.amount(), row.count())
        }

    private val SALE_RETURNS =
        CsvTableQuery(
            """
            SELECT r.id, r.sale_id, r.resolution, r.net_refund, r.tax_refund, r.total_refund,
                r.cost_returned, ma.system_key, ma.name, r.replacement_sale_id, r.note,
                r.occurred_at, r.business_date, r.created_at, r.status, r.voided_at, r.void_reason,
                r.void_note
            FROM sale_returns r
            LEFT JOIN money_accounts ma ON ma.id = r.money_account_id
            ORDER BY r.id
            """.trimIndent(),
            rangeFilter = "r.business_date BETWEEN ?1 AND ?2",
        ) { row ->
            listOf(
                row.count(),
                row.count(),
                row.text(),
                row.amount(),
                row.amount(),
                row.amount(),
                row.amount(),
                row.seed(SeedTable.MONEY_ACCOUNTS),
                row.count(),
                row.text(),
                row.timeOfDay(),
                row.day(),
                row.moment(),
                row.text(),
                row.moment(),
                row.text(),
                row.text(),
            )
        }

    private val SALE_RETURN_LINES =
        CsvTableQuery(
            """
            SELECT l.id, l.sale_return_id, l.sale_line_id, l.quantity_milli, l.net_amount,
                l.tax_amount, l.line_total, l.line_cost, l.restock
            FROM sale_return_lines l
            ORDER BY l.id
            """.trimIndent(),
            rangeFilter =
                "l.sale_return_id IN " +
                    "(SELECT id FROM sale_returns WHERE business_date BETWEEN ?1 AND ?2)",
        ) { row ->
            listOf(
                row.count(),
                row.count(),
                row.count(),
                row.quantity(),
                row.amount(),
                row.amount(),
                row.amount(),
                row.amount(),
                row.count(),
            )
        }

    private val PURCHASE_RETURNS =
        CsvTableQuery(
            """
            SELECT r.id, r.purchase_id, s.name, r.resolution, r.total, ma.system_key, ma.name,
                r.note, r.occurred_at, r.business_date, r.created_at, r.status, r.voided_at,
                r.void_reason, r.void_note
            FROM purchase_returns r
            LEFT JOIN suppliers s ON s.id = r.supplier_id
            LEFT JOIN money_accounts ma ON ma.id = r.money_account_id
            ORDER BY r.id
            """.trimIndent(),
            rangeFilter = "r.business_date BETWEEN ?1 AND ?2",
        ) { row ->
            listOf(
                row.count(),
                row.count(),
                row.text(),
                row.text(),
                row.amount(),
                row.seed(SeedTable.MONEY_ACCOUNTS),
                row.text(),
                row.timeOfDay(),
                row.day(),
                row.moment(),
                row.text(),
                row.moment(),
                row.text(),
                row.text(),
            )
        }

    private val PURCHASE_RETURN_LINES =
        CsvTableQuery(
            """
            SELECT l.id, l.purchase_return_id, l.purchase_line_id, p.name, lo.lot_code,
                l.quantity_milli, l.unit_cost, l.line_total
            FROM purchase_return_lines l
            LEFT JOIN products p ON p.id = l.product_id
            LEFT JOIN stock_lots lo ON lo.id = l.lot_id
            ORDER BY l.id
            """.trimIndent(),
            rangeFilter =
                "l.purchase_return_id IN " +
                    "(SELECT id FROM purchase_returns WHERE business_date BETWEEN ?1 AND ?2)",
        ) { row ->
            listOf(
                row.count(),
                row.count(),
                row.count(),
                row.text(),
                row.code(),
                row.quantity(),
                row.amount(),
                row.amount(),
            )
        }

    private val PRICE_LISTS =
        CsvTableQuery(
            """
            SELECT pl.id, pl.system_key, pl.system_key, pl.name, pl.kind, b.system_key, b.name,
                pl.adjustment_bp, pl.archived_at, pl.created_at, pl.updated_at
            FROM price_lists pl
            LEFT JOIN price_lists b ON b.id = pl.base_price_list_id
            ORDER BY pl.id
            """.trimIndent(),
        ) { row ->
            listOf(
                row.count(),
                row.text(),
                row.seed(SeedTable.PRICE_LISTS),
                row.text(),
                row.seed(SeedTable.PRICE_LISTS),
                row.percent(),
                row.moment(),
                row.moment(),
                row.moment(),
            )
        }

    private val PRODUCT_PRICES =
        CsvTableQuery(
            """
            SELECT pp.id, pl.system_key, pl.name, p.name, pp.price, pp.created_at, pp.updated_at
            FROM product_prices pp
            LEFT JOIN price_lists pl ON pl.id = pp.price_list_id
            LEFT JOIN products p ON p.id = pp.product_id
            ORDER BY pp.id
            """.trimIndent(),
        ) { row ->
            listOf(
                row.count(),
                row.seed(SeedTable.PRICE_LISTS),
                row.text(),
                row.amount(),
                row.moment(),
                row.moment(),
            )
        }

    private val FX_RATES =
        CsvTableQuery(
            """
            SELECT id, currency_code, rate_micro, note, occurred_at, business_date, created_at
            FROM fx_rates
            ORDER BY id
            """.trimIndent(),
            rangeFilter = "business_date BETWEEN ?1 AND ?2",
        ) { row ->
            listOf(
                row.count(),
                row.text(),
                row.micro(),
                row.text(),
                row.timeOfDay(),
                row.day(),
                row.moment(),
            )
        }

    private val PRICE_INDEX =
        CsvTableQuery(
            """
            SELECT id, month, change_bp, source_note, created_at, updated_at
            FROM price_index_entries
            ORDER BY id
            """.trimIndent(),
        ) { row ->
            listOf(
                row.count(),
                row.text(),
                row.percent(),
                row.text(),
                row.moment(),
                row.moment(),
            )
        }

    private val UNITS =
        CsvTableQuery(
            """
            SELECT id, system_key, system_key, name, symbol, kind, allows_fraction, archived_at,
                created_at, updated_at
            FROM units
            ORDER BY id
            """.trimIndent(),
        ) { row ->
            listOf(
                row.count(),
                row.text(),
                row.seed(SeedTable.UNITS),
                row.text(),
                row.text(),
                row.count(),
                row.moment(),
                row.moment(),
                row.moment(),
            )
        }

    private val PRODUCT_UNITS =
        CsvTableQuery(
            """
            SELECT pu.id, p.name, u.system_key, u.name, pu.factor_milli, pu.sale_price, pu.barcode,
                pu.for_sale, pu.for_purchase, pu.created_at, pu.updated_at
            FROM product_units pu
            LEFT JOIN products p ON p.id = pu.product_id
            LEFT JOIN units u ON u.id = pu.unit_id
            ORDER BY pu.id
            """.trimIndent(),
        ) { row ->
            listOf(
                row.count(),
                row.text(),
                row.seed(SeedTable.UNITS),
                // La equivalencia se guarda en milésimas de la unidad base, como una cantidad.
                row.quantity(),
                row.amount(),
                row.code(),
                row.count(),
                row.count(),
                row.moment(),
                row.moment(),
            )
        }

    private val PRODUCT_COMPONENTS =
        CsvTableQuery(
            """
            SELECT pc.id, pp.name, cp.name, pc.quantity_milli, pc.created_at, pc.updated_at
            FROM product_components pc
            LEFT JOIN products pp ON pp.id = pc.parent_product_id
            LEFT JOIN products cp ON cp.id = pc.component_product_id
            ORDER BY pc.id
            """.trimIndent(),
        ) { row ->
            listOf(
                row.count(),
                row.text(),
                row.text(),
                row.quantity(),
                row.moment(),
                row.moment(),
            )
        }

    private val PRODUCT_MODIFIERS =
        CsvTableQuery(
            """
            SELECT pm.id, p.name, m.name, pm.group_name, pm.sort_order, pm.created_at,
                pm.updated_at
            FROM product_modifiers pm
            LEFT JOIN products p ON p.id = pm.product_id
            LEFT JOIN products m ON m.id = pm.modifier_product_id
            ORDER BY pm.id
            """.trimIndent(),
        ) { row ->
            listOf(
                row.count(),
                row.text(),
                row.text(),
                row.text(),
                row.count(),
                row.moment(),
                row.moment(),
            )
        }

    private val CUSTOM_FIELDS =
        CsvTableQuery(
            """
            SELECT id, name, field_type, sort_order, archived_at, created_at, updated_at
            FROM custom_field_definitions
            ORDER BY id
            """.trimIndent(),
        ) { row ->
            listOf(
                row.count(),
                row.text(),
                row.text(),
                row.count(),
                row.moment(),
                row.moment(),
                row.moment(),
            )
        }

    private val PRODUCT_CUSTOM_VALUES =
        CsvTableQuery(
            """
            SELECT v.id, p.name, f.name, v.value_text, v.updated_at
            FROM product_custom_values v
            LEFT JOIN products p ON p.id = v.product_id
            LEFT JOIN custom_field_definitions f ON f.id = v.field_id
            ORDER BY v.id
            """.trimIndent(),
        ) { row ->
            listOf(row.count(), row.text(), row.text(), row.text(), row.moment())
        }

    private val TAGS =
        CsvTableQuery(
            """
            SELECT id, name, created_at, updated_at
            FROM tags
            ORDER BY id
            """.trimIndent(),
        ) { row ->
            listOf(row.count(), row.text(), row.moment(), row.moment())
        }

    private val PRODUCT_TAGS =
        CsvTableQuery(
            """
            SELECT pt.id, p.name, t.name
            FROM product_tags pt
            LEFT JOIN products p ON p.id = pt.product_id
            LEFT JOIN tags t ON t.id = pt.tag_id
            ORDER BY pt.id
            """.trimIndent(),
        ) { row ->
            listOf(row.count(), row.text(), row.text())
        }

    private val MEDIA_ASSETS =
        CsvTableQuery(
            """
            SELECT id, sha256, kind, mime_type, width_px, height_px, size_bytes, created_at,
                orphaned_at
            FROM media_assets
            ORDER BY id
            """.trimIndent(),
        ) { row ->
            listOf(
                row.count(),
                row.code(),
                row.text(),
                row.text(),
                row.count(),
                row.count(),
                row.count(),
                row.moment(),
                row.moment(),
            )
        }

    private val SALE_LINE_COMPONENTS =
        CsvTableQuery(
            """
            SELECT c.id, c.sale_line_id, p.name, c.quantity_milli, c.tracked, c.unit_cost,
                c.line_cost
            FROM sale_line_components c
            LEFT JOIN products p ON p.id = c.component_product_id
            ORDER BY c.id
            """.trimIndent(),
            rangeFilter =
                "c.sale_line_id IN (SELECT id FROM sale_lines WHERE sale_id IN ($SALES_IN_RANGE))",
        ) { row ->
            listOf(
                row.count(),
                row.count(),
                row.text(),
                row.quantity(),
                row.count(),
                row.amount(),
                row.amount(),
            )
        }

    private val LOCATIONS =
        CsvTableQuery(
            """
            SELECT id, system_key, system_key, name, kind, archived_at, created_at, updated_at
            FROM locations
            ORDER BY id
            """.trimIndent(),
        ) { row ->
            listOf(
                row.count(),
                row.text(),
                row.seed(SeedTable.LOCATIONS),
                row.text(),
                row.moment(),
                row.moment(),
                row.moment(),
            )
        }

    private val STOCK_BALANCES =
        CsvTableQuery(
            // Sin lote, `lot_key` es 0 y no encuentra ninguno: la celda queda vacía.
            """
            SELECT b.id, p.name, lc.system_key, lc.name, lo.lot_code, b.on_hand_milli
            FROM stock_balances b
            LEFT JOIN products p ON p.id = b.product_id
            LEFT JOIN locations lc ON lc.id = b.location_id
            LEFT JOIN stock_lots lo ON lo.id = b.lot_key
            ORDER BY b.id
            """.trimIndent(),
        ) { row ->
            listOf(
                row.count(),
                row.text(),
                row.seed(SeedTable.LOCATIONS),
                row.code(),
                row.quantity(),
            )
        }

    private val STOCK_LOTS =
        CsvTableQuery(
            """
            SELECT lo.id, p.name, lo.lot_code, lo.is_unassigned, lo.expiry_date,
                lo.purchase_line_id, lo.expiry_notified_at, lo.created_at, lo.closed_at
            FROM stock_lots lo
            LEFT JOIN products p ON p.id = lo.product_id
            ORDER BY lo.id
            """.trimIndent(),
        ) { row ->
            listOf(
                row.count(),
                row.text(),
                row.code(),
                row.count(),
                row.day(),
                row.count(),
                row.moment(),
                row.moment(),
                row.moment(),
            )
        }

    private val STOCK_TRANSFERS =
        CsvTableQuery(
            """
            SELECT t.id, fl.system_key, fl.name, tl.system_key, tl.name, t.note, t.occurred_at,
                t.business_date, t.created_at, t.status, t.voided_at, t.void_reason, t.void_note
            FROM stock_transfers t
            LEFT JOIN locations fl ON fl.id = t.from_location_id
            LEFT JOIN locations tl ON tl.id = t.to_location_id
            ORDER BY t.id
            """.trimIndent(),
            rangeFilter = "t.business_date BETWEEN ?1 AND ?2",
        ) { row ->
            listOf(
                row.count(),
                row.seed(SeedTable.LOCATIONS),
                row.seed(SeedTable.LOCATIONS),
                row.text(),
                row.timeOfDay(),
                row.day(),
                row.moment(),
                row.text(),
                row.moment(),
                row.text(),
                row.text(),
            )
        }

    private val STOCK_TRANSFER_LINES =
        CsvTableQuery(
            """
            SELECT l.id, l.transfer_id, p.name, lo.lot_code, l.quantity_milli
            FROM stock_transfer_lines l
            LEFT JOIN products p ON p.id = l.product_id
            LEFT JOIN stock_lots lo ON lo.id = l.lot_id
            ORDER BY l.id
            """.trimIndent(),
            rangeFilter =
                "l.transfer_id IN " +
                    "(SELECT id FROM stock_transfers WHERE business_date BETWEEN ?1 AND ?2)",
        ) { row ->
            listOf(row.count(), row.count(), row.text(), row.code(), row.quantity())
        }

    private val PRODUCTION_BATCHES =
        CsvTableQuery(
            """
            SELECT b.id, p.name, lc.system_key, lc.name, b.quantity_milli, b.total_cost, b.note,
                b.occurred_at, b.business_date, b.created_at, b.status, b.voided_at, b.void_reason,
                b.void_note
            FROM production_batches b
            LEFT JOIN products p ON p.id = b.product_id
            LEFT JOIN locations lc ON lc.id = b.location_id
            ORDER BY b.id
            """.trimIndent(),
            rangeFilter = "b.business_date BETWEEN ?1 AND ?2",
        ) { row ->
            listOf(
                row.count(),
                row.text(),
                row.seed(SeedTable.LOCATIONS),
                row.quantity(),
                row.amount(),
                row.text(),
                row.timeOfDay(),
                row.day(),
                row.moment(),
                row.text(),
                row.moment(),
                row.text(),
                row.text(),
            )
        }

    private val PRODUCTION_BATCH_LINES =
        CsvTableQuery(
            """
            SELECT l.id, l.production_batch_id, p.name, lo.lot_code, l.quantity_milli, l.unit_cost,
                l.line_cost
            FROM production_batch_lines l
            LEFT JOIN products p ON p.id = l.component_product_id
            LEFT JOIN stock_lots lo ON lo.id = l.lot_id
            ORDER BY l.id
            """.trimIndent(),
            rangeFilter =
                "l.production_batch_id IN " +
                    "(SELECT id FROM production_batches WHERE business_date BETWEEN ?1 AND ?2)",
        ) { row ->
            listOf(
                row.count(),
                row.count(),
                row.text(),
                row.code(),
                row.quantity(),
                row.amount(),
                row.amount(),
            )
        }

    private val COUNT_SESSIONS =
        CsvTableQuery(
            """
            SELECT cs.id, cs.status, cs.is_blind, lc.system_key, lc.name, c.name, cs.note,
                cs.started_at, cs.finished_at, cs.business_date
            FROM count_sessions cs
            LEFT JOIN locations lc ON lc.id = cs.location_id
            LEFT JOIN categories c ON c.id = cs.category_id
            ORDER BY cs.id
            """.trimIndent(),
            rangeFilter = "cs.business_date BETWEEN ?1 AND ?2",
        ) { row ->
            listOf(
                row.count(),
                row.text(),
                row.count(),
                row.seed(SeedTable.LOCATIONS),
                row.text(),
                row.text(),
                row.moment(),
                row.moment(),
                row.day(),
            )
        }

    private val COUNT_SESSION_LINES =
        CsvTableQuery(
            """
            SELECT l.id, l.count_session_id, p.name, lo.lot_code, l.expected_milli, l.counted_milli
            FROM count_session_lines l
            LEFT JOIN products p ON p.id = l.product_id
            LEFT JOIN stock_lots lo ON lo.id = l.lot_id
            ORDER BY l.id
            """.trimIndent(),
            rangeFilter =
                "l.count_session_id IN " +
                    "(SELECT id FROM count_sessions WHERE business_date BETWEEN ?1 AND ?2)",
        ) { row ->
            listOf(
                row.count(),
                row.count(),
                row.text(),
                row.code(),
                row.quantity(),
                row.quantity(),
            )
        }

    private val QUOTES =
        CsvTableQuery(
            """
            SELECT q.id, c.name, q.status, q.valid_until, q.net_total, q.tax_total, q.total,
                q.converted_sale_id, q.note, q.occurred_at, q.business_date, q.created_at,
                q.updated_at
            FROM quotes q
            LEFT JOIN customers c ON c.id = q.customer_id
            ORDER BY q.id
            """.trimIndent(),
            rangeFilter = "q.business_date BETWEEN ?1 AND ?2",
        ) { row ->
            listOf(
                row.count(),
                row.text(),
                row.text(),
                row.day(),
                row.amount(),
                row.amount(),
                row.amount(),
                row.count(),
                row.text(),
                row.timeOfDay(),
                row.day(),
                row.moment(),
                row.moment(),
            )
        }

    private val QUOTE_LINES =
        documentLines(
            table = "quote_lines",
            parentColumn = "quote_id",
            parentRange = "SELECT id FROM quotes WHERE business_date BETWEEN ?1 AND ?2",
        )

    private val ORDERS =
        CsvTableQuery(
            """
            SELECT o.id, c.name, o.status, o.delivery_address, o.delivery_note, o.shipping_amount,
                o.due_date, o.delivered_sale_id, o.total, o.occurred_at, o.business_date,
                o.created_at, o.updated_at
            FROM orders o
            LEFT JOIN customers c ON c.id = o.customer_id
            ORDER BY o.id
            """.trimIndent(),
            rangeFilter = "o.business_date BETWEEN ?1 AND ?2",
        ) { row ->
            listOf(
                row.count(),
                row.text(),
                row.text(),
                row.text(),
                row.text(),
                row.amount(),
                row.day(),
                row.count(),
                row.amount(),
                row.timeOfDay(),
                row.day(),
                row.moment(),
                row.moment(),
            )
        }

    private val ORDER_LINES =
        documentLines(
            table = "order_lines",
            parentColumn = "order_id",
            parentRange = "SELECT id FROM orders WHERE business_date BETWEEN ?1 AND ?2",
        )

    /**
     * Las líneas de una cotización o de un pedido, que tienen las mismas columnas: el producto por
     * su nombre de hoy y, al final, el nombre con que se anotó.
     */
    private fun documentLines(
        table: String,
        parentColumn: String,
        parentRange: String,
    ): CsvTableQuery = CsvTableQuery(
        """
        SELECT l.id, l.$parentColumn, p.name, u.system_key, u.name, l.unit_factor_milli,
            l.entered_quantity_milli, l.quantity_milli, l.unit_price, l.line_discount,
            l.net_amount, l.tax_rate_bp, l.tax_name_snapshot, l.tax_amount, l.line_total,
            l.product_name_snapshot
        FROM $table l
        LEFT JOIN products p ON p.id = l.product_id
        LEFT JOIN units u ON u.id = l.unit_id
        ORDER BY l.id
        """.trimIndent(),
        rangeFilter = "l.$parentColumn IN ($parentRange)",
    ) { row ->
        listOf(
            row.count(),
            row.count(),
            row.text(),
            row.seed(SeedTable.UNITS),
            row.quantity(),
            row.quantity(),
            row.quantity(),
            row.amount(),
            row.amount(),
            row.amount(),
            row.percent(),
            row.text(),
            row.amount(),
            row.amount(),
            row.text(),
        )
    }

    private val SALE_FISCAL_MARKS =
        CsvTableQuery(
            """
            SELECT id, sale_id, status, external_reference, recorded_at, updated_at
            FROM sale_fiscal_marks
            ORDER BY id
            """.trimIndent(),
            rangeFilter = "sale_id IN ($SALES_IN_RANGE)",
        ) { row ->
            listOf(
                row.count(),
                row.count(),
                row.text(),
                row.code(),
                row.moment(),
                row.moment(),
            )
        }

    private val DOCUMENT_ATTACHMENTS =
        CsvTableQuery(
            """
            SELECT id, media_asset_id, expense_id, purchase_id, caption, created_at
            FROM document_attachments
            ORDER BY id
            """.trimIndent(),
        ) { row ->
            listOf(
                row.count(),
                row.count(),
                row.count(),
                row.count(),
                row.text(),
                row.moment(),
            )
        }

    private val BUSINESS_GOALS =
        CsvTableQuery(
            """
            SELECT id, month, sales_goal, profit_goal, updated_at
            FROM business_goals
            ORDER BY id
            """.trimIndent(),
        ) { row ->
            listOf(row.count(), row.text(), row.amount(), row.amount(), row.moment())
        }

    private val RECURRING_EXPENSES =
        CsvTableQuery(
            """
            SELECT r.id, ec.system_key, ec.name, r.amount, r.description, r.recurrence,
                r.day_of_period, r.next_due_date, ma.system_key, ma.name, r.reminder_enabled,
                r.archived_at, r.created_at, r.updated_at
            FROM recurring_expenses r
            LEFT JOIN expense_categories ec ON ec.id = r.expense_category_id
            LEFT JOIN money_accounts ma ON ma.id = r.money_account_id
            ORDER BY r.id
            """.trimIndent(),
        ) { row ->
            listOf(
                row.count(),
                row.seed(SeedTable.EXPENSE_CATEGORIES),
                row.amount(),
                row.text(),
                row.text(),
                row.count(),
                row.day(),
                row.seed(SeedTable.MONEY_ACCOUNTS),
                row.count(),
                row.moment(),
                row.moment(),
                row.moment(),
            )
        }

    /** Las ventas de un rango, para limitar lo que cuelga de ellas. */
    private const val SALES_IN_RANGE = "SELECT id FROM sales WHERE business_date BETWEEN ?1 AND ?2"
}

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
 */
internal class CsvTableQuery(val sql: String, val cells: (CsvRow) -> List<CsvCell>)

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

    fun text(): CsvCell = next { CsvCell.Text(statement.getText(it)) }

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

/** Las consultas de cada archivo de [CsvExportTable]. */
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
    }

    private val PRODUCTS =
        CsvTableQuery(
            """
            SELECT p.name, c.name, s.name, u.system_key, u.name, p.stock_mode, p.barcode,
                p.sale_price, p.average_cost, p.initial_cost, p.stock_value, p.on_hand_milli,
                p.min_stock_milli, p.archived_at
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
                row.text(),
                row.amount(),
                row.amount(),
                row.amount(),
                row.amount(),
                row.quantity(),
                row.quantity(),
                row.moment(),
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
}

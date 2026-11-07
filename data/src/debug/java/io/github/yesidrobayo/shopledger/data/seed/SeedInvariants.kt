package io.github.yesidrobayo.shopledger.data.seed

import androidx.room3.PooledConnection
import androidx.room3.useReaderConnection
import io.github.yesidrobayo.shopledger.data.backup.InvariantQueries
import io.github.yesidrobayo.shopledger.data.backup.InvariantQuery
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.CostMovement
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.rule.CostReplayResult
import io.github.yesidrobayo.shopledger.domain.rule.WeightedAverageCost
import kotlin.time.Instant

/**
 * Las comprobaciones de invariantes sobre una base generada: las consultas del modelo de datos, que
 * deben devolver cero filas, y las que no se escriben en SQL, que corren aquí con las reglas de
 * `:domain`.
 *
 * Solo leen. Cada resultado es la cantidad de filas que rompen el invariante, por su nombre en el
 * modelo de datos: nunca un dato de las filas.
 */
internal object SeedInvariants {
    /**
     * Corre todas las comprobaciones y devuelve, por nombre, cuántas filas rompe cada una. Una base
     * sana da cero en todas.
     */
    suspend fun check(database: AppDatabase, zone: BusinessTimeZone): Map<String, Long> {
        val results = LinkedHashMap<String, Long>()
        val tracked =
            database.useReaderConnection { connection ->
                for (query in QUERIES) results[query.label] = connection.rowCount(query.sql)
                results[BUSINESS_DATE] = businessDateFailures(connection, zone)
                results[FOREIGN_KEYS] = connection.rowCount("PRAGMA foreign_key_check")
                results[INTEGRITY] =
                    connection.usePrepared("PRAGMA integrity_check") { statement ->
                        var problems = 0L
                        while (statement.step()) if (statement.getText(0) != "ok") problems++
                        problems
                    }
                trackedProducts(connection)
            }
        results[COST_REPLAY] = costReplayFailures(database, tracked)
        return results
    }

    private suspend fun PooledConnection.rowCount(sql: String): Long =
        usePrepared(sql) { statement ->
            var rows = 0L
            while (statement.step()) rows++
            rows
        }

    /** Lo que guarda cada producto con libro, para compararlo con su reproducción. */
    private suspend fun trackedProducts(connection: PooledConnection): List<StoredCost> =
        connection.usePrepared(
            "SELECT id, initial_cost, average_cost, stock_value, on_hand_milli FROM products " +
                "WHERE stock_mode = 'TRACKED' ORDER BY id",
        ) { statement ->
            buildList {
                while (statement.step()) {
                    add(
                        StoredCost(
                            id = statement.getLong(0),
                            initialCost = statement.getLong(1),
                            averageCost = statement.getLong(2),
                            stockValue = statement.getLong(3),
                            onHandMilli = statement.getLong(4),
                        ),
                    )
                }
            }
        }

    /**
     * El costo promedio, el valor del stock y el saldo de cada producto con libro, contra los que
     * da reproducir su libro con la regla del costo. Cuenta los productos que no coinciden.
     */
    private suspend fun costReplayFailures(
        database: AppDatabase,
        products: List<StoredCost>,
    ): Long {
        val movements = database.stockMovementDao()
        var failures = 0L
        for (product in products) {
            val ledger =
                movements.findByProduct(product.id).map { row ->
                    CostMovement(
                        id = row.id,
                        type = StockMovementType.valueOf(row.type),
                        quantity = QuantityMilli(row.quantityMilli),
                        unitCost = Money(row.unitCost),
                        totalCost = Money(row.totalCost),
                        reversesMovementId = row.reversesMovementId,
                    )
                }
            val replay = WeightedAverageCost.replayCostState(Money(product.initialCost), ledger)
            val matches =
                replay is CostReplayResult.Replayed &&
                    replay.state.averageCost.minor == product.averageCost &&
                    replay.state.stockValue.minor == product.stockValue &&
                    replay.state.onHand.milli == product.onHandMilli
            if (!matches) failures++
        }
        return failures
    }

    /**
     * La fecha de negocio de cada fila con fecha, contra la que da su instante en la zona del
     * negocio. La zona no cambia durante la generación, así que todas deben coincidir.
     */
    private suspend fun businessDateFailures(
        connection: PooledConnection,
        zone: BusinessTimeZone,
    ): Long {
        var failures = 0L
        for (table in DATED_TABLES) {
            connection.usePrepared("SELECT occurred_at, business_date FROM $table") { statement ->
                while (statement.step()) {
                    val expected =
                        BusinessDate.of(Instant.fromEpochMilliseconds(statement.getLong(0)), zone)
                    if (expected.toString() != statement.getText(1)) failures++
                }
            }
        }
        return failures
    }

    /** Del modelo de datos: el costo de cada producto sale de reproducir su libro. */
    const val COST_REPLAY = "I-03"

    /** Del modelo de datos: la fecha de negocio sale del instante en la zona del negocio. */
    const val BUSINESS_DATE = "I-08"

    const val FOREIGN_KEYS = "foreign_key_check"
    const val INTEGRITY = "integrity_check"

    private val DATED_TABLES =
        listOf(
            "sales",
            "purchases",
            "expenses",
            "stock_movements",
            "money_movements",
            "customer_ledger_entries",
            "supplier_ledger_entries",
            "loyalty_ledger_entries",
            "product_price_history",
        )

    /** Las consultas del modelo de datos, las mismas que corre el validador de copias. */
    val QUERIES: List<InvariantQuery> = InvariantQueries.MODEL
}

/** El costo que guarda un producto con libro. */
private class StoredCost(
    val id: Long,
    val initialCost: Long,
    val averageCost: Long,
    val stockValue: Long,
    val onHandMilli: Long,
)

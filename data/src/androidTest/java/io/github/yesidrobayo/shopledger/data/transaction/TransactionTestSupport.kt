package io.github.yesidrobayo.shopledger.data.transaction

import androidx.room3.RoomDatabase
import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import androidx.room3.withWriteTransaction
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.LedgerDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.catalog.ProductEntity
import io.github.yesidrobayo.shopledger.data.local.catalog.product
import io.github.yesidrobayo.shopledger.data.local.longValue
import io.github.yesidrobayo.shopledger.data.local.movement
import io.github.yesidrobayo.shopledger.data.local.openingBalance
import io.github.yesidrobayo.shopledger.data.local.sale
import io.github.yesidrobayo.shopledger.data.local.saleLine
import io.github.yesidrobayo.shopledger.data.local.texts
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before

// Apoyo de las pruebas de las transacciones. Todos los nombres, cantidades y montos son inventados.

/** El reloj de las pruebas: siempre el mismo instante. */
internal val FIXED_CLOCK = Clock { Instant.fromEpochMilliseconds(CREATED_AT) }

/** Una falla cualquiera del código, inventada por la prueba. */
internal class InjectedFailure : RuntimeException("Falla inyectada por la prueba")

/**
 * El contador real de la app, con la fila del estado del respaldo, envuelto para que la prueba vea
 * cada llamada: anota si llegó dentro de la transacción, y puede fallar a propósito antes de contar.
 */
internal class RecordingChangeCounter(private val database: AppDatabase) : ChangeCounter {
    private val real = BackupStateChangeCounter(database.backupStateDao())
    var failure: Throwable? = null
    val instants = mutableListOf<Long>()
    val insideTransaction = mutableListOf<Boolean>()

    /**
     * Las llamadas que terminaron sin fallar. Son de memoria y no se deshacen con la transacción:
     * lo que de verdad quedó contado lo dice `change_seq`, en [DatabaseSnapshot].
     */
    val calls: Int get() = instants.size

    override suspend fun markChanged(nowEpochMillis: Long) {
        // Dentro del bloque, Room entrega la misma conexión de la transacción abierta.
        insideTransaction += database.useWriterConnection { it.inTransaction() }
        failure?.let { throw it }
        real.markChanged(nowEpochMillis)
        instants += nowEpochMillis
    }
}

/** Cuenta cuántas veces se avisó una confirmación, y puede fallar a propósito. */
internal class RecordingCommitListener : CommitListener {
    var failure: RuntimeException? = null
    var calls = 0
        private set

    override fun onCommitted() {
        calls++
        failure?.let { throw it }
    }
}

internal fun runnerFor(
    database: RoomDatabase,
    changeCounter: ChangeCounter,
    restoreLock: RestoreLock = RestoreLock { false },
    storageSpace: StorageSpace = StorageSpace { false },
    commitListener: CommitListener = CommitListener {},
    clock: Clock = FIXED_CLOCK,
): RoomTransactionRunner = RoomTransactionRunner(
    database,
    changeCounter,
    restoreLock,
    storageSpace,
    clock,
    commitListener,
)

/** Los pasos de [TestSaleUseCase] tras los que se puede inyectar una falla. */
internal enum class SaleStep {
    READ_PRODUCTS,
    INSERT_HEADER,
    INSERT_LINE,
    APPEND_MOVEMENT,
    UPDATE_STOCK_STATE,
}

/** Un punto de la venta: el paso, y la línea si el paso es de una línea. */
internal data class StepPoint(val step: SaleStep, val line: Int = NO_LINE) {
    override fun toString(): String = if (line == NO_LINE) "$step" else "$step:$line"

    companion object {
        const val NO_LINE = -1

        fun parse(text: String): StepPoint {
            val parts = text.split(':')
            return StepPoint(SaleStep.valueOf(parts[0]), parts.getOrNull(1)?.toInt() ?: NO_LINE)
        }

        /** Todos los puntos de una venta de [lines] líneas, en el orden en que pasan. */
        fun all(lines: Int): List<StepPoint> =
            listOf(StepPoint(SaleStep.READ_PRODUCTS), StepPoint(SaleStep.INSERT_HEADER)) +
                (0 until lines).flatMap { line ->
                    listOf(
                        SaleStep.INSERT_LINE,
                        SaleStep.APPEND_MOVEMENT,
                        SaleStep.UPDATE_STOCK_STATE,
                    )
                        .map { StepPoint(it, line) }
                }
    }
}

/** Lo que corre al llegar a cada punto de la venta. */
internal fun interface StepHook {
    suspend fun reached(point: StepPoint)
}

/** Cantidad vendida en cada línea: dos unidades. */
internal const val SOLD_MILLI = 2_000L

/**
 * Una venta con la forma de la real, escrita con los DAO dentro de una sola transacción: lee los
 * productos, inserta la cabecera y, por cada línea, la línea, su fila `SALE` del libro y el saldo,
 * el costo y el valor del producto. Una venta sin stock suficiente no se bloquea: el saldo puede
 * quedar negativo.
 *
 * El costo de cada línea es el costo promedio del producto por la cantidad. Un producto puede
 * repetirse en varias líneas: cada línea parte del saldo que dejó la anterior, leído dentro de la
 * misma transacción.
 */
internal class TestSaleUseCase(
    private val transactions: TransactionRunner,
    private val database: AppDatabase,
    private val priceListId: Long,
) {
    /** Devuelve el id de la venta, o `null` si [reject] pide deshacerla al final. */
    suspend operator fun invoke(
        productIds: List<Long>,
        reject: Boolean = false,
        hook: StepHook = StepHook {},
    ): Long? = transactions.write {
        val products =
            database.productDao().findByIds(productIds.distinct()).associateBy { it.id }
                .toMutableMap()
        hook.reached(StepPoint(SaleStep.READ_PRODUCTS))
        val lineCosts = productIds.map { lineCost(requireNotNull(products[it])) }
        val saleId = database.saleDao().insert(header(productIds.size, lineCosts.sum()))
        hook.reached(StepPoint(SaleStep.INSERT_HEADER))
        productIds.forEachIndexed { index, productId ->
            val current = requireNotNull(products[productId]) { "El producto no existe" }
            val cost = lineCosts[index]
            val lineId =
                database.saleLineDao().insert(
                    saleLine(saleId, productId, priceListId).copy(
                        unitCost = current.averageCost,
                        lineCost = cost,
                    ),
                )
            hook.reached(StepPoint(SaleStep.INSERT_LINE, index))
            val balance = current.onHandMilli - SOLD_MILLI
            val value = current.stockValue - cost
            database.stockMovementDao().insert(
                movement(
                    productId,
                    type = "SALE",
                    quantityMilli = -SOLD_MILLI,
                    unitCost = current.averageCost,
                    totalCost = -cost,
                    balanceAfterMilli = balance,
                    averageCostAfter = current.averageCost,
                    stockValueAfter = value,
                ).copy(saleLineId = lineId),
            )
            hook.reached(StepPoint(SaleStep.APPEND_MOVEMENT, index))
            database.productDao().updateStockState(
                productId,
                onHandMilli = balance,
                averageCost = current.averageCost,
                stockValue = value,
                updatedAt = CREATED_AT,
            )
            hook.reached(StepPoint(SaleStep.UPDATE_STOCK_STATE, index))
            products[productId] = current.copy(onHandMilli = balance, stockValue = value)
        }
        if (reject) TxResult.Rollback(null) else TxResult.Commit(saleId)
    }

    private fun lineCost(product: ProductEntity): Long =
        Math.multiplyExact(product.averageCost, SOLD_MILLI) / MILLI

    /** La cabecera cuadra con sus líneas: cada una es la de [saleLine], con su costo. */
    private fun header(lines: Int, totalCost: Long) = sale(priceListId).copy(
        netTotal = LINE_NET * lines,
        taxTotal = LINE_TAX * lines,
        total = LINE_TOTAL * lines,
        totalCost = totalCost,
        totalReplacementCost = LINE_REPLACEMENT_COST * lines,
        orderAdjustment = LINE_ADJUSTMENT * lines,
        tipAmount = 0,
        cashRoundingAmount = 0,
        amountDue = LINE_TOTAL * lines,
    )

    private companion object {
        const val MILLI = 1_000L

        // Los montos de cada línea de saleLine.
        const val LINE_NET = 10_000L
        const val LINE_TAX = 1_900L
        const val LINE_TOTAL = 11_900L
        const val LINE_REPLACEMENT_COST = 6_500L
        const val LINE_ADJUSTMENT = -500L
    }
}

/** Los dos productos con stock de [seedStock]. */
internal data class SeededStock(val first: Long, val second: Long)

/** Crea dos productos, cada uno con su inventario inicial de diez unidades, ya confirmado. */
internal suspend fun seedStock(database: AppDatabase): SeededStock {
    suspend fun stocked(name: String): Long = database.withWriteTransaction {
        val id = database.productDao().insert(product(name))
        val opening = openingBalance(id)
        database.stockMovementDao().insert(opening)
        database.productDao().updateStockState(
            id,
            onHandMilli = opening.balanceAfterMilli,
            averageCost = opening.averageCostAfter,
            stockValue = opening.stockValueAfter,
            updatedAt = CREATED_AT,
        )
        id
    }
    return SeededStock(stocked("Arroz"), stocked("Lentejas"))
}

/**
 * Lo que una escritura a medias cambiaría: las filas de ventas y del libro, los productos y el
 * contador de cambios sin respaldar, con el instante del primero.
 */
internal data class DatabaseSnapshot(
    val sales: Long,
    val saleLines: Long,
    val movements: Long,
    val products: List<ProductEntity>,
    val changeSeq: Long,
    val firstUnsyncedAt: Long?,
)

internal suspend fun AppDatabase.snapshot(): DatabaseSnapshot {
    val backupState = requireNotNull(backupStateDao().get()) { "Falta la fila del respaldo" }
    val ids =
        useReaderConnection { it.texts("SELECT id FROM products ORDER BY id") }.map { it.toLong() }
    return DatabaseSnapshot(
        sales = countOf("sales"),
        saleLines = countOf("sale_lines"),
        movements = countOf("stock_movements"),
        products = productDao().findByIds(ids).sortedBy { it.id },
        changeSeq = backupState.changeSeq,
        firstUnsyncedAt = backupState.firstUnsyncedAt,
    )
}

private suspend fun AppDatabase.countOf(table: String): Long =
    useReaderConnection { it.longValue("SELECT COUNT(*) FROM $table") }

/**
 * Comprueba que nada quedó a medias: cada venta tiene líneas, cada línea su fila del libro, cada
 * producto el saldo que suman sus filas y el valor de la última, y SQLite ve el archivo sano.
 */
internal suspend fun AppDatabase.assertNothingHalfWritten(case: String) {
    val broken =
        useReaderConnection { connection ->
            listOf(
                "SELECT COUNT(*) FROM sales s " +
                    "WHERE NOT EXISTS (SELECT 1 FROM sale_lines l WHERE l.sale_id = s.id)",
                "SELECT COUNT(*) FROM sale_lines l " +
                    "WHERE NOT EXISTS (SELECT 1 FROM stock_movements m WHERE m.sale_line_id = l.id)",
                "SELECT COUNT(*) FROM products p WHERE p.on_hand_milli <> COALESCE(" +
                    "(SELECT SUM(m.quantity_milli) FROM stock_movements m " +
                    "WHERE m.product_id = p.id), 0)",
                "SELECT COUNT(*) FROM products p WHERE p.stock_value <> COALESCE(" +
                    "(SELECT m.stock_value_after FROM stock_movements m " +
                    "WHERE m.product_id = p.id ORDER BY m.id DESC LIMIT 1), 0)",
            ).map { connection.longValue(it) }
        }
    assertEquals("Filas a medias en $case", listOf(0L, 0L, 0L, 0L), broken)
    val integrity = useReaderConnection { it.texts("PRAGMA integrity_check") }
    assertEquals("Integridad en $case", listOf("ok"), integrity)
}

/**
 * Además de la base de las pruebas del libro, cada prueba empieza con dos productos con stock y un
 * runner que cuenta sus cambios en memoria.
 */
internal abstract class SaleTransactionTest : LedgerDatabaseTest() {
    protected lateinit var stock: SeededStock
    protected lateinit var changes: RecordingChangeCounter
    protected lateinit var listener: RecordingCommitListener
    protected lateinit var runner: RoomTransactionRunner
    protected lateinit var sales: TestSaleUseCase

    @Before
    fun seedSaleStock() = runBlocking {
        stock = seedStock(database)
        changes = RecordingChangeCounter(database)
        listener = RecordingCommitListener()
        runner = runnerFor(database, changes, commitListener = listener)
        sales = TestSaleUseCase(runner, database, priceListId)
    }

    /** Tres líneas, con el primer producto repetido. */
    protected val threeLines: List<Long> get() = listOf(stock.first, stock.second, stock.first)
}

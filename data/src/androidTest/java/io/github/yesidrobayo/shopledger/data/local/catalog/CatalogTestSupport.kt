package io.github.yesidrobayo.shopledger.data.local.catalog

import android.content.Context
import androidx.room3.executeSQL
import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteException
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.deleteDatabaseFiles
import io.github.yesidrobayo.shopledger.data.local.insertPriceListSql
import io.github.yesidrobayo.shopledger.data.local.longValue
import io.github.yesidrobayo.shopledger.data.local.testDatabaseFile
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before

// Apoyo de las pruebas del catálogo. Todos los nombres, códigos y montos son inventados.

/**
 * Cada prueba abre su propia base por el camino de la app, con el SQLite empaquetado, WAL y las
 * llaves foráneas activas, y la borra al terminar. Las filas que nacen con la base llevan el
 * instante de [FIXED_CLOCK].
 */
internal abstract class CatalogDatabaseTest {
    protected val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    protected val file = testDatabaseFile(context, "${javaClass.simpleName}.db")
    protected lateinit var database: AppDatabase

    @Before
    fun openDatabase() {
        deleteDatabaseFiles(file)
        database = runBlocking { AppDatabase.open(context, file, Dispatchers.IO, FIXED_CLOCK) }
    }

    @After
    fun closeDatabase() {
        database.close()
        deleteDatabaseFiles(file)
    }

    protected suspend fun execute(sql: String) {
        database.useWriterConnection { it.executeSQL(sql) }
    }

    protected suspend fun count(sql: String): Long = database.useReaderConnection {
        it.longValue(sql)
    }

    /** Crea una lista de precios fija y devuelve su id: el historial siempre apunta a una. */
    protected suspend fun insertPriceList(normalizedName: String): Long {
        execute(insertPriceListSql(normalizedName, "FIXED", basePriceListId = null))
        return count("SELECT id FROM price_lists WHERE normalized_name = '$normalizedName'")
    }
}

/** Un instante fijo cualquiera, en milisegundos UTC, y otros después de él. */
internal const val CREATED_AT = 1_767_225_600_000L
internal const val LATER = CREATED_AT + 60_000L
internal const val MUCH_LATER = CREATED_AT + 3_600_000L

/** Un reloj que siempre da [CREATED_AT]: las filas que nacen con la base salen iguales siempre. */
internal val FIXED_CLOCK = Clock { Instant.fromEpochMilliseconds(CREATED_AT) }

/** Unidad base cualquiera: la tabla de unidades todavía no existe, y la columna no tiene llave. */
internal const val UNIT_ID = 1L

internal fun category(name: String, targetMarginBp: Long? = null): CategoryEntity = CategoryEntity(
    name = name,
    normalizedName = TextNormalizer.normalize(name),
    targetMarginBp = targetMarginBp,
    createdAt = CREATED_AT,
    updatedAt = CREATED_AT,
)

internal fun supplier(name: String, archivedAt: Long? = null): SupplierEntity = SupplierEntity(
    name = name,
    normalizedName = TextNormalizer.normalize(name),
    phone = null,
    email = null,
    notes = null,
    balance = 0,
    paymentTermsDays = null,
    archivedAt = archivedAt,
    createdAt = CREATED_AT,
    updatedAt = CREATED_AT,
)

/** Un producto recién creado: sin stock, y con el costo promedio igual al inicial. */
internal fun product(
    name: String,
    categoryId: Long? = null,
    defaultSupplierId: Long? = null,
    parentProductId: Long? = null,
    barcode: String? = null,
    archivedAt: Long? = null,
): ProductEntity = ProductEntity(
    name = name,
    normalizedName = TextNormalizer.normalize(name),
    categoryId = categoryId,
    defaultSupplierId = defaultSupplierId,
    baseUnitId = UNIT_ID,
    stockMode = "TRACKED",
    salePrice = 2_500,
    averageCost = 1_200,
    initialCost = 1_200,
    fixedUnitCost = 0,
    stockValue = 0,
    onHandMilli = 0,
    minStockMilli = 0,
    barcode = barcode,
    parentProductId = parentProductId,
    variantAxis1 = null,
    variantAxis2 = null,
    variantValue1 = null,
    variantValue2 = null,
    photoMediaId = null,
    taxRateId = null,
    replacementCost = 0,
    replacementCostAt = null,
    targetMarginBp = null,
    referenceCost = null,
    referencePrice = null,
    priceReviewSince = null,
    tracksLots = 0,
    expiryNoticeDays = null,
    archivedAt = archivedAt,
    createdAt = CREATED_AT,
    updatedAt = CREATED_AT,
)

/** Un cambio de precio manual, el más común: con precio anterior y sin lote. */
internal fun manualChange(
    productId: Long,
    priceListId: Long,
    oldPrice: Long,
    newPrice: Long,
    occurredAt: Long = CREATED_AT,
): PriceHistoryEntity = PriceHistoryEntity(
    productId = productId,
    priceListId = priceListId,
    oldPrice = oldPrice,
    newPrice = newPrice,
    source = "MANUAL",
    batchId = null,
    bulkMode = null,
    percentBp = null,
    amountDelta = null,
    targetMarginBp = null,
    fxRateMicro = null,
    roundingStep = null,
    occurredAt = occurredAt,
    businessDate = "2026-01-01",
)

/**
 * Corre [block] y devuelve el error de SQLite que lanzó, o falla si no lanzó ninguno. Exige que el
 * mensaje nombre la restricción esperada, para que otro error no pase por el que se busca.
 */
internal suspend fun constraintFailure(constraint: String, block: suspend () -> Unit) {
    try {
        block()
    } catch (expected: SQLiteException) {
        val message = expected.message.orEmpty()
        assertTrue("Falló por otra causa: $message", message.contains(constraint))
        return
    }
    fail("La sentencia debía fallar por $constraint")
}

internal const val FOREIGN_KEY = "FOREIGN KEY constraint"
internal const val UNIQUE = "UNIQUE constraint"

/**
 * Corre [block] y exige que lo rechace la guarda que aborta con [guard], como `guard:sales`. El
 * mensaje debe terminar en él: `guard:sales` no pasa por `guard:sales_void_only`, ni otro error por
 * el de la guarda.
 */
internal suspend fun guardFailure(guard: String, block: suspend () -> Unit) {
    try {
        block()
    } catch (expected: SQLiteException) {
        val message = expected.message.orEmpty()
        assertTrue("Falló por otra causa: $message", message.endsWith(": $guard"))
        return
    }
    fail("La sentencia debía fallar por $guard")
}

/** Espera máxima por cada emisión de un [Flow]: si no llega, la prueba falla sin colgarse. */
private const val EMISSION_TIMEOUT_MS = 10_000L

/**
 * Las emisiones de un [Flow] de Room, en orden. Sirve para comprobar que una lectura vuelve a
 * emitir cuando se confirma una escritura que la cambia.
 *
 * Room vuelve a emitir con cualquier cambio de la tabla, aunque no cambie lo leído: por eso
 * [awaitValue] espera hasta ver el valor pedido, y falla si no llega a tiempo.
 */
internal class Emissions<T>(scope: CoroutineScope, flow: Flow<T>) {
    private val channel = Channel<T>(Channel.UNLIMITED)
    private val job: Job = scope.launch(Dispatchers.IO) { flow.collect { channel.send(it) } }

    /** Espera una emisión igual a [expected], y descarta las anteriores. */
    suspend fun awaitValue(expected: T) {
        val seen = mutableListOf<T>()
        val found =
            withTimeoutOrNull(EMISSION_TIMEOUT_MS) {
                while (seen.isEmpty() || seen.last() != expected) seen += channel.receive()
                true
            }
        if (found == null) fail("Esperaba la emisión $expected, y llegaron $seen")
    }

    fun stop() {
        job.cancel()
    }
}

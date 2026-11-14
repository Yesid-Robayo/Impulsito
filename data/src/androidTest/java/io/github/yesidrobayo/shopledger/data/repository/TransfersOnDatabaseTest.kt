package io.github.yesidrobayo.shopledger.data.repository

import androidx.room3.useReaderConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.data.backup.InvariantQueries
import io.github.yesidrobayo.shopledger.data.local.LOCATION_ID
import io.github.yesidrobayo.shopledger.data.local.catalog.FIXED_CLOCK
import io.github.yesidrobayo.shopledger.data.local.stock.location
import io.github.yesidrobayo.shopledger.data.local.texts
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementSource
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.model.stock.StockTransferLineRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.StockTransferRequest
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.stock.RegisterStockTransferResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.RegisterStockTransferUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.VoidStockTransferResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.VoidStockTransferUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Transferencias sobre la base real: con 10 unidades que valen 12000, mover 4 deja 6 y 4, el
 * producto sigue con 10 y el mismo valor, y las consultas de los saldos y de las transferencias
 * quedan sin filas, también al anular. Nombres y cantidades inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class TransfersOnDatabaseTest : RepositoryTest() {
    private lateinit var ports: DataPorts
    private lateinit var register: RegisterStockTransferUseCase
    private lateinit var void: VoidStockTransferUseCase
    private var warehouse = 0L

    @Before
    fun connectUseCases() = runBlocking<Unit> {
        ports = DataPorts(database, file, Dispatchers.IO, FIXED_CLOCK, RestoreLock { false })
        val calendar = BusinessCalendarSource(ports.settings)
        register = RegisterStockTransferUseCase(
            ports.transactions,
            ports.products,
            ports.units,
            ports.locations,
            ports.stockTransfers,
            ports.stockLedger,
            calendar,
            FIXED_CLOCK,
            ports.stockLots,
        )
        void = VoidStockTransferUseCase(
            ports.transactions,
            ports.products,
            ports.stockTransfers,
            ports.stockLedger,
            calendar,
            FIXED_CLOCK,
            ports.stockLots,
        )
        warehouse = database.locationDao().insert(location("Bodega"))
        // Diez unidades a 1200 en la ubicación inicial: valen 12000.
        commit { ports.stockLedger.append(openingRow(productId, units = 10)) }
    }

    private suspend fun move(milli: Long): Long {
        val request = StockTransferRequest(
            LOCATION_ID,
            warehouse,
            listOf(StockTransferLineRequest(productId, QuantityMilli(milli))),
            note = "Para la feria",
        )
        return (register(request) as RegisterStockTransferResult.Registered).transferId
    }

    @Test
    fun movingFourOfTenLeavesSixAndFourAndKeepsTheValue() = runBlocking {
        val before = changeSeq()

        val id = move(4_000L)

        assertEquals(before + 1L, changeSeq())
        assertEquals(6_000L, balanceAt(LOCATION_ID))
        assertEquals(4_000L, balanceAt(warehouse))
        val product = requireNotNull(database.productDao().findById(productId))
        assertEquals(
            listOf(10_000L, 1_200L, 12_000L),
            listOf(product.onHandMilli, product.averageCost, product.stockValue),
        )
        val stored = requireNotNull(database.stockTransferDao().findById(id))
        assertEquals(listOf("ACTIVE", "Para la feria"), listOf(stored.status, stored.note))
        assertEquals(listOf("TRANSFER_OUT", "TRANSFER_IN"), typesOf(id))
        assertEquals(emptyList<String>(), invariantRows())
    }

    @Test
    fun movingEverythingKeepsTheValueAndVoidingBringsItBack() = runBlocking {
        val id = move(10_000L)
        assertEquals(0L, balanceAt(LOCATION_ID))
        assertEquals(12_000L, database.productDao().findById(productId)?.stockValue)

        assertEquals(VoidStockTransferResult.Voided, void(id, VoidReason.ENTRY_ERROR))

        assertEquals(10_000L, balanceAt(LOCATION_ID))
        assertEquals(0L, balanceAt(warehouse))
        assertEquals("VOIDED", database.stockTransferDao().findById(id)?.status)
        assertEquals(
            listOf("TRANSFER_OUT", "TRANSFER_IN", "TRANSFER_VOID", "TRANSFER_VOID"),
            typesOf(id),
        )
        assertEquals(12_000L, database.productDao().findById(productId)?.stockValue)
        assertEquals(VoidStockTransferResult.AlreadyVoided, void(id, VoidReason.ENTRY_ERROR))
        assertEquals(emptyList<String>(), invariantRows())
    }

    @Test
    fun theTransferInvariantFindsALineWithOnlyItsExit() = runBlocking {
        val id = move(2_000L)
        val line = database.stockTransferDao().findLines(id).single()
        val lonely = database.stockTransferDao().insertLine(line.copy(id = 0))
        // Una salida sin su entrada, escrita directo: lo que la consulta debe delatar.
        commit {
            ports.stockLedger.append(
                ledgerRow(
                    productId,
                    StockMovementType.TRANSFER_OUT,
                    quantity = -1_000,
                    balanceAfter = 9_000,
                    unitCost = 0,
                    source = StockMovementSource(transferLineId = lonely),
                ),
            )
        }

        assertEquals(listOf("$lonely"), rowsOf("I-21"))
    }

    private suspend fun balanceAt(locationId: Long): Long =
        database.stockBalanceDao().find(productId, locationId, 0L)?.onHandMilli ?: 0L

    private suspend fun typesOf(transferId: Long): List<String> =
        database.stockTransferDao().findMovements(transferId).map { it.type }

    /** Las filas de las consultas de los saldos por ubicación y de las transferencias. */
    private suspend fun invariantRows(): List<String> = database.useReaderConnection { connection ->
        InvariantQueries.MODEL
            .filter { it.label == "I-10" || it.label.startsWith("I-11") || it.label == "I-21" }
            .flatMap { connection.texts(it.sql) }
    }

    private suspend fun rowsOf(label: String): List<String> =
        database.useReaderConnection { connection ->
            InvariantQueries.MODEL.filter { it.label == label }.flatMap { connection.texts(it.sql) }
        }
}

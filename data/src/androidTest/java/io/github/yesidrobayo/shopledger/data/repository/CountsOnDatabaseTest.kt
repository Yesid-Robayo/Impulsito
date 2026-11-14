package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.DataPorts
import io.github.yesidrobayo.shopledger.data.local.LOCATION_ID
import io.github.yesidrobayo.shopledger.data.local.catalog.FIXED_CLOCK
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.stock.OpenCountRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.StockAdjustmentReason
import io.github.yesidrobayo.shopledger.domain.model.stock.StockMovementType
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import io.github.yesidrobayo.shopledger.domain.usecase.stock.ApplyCountSessionUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.CancelCountSessionUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.FinishCountResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.OpenCountResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.OpenCountSessionUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.stock.RecordCountResult
import io.github.yesidrobayo.shopledger.domain.usecase.stock.RecordCountUseCase
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Un conteo a ciegas sobre la base real: abrir congela lo esperado, anotar lo guarda, y aplicar
 * escribe el ajuste con la sesión en una transacción. Esperado 12 y contado 10 dan −2000.
 */
@RunWith(AndroidJUnit4::class)
internal class CountsOnDatabaseTest : RepositoryTest() {
    private lateinit var ports: DataPorts
    private lateinit var open: OpenCountSessionUseCase
    private lateinit var record: RecordCountUseCase
    private lateinit var apply: ApplyCountSessionUseCase
    private lateinit var cancel: CancelCountSessionUseCase

    @Before
    fun connectUseCases() = runBlocking<Unit> {
        ports = DataPorts(database, file, Dispatchers.IO, FIXED_CLOCK, RestoreLock { false })
        val calendar = BusinessCalendarSource(ports.settings)
        open = OpenCountSessionUseCase(
            ports.transactions,
            ports.countSessions,
            ports.products,
            ports.categories,
            ports.locations,
            ports.locationStock,
            calendar,
            FIXED_CLOCK,
        )
        record = RecordCountUseCase(
            ports.transactions,
            ports.countSessions,
            ports.products,
            ports.units,
        )
        apply = ApplyCountSessionUseCase(
            ports.transactions,
            ports.countSessions,
            ports.products,
            ports.stockLedger,
            ports.lowStockAlerts,
            calendar,
            CurrencyLock(ports.settings),
            FIXED_CLOCK,
        )
        cancel = CancelCountSessionUseCase(ports.transactions, ports.countSessions, FIXED_CLOCK)
        // Doce unidades a 1200 en la ubicación inicial.
        commit { ports.stockLedger.append(openingRow(productId, units = 12)) }
    }

    @Test
    fun expectedTwelveCountedTenAppliesMinusTwoThousandWithTheSession() = runBlocking {
        val id = (open(OpenCountRequest(LOCATION_ID)) as OpenCountResult.Opened).sessionId
        val line = ports.countSessions.findLines(id).single()
        assertEquals(12_000L, line.expected.milli)

        assertEquals(RecordCountResult.Recorded, record(id, line.id, QuantityMilli(10_000L)))
        assertEquals(FinishCountResult.Applied(1), apply(id))

        val row = ports.stockLedger.findByProduct(productId).last()
        assertEquals(StockMovementType.ADJUSTMENT, row.movement.type)
        assertEquals(StockAdjustmentReason.COUNT_CORRECTION, row.reason)
        assertEquals(-2_000L, row.movement.quantity.milli)
        assertEquals(id, row.source.countSessionId)
        assertEquals(10_000L, database.productDao().findById(productId)?.onHandMilli)
        assertEquals("APPLIED", database.countSessionDao().findById(id)?.status)
        assertEquals(RecordCountResult.SessionClosed, record(id, line.id, QuantityMilli(1L)))
    }

    @Test
    fun aMovementAfterOpeningIsSeenAndCancellingWritesNothing() = runBlocking {
        val id = (open(OpenCountRequest(LOCATION_ID)) as OpenCountResult.Opened).sessionId
        val before = ports.stockLedger.findByProduct(productId).size
        assertEquals(emptySet<Long>(), ports.countSessions.productsMovedSinceOpen(id))

        // Una merma registrada después de abrir, con un instante posterior.
        commit {
            ports.stockLedger.append(
                ledgerRow(
                    productId,
                    StockMovementType.ADJUSTMENT,
                    quantity = -1_000,
                    balanceAfter = 11_000,
                    reason = StockAdjustmentReason.DAMAGED,
                    occurredAt = FIXED_CLOCK.now() + 1.minutes,
                ),
            )
        }

        assertEquals(setOf(productId), ports.countSessions.productsMovedSinceOpen(id))
        assertEquals(FinishCountResult.Cancelled, cancel(id))
        assertEquals(before + 1, ports.stockLedger.findByProduct(productId).size)
        assertEquals("CANCELLED", database.countSessionDao().findById(id)?.status)
    }
}

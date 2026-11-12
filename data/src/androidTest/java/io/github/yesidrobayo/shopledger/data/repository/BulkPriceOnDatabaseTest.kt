package io.github.yesidrobayo.shopledger.data.repository

import android.util.Log
import androidx.room3.withWriteTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.catalog.product
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceAdjustment
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceRule
import io.github.yesidrobayo.shopledger.domain.model.catalog.PriceChangeSource
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ApplyBulkPriceResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.ApplyBulkPriceUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import kotlin.time.TimeSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Aplicar una actualización masiva sobre la base real: 5000 precios y sus 5000 filas de historial
 * en una sola transacción, que cuenta un solo cambio, en el tiempo de la meta, tres segundos o menos.
 * Un emulador no es el teléfono de referencia: el tiempo se anota para comparar. Los productos y
 * montos son inventados, y el registro solo lleva conteos y duraciones.
 */
@RunWith(AndroidJUnit4::class)
internal class BulkPriceOnDatabaseTest : RepositoryTest() {
    private val products by lazy { RoomProductRepository(database) }
    private val history by lazy { RoomPriceHistoryRepository(database) }
    private val settings by lazy { RoomAppSettingsRepository(database, RestoreLock { false }) }

    private val apply by lazy {
        ApplyBulkPriceUseCase(
            runner,
            products,
            RoomSystemRowRepository(database),
            history,
            BusinessCalendarSource(settings),
            CurrencyLock(settings),
            Clock { LATER_AT },
        )
    }

    @Test
    fun fiveThousandPricesChangeInOneTransactionWithinTheGoal() = runBlocking {
        val ids = database.withWriteTransaction {
            (1..PRODUCTS).map { database.productDao().insert(product("Producto inventado $it")) }
        }
        val before = changeSeq()

        val mark = TimeSource.Monotonic.markNow()
        val result = apply(ids, BasisPoints(800L), Money(100L))
        val elapsed = mark.elapsedNow().inWholeMilliseconds
        Log.i(TAG, "aplicar $PRODUCTS precios: $elapsed ms")

        val applied = result as ApplyBulkPriceResult.Applied
        assertEquals(PRODUCTS, applied.changed)
        assertEquals(before + 1, changeSeq())
        // 2500 × 1,08 = 2700, que ya es múltiplo de 100.
        assertEquals(
            PRODUCTS.toLong(),
            count("SELECT COUNT(*) FROM products WHERE sale_price = 2700"),
        )
        val batch = history.findByBatch(applied.batchId)
        assertEquals(PRODUCTS, batch.size)
        val rule = BulkPriceRule(BulkPriceAdjustment.Percent(BasisPoints(800L)), Money(100L))
        assertTrue(
            batch.all {
                it.content.source == PriceChangeSource.BULK &&
                    it.content.bulkRule == rule
            },
        )
        assertTrue("Aplicar tardó $elapsed ms", elapsed <= LIMIT_MILLIS)
    }

    private companion object {
        const val PRODUCTS = 5_000
        const val LIMIT_MILLIS = 3_000L
        const val TAG = "BulkPriceTiming"
    }
}

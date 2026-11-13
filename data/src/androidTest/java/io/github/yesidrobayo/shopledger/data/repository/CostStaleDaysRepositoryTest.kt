package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.usecase.settings.ObserveCostStaleDaysUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.settings.SetCostStaleDaysResult
import io.github.yesidrobayo.shopledger.domain.usecase.settings.SetCostStaleDaysUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Los días para costo viejo en una base real: cambiarlos escribe solo `cost_stale_days` y la fecha
 * del cambio, y cuenta un cambio para el respaldo; pedir lo que ya estaba, o un valor fuera de
 * rango, no escribe ni cuenta nada.
 */
@RunWith(AndroidJUnit4::class)
internal class CostStaleDaysRepositoryTest : RepositoryTest() {
    private val settings by lazy { RoomAppSettingsRepository(database, RestoreLock { false }) }
    private val setDays by lazy { SetCostStaleDaysUseCase(runner, settings, Clock { AT }) }

    @Test
    fun changingTheDaysWritesOnlyThatColumnAndCountsAChange() = runBlocking {
        val before = changeSeq()
        val row = requireNotNull(database.appSettingsDao().get())

        assertEquals(SetCostStaleDaysResult.Saved, setDays(0))

        assertEquals(
            row.copy(costStaleDays = 0L, updatedAt = AT.toEpochMilliseconds()),
            database.appSettingsDao().get(),
        )
        assertEquals(before + 1, changeSeq())
        assertEquals(0, ObserveCostStaleDaysUseCase(settings)().first())

        assertEquals(SetCostStaleDaysResult.Saved, setDays(36_500))

        assertEquals(36_500L, requireNotNull(database.appSettingsDao().get()).costStaleDays)
        assertEquals(before + 2, changeSeq())
    }

    @Test
    fun theSameValueOrOneOutOfRangeWritesNothing() = runBlocking {
        val before = changeSeq()
        val row = requireNotNull(database.appSettingsDao().get())

        // La base nace con 90.
        assertEquals(SetCostStaleDaysResult.Saved, setDays(90))
        assertEquals(SetCostStaleDaysResult.OutOfRange, setDays(-1))
        assertEquals(SetCostStaleDaysResult.OutOfRange, setDays(36_501))

        assertEquals(row, database.appSettingsDao().get())
        assertEquals(before, changeSeq())
    }
}

package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.usecase.report.SetDailySummaryTimeUseCase
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La hora del resumen del día sobre la base real: se guarda como `HH:mm`, que la guarda acepta,
 * se lee de vuelta igual, nula lo apaga, y cada cambio cuenta para la copia.
 */
@RunWith(AndroidJUnit4::class)
internal class DailySummaryTimeTest : RepositoryTest() {
    private val settings by lazy { RoomAppSettingsRepository(database, RestoreLock { false }) }

    @Test
    fun theTimeIsSavedAsHoursAndMinutesAndNullTurnsItOff() = runBlocking {
        val set = SetDailySummaryTimeUseCase(runner, settings) { AT }
        val before = changeSeq()

        set(LocalTime(7, 5, 30))
        assertEquals(LocalTime(7, 5), settings.get().notices.dailySummaryTime)
        assertEquals(before + 1, changeSeq())

        set(LocalTime(7, 5))
        assertEquals(before + 1, changeSeq())

        set(null)
        assertNull(settings.get().notices.dailySummaryTime)
        assertEquals(before + 2, changeSeq())
    }

    private companion object {
        val AT: Instant = Instant.fromEpochMilliseconds(1_793_600_000_000L)
    }
}

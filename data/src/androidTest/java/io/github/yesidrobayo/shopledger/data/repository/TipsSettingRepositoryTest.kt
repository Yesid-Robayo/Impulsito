package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.usecase.payment.ObserveTipsEnabledUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.payment.SetTipsEnabledResult
import io.github.yesidrobayo.shopledger.domain.usecase.payment.SetTipsEnabledUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las propinas en una base real: encenderlas escribe `tips_enabled` y cuenta un cambio para el
 * respaldo, en una sola transacción, y pedir lo que ya estaba no escribe ni cuenta nada.
 */
@RunWith(AndroidJUnit4::class)
internal class TipsSettingRepositoryTest : RepositoryTest() {
    private val settings by lazy { RoomAppSettingsRepository(database, RestoreLock { false }) }
    private val setTips by lazy { SetTipsEnabledUseCase(runner, settings, Clock { AT }) }

    private suspend fun stored(): Long = requireNotNull(database.appSettingsDao().get()).tipsEnabled

    @Test
    fun turningTipsOnAndOffWritesTheColumnAndCountsAChange() = runBlocking {
        val before = changeSeq()
        val row = requireNotNull(database.appSettingsDao().get())

        assertEquals(SetTipsEnabledResult.Saved, setTips(true))

        // Solo cambian las propinas y la fecha del cambio: el resto de la fila queda igual.
        assertEquals(
            row.copy(tipsEnabled = 1L, updatedAt = AT.toEpochMilliseconds()),
            database.appSettingsDao().get(),
        )
        assertEquals(1L, stored())
        assertEquals(before + 1, changeSeq())
        assertEquals(
            AT.toEpochMilliseconds(),
            requireNotNull(database.appSettingsDao().get()).updatedAt,
        )
        assertEquals(true, ObserveTipsEnabledUseCase(settings)().first())

        assertEquals(SetTipsEnabledResult.Saved, setTips(false))

        assertEquals(0L, stored())
        assertEquals(before + 2, changeSeq())
    }

    @Test
    fun askingForWhatIsAlreadyThereWritesNothing() = runBlocking {
        val before = changeSeq()

        assertEquals(SetTipsEnabledResult.Saved, setTips(false))

        assertEquals(0L, stored())
        assertEquals(before, changeSeq())
    }
}

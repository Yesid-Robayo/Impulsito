package io.github.yesidrobayo.shopledger.domain.usecase.payment

import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.testing.FakeAppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeTransactionRunner
import io.github.yesidrobayo.shopledger.domain.testing.FixedClock
import io.github.yesidrobayo.shopledger.domain.testing.InMemoryDatabase
import io.github.yesidrobayo.shopledger.domain.testing.appSettings
import io.github.yesidrobayo.shopledger.domain.testing.regionalSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Encender y apagar las propinas: es un dato del negocio, así que se guarda en una transacción que
 * cuenta como cambio, y si ya estaba así no escribe nada. Una falla de la base se devuelve sin
 * cambiar nada.
 */
class TipsUseCasesTest {
    private val settings = FakeAppSettingsRepository(
        appSettings(regionalSettings("Etc/GMT+5", DayOfWeek.MONDAY)),
    )
    private val transactions = FakeTransactionRunner(InMemoryDatabase())
    private val clock = FixedClock.atEpochMillis(1_793_600_000_000L)
    private val setTips = SetTipsEnabledUseCase(transactions, settings, clock)

    @Test
    fun `turning tips on is saved as a business change`() = runTest {
        assertEquals(SetTipsEnabledResult.Saved, setTips(true))

        assertEquals(true, settings.settings.value.sales.tipsEnabled)
        assertEquals(listOf(true to clock.now()), settings.tipsWrites)
        assertEquals(1, transactions.commits)
    }

    @Test
    fun `setting what is already there writes nothing`() = runTest {
        assertEquals(SetTipsEnabledResult.Saved, setTips(false))

        assertEquals(emptyList<Pair<Boolean, Any>>(), settings.tipsWrites)
        assertEquals(0, transactions.commits)
        assertEquals(1, transactions.rollbacks)
    }

    @Test
    fun `a storage failure changes nothing`() = runTest {
        transactions.restoreInProgress = true

        assertEquals(
            SetTipsEnabledResult.Failed(StorageFailure.RESTORE_IN_PROGRESS),
            setTips(true),
        )
        assertEquals(false, settings.settings.value.sales.tipsEnabled)
    }

    @Test
    fun `observing emits only when tips change`() = runTest {
        val observe = ObserveTipsEnabledUseCase(settings)
        val seen = mutableListOf<Boolean>()
        val job = launch { observe().take(2).toList(seen) }
        testScheduler.runCurrent()

        setTips(false)
        setTips(true)
        job.join()

        assertEquals(listOf(false, true), seen)
        assertEquals(true, observe().first())
    }
}

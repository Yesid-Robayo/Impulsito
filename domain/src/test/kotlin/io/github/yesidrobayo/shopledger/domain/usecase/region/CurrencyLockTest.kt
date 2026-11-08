package io.github.yesidrobayo.shopledger.domain.usecase.region

import io.github.yesidrobayo.shopledger.domain.model.settings.AppSettings
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeAppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.testing.appSettings
import io.github.yesidrobayo.shopledger.domain.testing.regionalSettings
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El bloqueo de la moneda con el primer registro con dinero: sin moneda no se guarda nada, el
 * primero la bloquea y los siguientes no mueven el instante. La moneda es la de prueba de ISO 4217.
 */
class CurrencyLockTest {
    private val first = Instant.fromEpochMilliseconds(1_793_593_800_000)
    private val second = Instant.fromEpochMilliseconds(1_793_593_860_000)

    private fun settings(confirmed: Boolean, lockedAt: Instant? = null): AppSettings {
        val regional = regionalSettings("Etc/GMT+5", DayOfWeek.MONDAY)
        val currency =
            if (confirmed) {
                regional.currency.copy(lockedAt = lockedAt)
            } else {
                regional.currency.copy(code = CurrencyCode.UNCONFIRMED, decimals = 0)
            }
        return appSettings(regional.copy(currency = currency))
    }

    @Test
    fun `without a confirmed currency nothing is locked and the record is refused`() = runTest {
        val repository = FakeAppSettingsRepository(settings(confirmed = false))

        assertEquals(
            CurrencyLockResult.Unconfirmed,
            CurrencyLock(repository).lockForMoneyRecord(first),
        )
        assertTrue(repository.lockCalls.isEmpty())
        assertEquals(null, repository.settings.value.regional.currency.lockedAt)
    }

    @Test
    fun `the first record with money locks the currency at its own instant`() = runTest {
        val repository = FakeAppSettingsRepository(settings(confirmed = true))
        val lock = CurrencyLock(repository)

        val result = lock.lockForMoneyRecord(first)

        val locked = repository.settings.value.regional.currency
        assertEquals(first, locked.lockedAt)
        assertEquals(CurrencyLockResult.Locked(locked), result)
        assertEquals(listOf(first), repository.lockCalls)
    }

    @Test
    fun `later records keep the first lock and write nothing`() = runTest {
        val repository = FakeAppSettingsRepository(settings(confirmed = true, lockedAt = first))

        val result = CurrencyLock(repository).lockForMoneyRecord(second)

        assertEquals(CurrencyLockResult.Locked(repository.settings.value.regional.currency), result)
        assertEquals(first, (result as CurrencyLockResult.Locked).currency.lockedAt)
        assertTrue(repository.lockCalls.isEmpty())
    }

    @Test
    fun `if the lock was already there, the saved instant wins`() = runTest {
        // Lo leído dice sin bloquear, pero la base ya lo tenía: vale lo guardado, que no se mueve.
        val stored = FakeAppSettingsRepository(settings(confirmed = true, lockedAt = first))
        val stale = settings(confirmed = true)
        var reads = 0
        val repository =
            object : AppSettingsRepository by stored {
                override suspend fun get(): AppSettings = if (reads++ == 0) stale else stored.get()
            }

        val result = CurrencyLock(repository).lockForMoneyRecord(second)

        assertEquals(first, (result as CurrencyLockResult.Locked).currency.lockedAt)
        assertEquals(listOf(second), stored.lockCalls)
    }
}

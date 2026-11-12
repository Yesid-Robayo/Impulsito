package io.github.yesidrobayo.shopledger.domain.usecase.settings

import io.github.yesidrobayo.shopledger.domain.model.settings.AppLockTimeout
import io.github.yesidrobayo.shopledger.domain.model.settings.PreferenceChange
import io.github.yesidrobayo.shopledger.domain.model.settings.ThemeMode
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.testing.FakeAppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.testing.FixedClock
import io.github.yesidrobayo.shopledger.domain.testing.appSettings
import io.github.yesidrobayo.shopledger.domain.testing.regionalSettings
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Las preferencias del bloqueo: se guardan una por una con la hora del reloj, una falla de la base se
 * devuelve sin cambiar nada, y quien observa el bloqueo solo se entera cuando el bloqueo cambia.
 */
class PreferenceUseCasesTest {
    private val settings =
        FakeAppSettingsRepository(appSettings(regionalSettings("Etc/GMT+5", DayOfWeek.MONDAY)))
    private val clock = FixedClock.atEpochMillis(1_793_600_000_000L)
    private val change = ChangePreferenceUseCase(settings, clock)

    @Test
    fun `turning the lock on saves only that preference with the clock time`() = runTest {
        assertEquals(ChangePreferenceResult.Saved, change(PreferenceChange.AppLock(true)))

        assertEquals(true, settings.settings.value.preferences.appLockEnabled)
        assertEquals(
            listOf<PreferenceChange>(PreferenceChange.AppLock(true)),
            settings.preferenceWrites,
        )
        assertEquals(clock.now(), settings.settings.value.updatedAt)
    }

    @Test
    fun `the delay is saved as chosen`() = runTest {
        change(PreferenceChange.AppLockDelay(AppLockTimeout.IMMEDIATELY))

        assertEquals(AppLockTimeout.IMMEDIATELY, settings.settings.value.preferences.appLockTimeout)
    }

    @Test
    fun `a storage failure is returned and nothing changes`() = runTest {
        settings.failNextPreference = StorageFailure.RESTORE_IN_PROGRESS

        assertEquals(
            ChangePreferenceResult.Failed(StorageFailure.RESTORE_IN_PROGRESS),
            change(PreferenceChange.AppLock(true)),
        )
        assertEquals(false, settings.settings.value.preferences.appLockEnabled)
    }

    @Test
    fun `the lock is observed only when the lock changes`() = runTest {
        val seen = mutableListOf<AppLockSettings>()
        val job = launch { ObserveAppLockUseCase(settings)().take(3).toList(seen) }
        testScheduler.runCurrent()

        // Otra preferencia no cambia el bloqueo: no se vuelve a emitir.
        change(PreferenceChange.Theme(ThemeMode.DARK))
        testScheduler.runCurrent()
        change(PreferenceChange.AppLock(true))
        testScheduler.runCurrent()
        change(PreferenceChange.AppLockDelay(AppLockTimeout.FIVE_MINUTES))
        job.join()

        assertEquals(
            listOf(
                AppLockSettings(false, AppLockTimeout.ONE_MINUTE),
                AppLockSettings(true, AppLockTimeout.ONE_MINUTE),
                AppLockSettings(true, AppLockTimeout.FIVE_MINUTES),
            ),
            seen,
        )
    }
}

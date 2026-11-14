package io.github.yesidrobayo.shopledger.domain.usecase.document

import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.testing.FakeAppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeTransactionRunner
import io.github.yesidrobayo.shopledger.domain.testing.FixedClock
import io.github.yesidrobayo.shopledger.domain.testing.InMemoryDatabase
import io.github.yesidrobayo.shopledger.domain.testing.appSettings
import io.github.yesidrobayo.shopledger.domain.testing.regionalSettings
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * El enlace al sistema externo: solo `https` con servidor, hasta 300 caracteres, vacío lo quita, y
 * lo que no cambia no cuenta como cambio. Direcciones inventadas de `example.com`.
 */
class FiscalPortalLinkUseCasesTest {
    private val database = InMemoryDatabase()
    private val runner = FakeTransactionRunner(database)
    private val settings =
        FakeAppSettingsRepository(appSettings(regionalSettings("UTC", DayOfWeek.MONDAY)))
    private val save = SaveFiscalPortalLinkUseCase(runner, settings, FixedClock(NOW))
    private val observe = ObserveFiscalPortalLinkUseCase(settings)

    @Test
    fun `an https link is saved trimmed and can be removed by leaving it empty`() = runTest {
        assertEquals(
            SavePortalLinkResult.Saved(LINK),
            save("  $LINK "),
        )
        assertEquals(LINK, observe().first())
        assertEquals(listOf<Pair<String?, Instant>>(LINK to NOW), settings.fiscalPortalWrites)

        assertEquals(SavePortalLinkResult.Saved(null), save("   "))
        assertNull(observe().first())
        assertEquals(2, runner.commits)
    }

    @Test
    fun `the same link again writes nothing`() = runTest {
        save(LINK)

        assertEquals(SavePortalLinkResult.Saved(LINK), save(LINK))

        assertEquals(1, settings.fiscalPortalWrites.size)
        assertEquals(1, runner.commits)
        assertEquals(1, runner.rollbacks)
    }

    @Test
    fun `a link that is not https or is too long is rejected without writing`() = runTest {
        assertEquals(SavePortalLinkResult.NotHttps, save("http://example.com"))
        assertEquals(
            SavePortalLinkResult.TooLong,
            save("https://example.com/" + "a".repeat(281)),
        )

        assertEquals(0, runner.writeCalls)
    }

    @Test
    fun `a storage failure is returned`() = runTest {
        runner.restoreInProgress = true

        assertEquals(
            SavePortalLinkResult.Failed(StorageFailure.RESTORE_IN_PROGRESS),
            save(LINK),
        )
    }

    private companion object {
        const val LINK = "https://example.com/portal"
        val NOW: Instant = Instant.fromEpochMilliseconds(1_793_000_000_000)
    }
}

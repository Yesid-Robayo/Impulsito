package io.github.yesidrobayo.shopledger.domain.usecase.onboarding

import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.testing.FakeAppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeTransactionRunner
import io.github.yesidrobayo.shopledger.domain.testing.FixedClock
import io.github.yesidrobayo.shopledger.domain.testing.InMemoryDatabase
import io.github.yesidrobayo.shopledger.domain.testing.appSettings
import io.github.yesidrobayo.shopledger.domain.testing.regionalSettings
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * El primer uso: se retoma donde quedó según lo guardado, y terminarlo queda escrito una sola vez,
 * en su propia transacción. Una falla de la base no lo marca.
 */
class OnboardingUseCasesTest {
    private val settings = FakeAppSettingsRepository(
        appSettings(regionalSettings("Etc/GMT+5", DayOfWeek.MONDAY)).let {
            it.copy(
                regional = it.regional.copy(
                    currency = it.regional.currency.copy(
                        code = requireNotNull(CurrencyCode.of("XXX")),
                    ),
                ),
            )
        },
    )
    private val transactions = FakeTransactionRunner(InMemoryDatabase())
    private val clock = FixedClock.atEpochMillis(1_793_600_000_000L)

    @Test
    fun `without a confirmed currency it starts with the language`() = runTest {
        assertEquals(OnboardingResume.LANGUAGE, ResumeOnboardingUseCase(settings)())
    }

    @Test
    fun `a confirmed region goes on with the welcome`() = runTest {
        val current = settings.settings.value
        settings.settings.value = current.copy(
            regional = current.regional.copy(
                currency = current.regional.currency.copy(
                    code = requireNotNull(CurrencyCode.of("XTS")),
                ),
            ),
        )

        assertEquals(OnboardingResume.WELCOME, ResumeOnboardingUseCase(settings)())
    }

    @Test
    fun `completing is written once`() = runTest {
        val complete = CompleteOnboardingUseCase(transactions, settings, clock)

        assertEquals(CompleteOnboardingResult.Completed, complete())
        assertEquals(clock.now(), settings.settings.value.onboardingCompletedAt)
        assertEquals(OnboardingResume.DONE, ResumeOnboardingUseCase(settings)())
        assertEquals(CompleteOnboardingResult.Completed, complete())
        assertEquals(1, transactions.commits)
        assertEquals(1, transactions.rollbacks)
    }

    @Test
    fun `a storage failure does not complete it`() = runTest {
        transactions.restoreInProgress = true

        assertEquals(
            CompleteOnboardingResult.Failed(StorageFailure.RESTORE_IN_PROGRESS),
            CompleteOnboardingUseCase(transactions, settings, clock)(),
        )
        assertEquals(null, settings.settings.value.onboardingCompletedAt)
    }
}

package io.github.yesidrobayo.shopledger.domain.usecase.settings

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.settings.BusinessProfile
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.testing.FakeAppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeTransactionRunner
import io.github.yesidrobayo.shopledger.domain.testing.FixedClock
import io.github.yesidrobayo.shopledger.domain.testing.InMemoryDatabase
import io.github.yesidrobayo.shopledger.domain.testing.appSettings
import io.github.yesidrobayo.shopledger.domain.testing.regionalSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El perfil del negocio: el nombre y el margen objetivo se guardan cada uno sin pisar el otro, en
 * una transacción que cuenta como cambio. Un margen fuera de 0 a 99 % y un nombre largo se rechazan
 * sin abrirla. Datos inventados.
 */
class BusinessProfileUseCasesTest {
    private val settings = FakeAppSettingsRepository(
        appSettings(regionalSettings("Etc/GMT+5", DayOfWeek.MONDAY)),
    )
    private val transactions = FakeTransactionRunner(InMemoryDatabase())
    private val clock = FixedClock.atEpochMillis(1_793_600_000_000L)
    private val save = SaveBusinessProfileUseCase(transactions, settings, clock)

    private val profile: BusinessProfile get() = settings.settings.value.profile

    @Test
    fun `a new target margin is saved as a business change and keeps the name`() = runTest {
        save(BusinessProfileChange.Name("  Tienda de prueba  "))

        assertEquals(
            SaveBusinessProfileResult.Saved,
            save(BusinessProfileChange.TargetMargin(BasisPoints(4_000L))),
        )

        assertEquals(BusinessProfile("Tienda de prueba", BasisPoints(4_000L)), profile)
        assertEquals(2, transactions.commits)
        assertEquals(clock.now(), settings.profileWrites.last().second)
    }

    @Test
    fun `the margin can go from zero to ninety nine percent and no further`() = runTest {
        assertEquals(
            SaveBusinessProfileResult.Saved,
            save(BusinessProfileChange.TargetMargin(BasisPoints.MAX_TARGET_MARGIN)),
        )
        assertEquals(
            SaveBusinessProfileResult.Saved,
            save(BusinessProfileChange.TargetMargin(BasisPoints.ZERO)),
        )
        assertEquals(
            SaveBusinessProfileResult.TargetMarginOutOfRange,
            save(BusinessProfileChange.TargetMargin(BasisPoints(9_901L))),
        )
        assertEquals(
            SaveBusinessProfileResult.TargetMarginOutOfRange,
            save(BusinessProfileChange.TargetMargin(BasisPoints(-1L))),
        )

        assertEquals(BasisPoints.ZERO, profile.defaultTargetMargin)
        assertEquals(2, transactions.commits)
    }

    @Test
    fun `a blank name leaves the business without a name`() = runTest {
        save(BusinessProfileChange.Name("Tienda de prueba"))

        assertEquals(SaveBusinessProfileResult.Saved, save(BusinessProfileChange.Name("   ")))

        assertEquals(null, profile.businessName)
    }

    @Test
    fun `a name of more than 80 characters is rejected and an emoji counts as one`() = runTest {
        assertEquals(
            SaveBusinessProfileResult.NameTooLong,
            save(BusinessProfileChange.Name("a".repeat(BUSINESS_NAME_MAX + 1))),
        )
        assertEquals(
            SaveBusinessProfileResult.Saved,
            save(BusinessProfileChange.Name("🌟".repeat(BUSINESS_NAME_MAX))),
        )

        assertEquals(1, transactions.commits)
    }

    @Test
    fun `saving what is already there writes nothing`() = runTest {
        assertEquals(
            SaveBusinessProfileResult.Saved,
            save(BusinessProfileChange.TargetMargin(BasisPoints(3_000L))),
        )

        assertTrue(settings.profileWrites.isEmpty())
        assertEquals(0, transactions.commits)
        assertEquals(1, transactions.rollbacks)
    }

    @Test
    fun `a storage failure changes nothing`() = runTest {
        transactions.restoreInProgress = true

        assertEquals(
            SaveBusinessProfileResult.Failed(StorageFailure.RESTORE_IN_PROGRESS),
            save(BusinessProfileChange.Name("Tienda de prueba")),
        )
        assertEquals(null, profile.businessName)
    }

    @Test
    fun `observing gives the current profile`() = runTest {
        save(BusinessProfileChange.Name("Tienda de prueba"))

        assertEquals(
            BusinessProfile("Tienda de prueba", BasisPoints(3_000L)),
            ObserveBusinessProfileUseCase(settings)().first(),
        )
    }
}

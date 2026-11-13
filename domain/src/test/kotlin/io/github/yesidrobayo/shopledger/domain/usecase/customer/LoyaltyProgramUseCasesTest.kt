package io.github.yesidrobayo.shopledger.domain.usecase.customer

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.customer.PointsSummary
import io.github.yesidrobayo.shopledger.domain.model.settings.LoyaltySettings
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.rule.customer.LoyaltyProgramIssue
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Guardar y leer el programa de puntos. */
class LoyaltyProgramUseCasesTest {
    private val world = CustomerLedgerWorld()
    private val save = SaveLoyaltyProgramUseCase(world.transactions, world.settings, world.clock)
    private val observe = ObserveLoyaltyProgramUseCase(world.settings, world.customers)
    private val program = LoyaltySettings(enabled = true, Money(1_000), Money(5))

    @Test
    fun `turning it on saves both amounts in one counted transaction`() = runTest {
        assertEquals(SaveLoyaltyProgramResult.Saved, save(program))

        assertEquals(program, world.settings.settings.value.loyalty)
        assertEquals(CustomerLedgerWorld.NOW, world.settings.settings.value.updatedAt)
        assertEquals(1, world.transactions.commits)
    }

    @Test
    fun `the same program again writes nothing`() = runTest {
        save(program)

        assertEquals(SaveLoyaltyProgramResult.Saved, save(program))
        assertEquals(1, world.transactions.commits)
        assertEquals(1, world.transactions.rollbacks)
    }

    @Test
    fun `an active program without its amounts is refused and nothing changes`() = runTest {
        val before = world.settings.settings.value

        assertEquals(
            SaveLoyaltyProgramResult.Invalid(setOf(LoyaltyProgramIssue.POINT_VALUE_REQUIRED)),
            save(program.copy(pointValue = null)),
        )
        assertEquals(before, world.settings.settings.value)
    }

    @Test
    fun `a storage failure is returned typed`() = runTest {
        world.transactions.restoreInProgress = true

        assertEquals(
            SaveLoyaltyProgramResult.StorageFailed(StorageFailure.RESTORE_IN_PROGRESS),
            save(program),
        )
    }

    @Test
    fun `the overview tells the program, the points and whether there are customers`() = runTest {
        assertEquals(
            LoyaltyProgramOverview(LoyaltySettings(false, null, null), PointsSummary.NONE, false),
            observe().first(),
        )
        world.seedCustomer(1L)
        world.database.seed { tables ->
            val customer = tables.customers.getValue(1L)
            tables.copy(customers = tables.customers + (1L to customer.copy(pointsBalance = 150L)))
        }
        save(program)

        assertEquals(
            LoyaltyProgramOverview(program, PointsSummary(150L, 1), hasCustomers = true),
            observe().first(),
        )
    }

    @Test
    fun `a storage failure from the settings is returned typed too`() = runTest {
        val failing = SaveLoyaltyProgramUseCase(
            world.transactions,
            object : AppSettingsRepository by world.settings {
                override suspend fun updateLoyalty(loyalty: LoyaltySettings, updatedAt: Instant) =
                    throw StorageFailureException(StorageFailure.DISK_FULL)
            },
            world.clock,
        )

        assertEquals(
            SaveLoyaltyProgramResult.StorageFailed(StorageFailure.DISK_FULL),
            failing(program),
        )
    }
}

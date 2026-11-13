package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.customer.appendLoyaltyEntry
import io.github.yesidrobayo.shopledger.data.local.customer.loyaltyEntry
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerContact
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerDetails
import io.github.yesidrobayo.shopledger.domain.model.customer.PointsSummary
import io.github.yesidrobayo.shopledger.domain.model.settings.LoyaltySettings
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.customer.ObserveLoyaltyProgramUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.customer.SaveLoyaltyProgramResult
import io.github.yesidrobayo.shopledger.domain.usecase.customer.SaveLoyaltyProgramUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El programa de puntos en una base real: nace apagado, guardarlo cambia solo sus columnas y cuenta
 * un cambio, apagarlo conserva los montos, y nada de esto toca el dinero ni el libro del cliente.
 */
@RunWith(AndroidJUnit4::class)
internal class LoyaltySettingRepositoryTest : RepositoryTest() {
    private val settings by lazy { RoomAppSettingsRepository(database, RestoreLock { false }) }
    private val customers by lazy { RoomCustomerRepository(database) }
    private val save by lazy { SaveLoyaltyProgramUseCase(runner, settings, Clock { AT }) }

    @Test
    fun theProgramIsOffByDefault() = runBlocking {
        assertEquals(LoyaltySettings(enabled = false, null, null), settings.get().loyalty)
    }

    @Test
    fun savingChangesOnlyItsColumnsAndTurningOffKeepsTheAmounts() = runBlocking {
        val before = changeSeq()
        val row = requireNotNull(database.appSettingsDao().get())
        val program = LoyaltySettings(enabled = true, Money(1_000), Money(5))

        assertEquals(SaveLoyaltyProgramResult.Saved, save(program))

        assertEquals(
            row.copy(
                loyaltyEnabled = 1L,
                loyaltyAmountPerPoint = 1_000L,
                loyaltyPointValue = 5L,
                updatedAt = AT.toEpochMilliseconds(),
            ),
            database.appSettingsDao().get(),
        )
        assertEquals(program, settings.get().loyalty)
        assertEquals(before + 1, changeSeq())

        assertEquals(SaveLoyaltyProgramResult.Saved, save(program.copy(enabled = false)))
        assertEquals(program.copy(enabled = false), settings.get().loyalty)
        assertEquals(before + 2, changeSeq())
        // Lo que ya estaba así no escribe ni cuenta.
        assertEquals(SaveLoyaltyProgramResult.Saved, save(program.copy(enabled = false)))
        assertEquals(before + 2, changeSeq())
        // Los puntos no son dinero: nada entró en el libro de dinero ni en el del cliente.
        assertEquals(0L, count("SELECT COUNT(*) FROM money_movements"))
        assertEquals(0L, count("SELECT COUNT(*) FROM customer_ledger_entries"))
    }

    @Test
    fun theDatabaseRefusesAnActiveProgramWithoutItsAmounts() = runBlocking {
        assertEquals(
            StorageFailure.INVARIANT_VIOLATION,
            storageFailureOf {
                commit { settings.updateLoyalty(LoyaltySettings(true, Money(1_000), null), AT) }
            },
        )
        assertFalse(settings.get().loyalty.enabled)
    }

    @Test
    fun thePointsSummaryAddsOnlyPositiveBalances() = runBlocking {
        val ana = newCustomer("Ana")
        val bruno = newCustomer("Bruno")
        newCustomer("Carla")
        database.appendLoyaltyEntry(loyaltyEntry(ana, "ADJUSTMENT", 150, 150))
        database.appendLoyaltyEntry(loyaltyEntry(bruno, "ADJUSTMENT", 40, 40))
        database.appendLoyaltyEntry(loyaltyEntry(bruno, "ADJUSTMENT", -60, -20))

        assertEquals(PointsSummary(150L, 1), customers.observePointsSummary().first())
        val overview = ObserveLoyaltyProgramUseCase(settings, customers)().first()
        assertEquals(PointsSummary(150L, 1), overview.points)
        assertEquals(true, overview.hasCustomers)
    }

    private suspend fun newCustomer(name: String): Long = commit {
        val contact = CustomerContact(name, name.lowercase(), null, null, null)
        customers.insert(CustomerDetails(contact, null), AT)
    }
}

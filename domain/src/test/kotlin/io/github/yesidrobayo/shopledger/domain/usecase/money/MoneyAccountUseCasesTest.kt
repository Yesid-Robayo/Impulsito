package io.github.yesidrobayo.shopledger.domain.usecase.money

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountDraft
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountInputError
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountKind
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementType
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyOrigin
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Crear, editar, archivar y reactivar cuentas de dinero: cada una en una sola transacción, y el saldo
 * inicial como primer movimiento del libro. Nombres y montos inventados, con valores a mano.
 */
class MoneyAccountUseCasesTest {
    private val world = MoneyWorld()

    private suspend fun created(draft: MoneyAccountDraft): Long =
        (world.save()(null, draft) as SaveMoneyAccountResult.Saved).accountId

    @Test
    fun `a new account with an opening balance of 50000 has that balance and one movement`() =
        runTest {
            val id =
                created(MoneyAccountDraft("Banco Uno", MoneyAccountKind.BANK, null, Money(50_000)))

            val account = world.database.tables.value.moneyAccounts.getValue(id)
            assertEquals(Money(50_000), account.balance)
            assertEquals(MoneyWorld.BOOK, account.currency)
            val movements = world.ledger.findByAccount(id)
            assertEquals(1, movements.size)
            assertEquals(MoneyMovementType.OPENING_BALANCE, movements.single().type)
            assertEquals(Money(50_000), movements.single().balanceAfter)
            assertEquals(MoneyOrigin.None, movements.single().draft.origin)
            assertEquals("2026-03-15", movements.single().time.businessDate.toString())
            assertEquals(1, world.transactions.commits)
            assertEquals(listOf(MoneyWorld.NOW), world.settings.lockCalls)
        }

    @Test
    fun `an account without opening balance is born empty and does not lock the currency`() =
        runTest {
            val id =
                created(MoneyAccountDraft("Billetera", MoneyAccountKind.WALLET, null, Money(0)))

            assertEquals(Money.ZERO, world.database.tables.value.moneyAccounts.getValue(id).balance)
            assertTrue(world.ledger.findByAccount(id).isEmpty())
            assertTrue(world.settings.lockCalls.isEmpty())
        }

    @Test
    fun `a negative opening balance is an overdraft`() = runTest {
        val id = created(MoneyAccountDraft("Sobregiro", MoneyAccountKind.BANK, null, Money(-2_500)))

        assertEquals(Money(-2_500), world.database.tables.value.moneyAccounts.getValue(id).balance)
    }

    @Test
    fun `an account in another currency keeps its own currency`() = runTest {
        val id = created(
            MoneyAccountDraft("Divisas", MoneyAccountKind.OTHER, MoneyWorld.OTHER, Money(300)),
        )

        val account = world.database.tables.value.moneyAccounts.getValue(id)
        assertEquals(MoneyWorld.OTHER, account.currency)
        assertEquals(Money(300), account.balance)
    }

    @Test
    fun `invalid input and taken names write nothing`() = runTest {
        created(MoneyAccountDraft("Banco Uno", MoneyAccountKind.BANK))

        assertEquals(
            SaveMoneyAccountResult.Invalid(setOf(MoneyAccountInputError.NAME_EMPTY)),
            world.save()(null, MoneyAccountDraft("  ", MoneyAccountKind.BANK)),
        )
        assertEquals(
            SaveMoneyAccountResult.DuplicateName,
            world.save()(null, MoneyAccountDraft("BANCO úno", MoneyAccountKind.WALLET)),
        )
        assertEquals(2, world.database.tables.value.moneyAccounts.size)
        assertEquals(1, world.transactions.commits)
    }

    @Test
    fun `no account is born before the business currency is confirmed`() = runTest {
        val world = MoneyWorld(currencyConfirmed = false)

        assertEquals(
            SaveMoneyAccountResult.CurrencyNotConfirmed,
            world.save()(null, MoneyAccountDraft("Banco", MoneyAccountKind.BANK, null, Money(10))),
        )
        assertEquals(1, world.database.tables.value.moneyAccounts.size)
    }

    @Test
    fun `an unknown zone keeps the opening balance out and the account too`() = runTest {
        val world = MoneyWorld(zoneId = "Nowhere/Unknown")

        assertEquals(
            SaveMoneyAccountResult.UnknownTimeZone("Nowhere/Unknown"),
            world.save()(null, MoneyAccountDraft("Banco", MoneyAccountKind.BANK, null, Money(10))),
        )
        assertEquals(1, world.database.tables.value.moneyAccounts.size)
        assertTrue(world.database.tables.value.moneyMovements.isEmpty())
    }

    @Test
    fun `renaming the initial cash account and blanking it again follows the translated name`() =
        runTest {
            val cash = world.cashId
            assertEquals(
                SaveMoneyAccountResult.Saved(cash),
                world.save()(cash, MoneyAccountDraft("Caja", MoneyAccountKind.CASH)),
            )
            assertEquals(
                "Caja",
                world.database.tables.value.moneyAccounts.getValue(cash).details.name,
            )

            world.save()(cash, MoneyAccountDraft(" ", MoneyAccountKind.CASH))
            assertNull(world.database.tables.value.moneyAccounts.getValue(cash).details.name)
            assertEquals(2, world.transactions.commits)
        }

    @Test
    fun `an edit that changes nothing does not count a change and ignores the opening balance`() =
        runTest {
            val id = created(MoneyAccountDraft("Banco", MoneyAccountKind.BANK))
            val before = world.database.tables.value

            assertEquals(
                SaveMoneyAccountResult.Saved(id),
                world.save()(
                    id,
                    MoneyAccountDraft("Banco", MoneyAccountKind.BANK, null, Money(99)),
                ),
            )
            assertEquals(before, world.database.tables.value)
        }

    @Test
    fun `editing a missing account, a taken name or an empty name is rejected`() = runTest {
        val first = created(MoneyAccountDraft("Banco", MoneyAccountKind.BANK))
        val second = created(MoneyAccountDraft("Billetera", MoneyAccountKind.WALLET))

        assertEquals(
            SaveMoneyAccountResult.NotFound,
            world.save()(99, MoneyAccountDraft("Otra", MoneyAccountKind.BANK)),
        )
        assertEquals(
            SaveMoneyAccountResult.DuplicateName,
            world.save()(second, MoneyAccountDraft("banco", MoneyAccountKind.WALLET)),
        )
        assertEquals(
            SaveMoneyAccountResult.Invalid(setOf(MoneyAccountInputError.NAME_EMPTY)),
            world.save()(first, MoneyAccountDraft("", MoneyAccountKind.BANK)),
        )
        // Cambiar solo la clase guarda, y su propio nombre no choca consigo.
        assertEquals(
            SaveMoneyAccountResult.Saved(first),
            world.save()(first, MoneyAccountDraft("Banco", MoneyAccountKind.OTHER)),
        )
    }

    @Test
    fun `an archived account frees its name, and reactivating needs it back`() = runTest {
        val old = created(MoneyAccountDraft("Banco", MoneyAccountKind.BANK, null, Money(700)))

        assertEquals(MoneyAccountLifecycleResult.Done, world.archive()(old))
        assertEquals(MoneyAccountLifecycleResult.Unchanged, world.archive()(old))
        // Renombrar una archivada no choca, aunque el nombre esté libre.
        assertEquals(
            SaveMoneyAccountResult.Saved(old),
            world.save()(old, MoneyAccountDraft("Banco", MoneyAccountKind.WALLET)),
        )
        val newer = created(MoneyAccountDraft("banco", MoneyAccountKind.BANK))

        assertEquals(MoneyAccountLifecycleResult.NameTaken, world.reactivate()(old))
        world.save()(newer, MoneyAccountDraft("Banco nuevo", MoneyAccountKind.BANK))
        assertEquals(MoneyAccountLifecycleResult.Done, world.reactivate()(old))
        assertEquals(MoneyAccountLifecycleResult.Unchanged, world.reactivate()(old))
        assertEquals(MoneyAccountLifecycleResult.NotFound, world.archive()(99))
        assertEquals(MoneyAccountLifecycleResult.NotFound, world.reactivate()(99))
        // El saldo sigue igual: archivar no mueve dinero.
        assertEquals(Money(700), world.database.tables.value.moneyAccounts.getValue(old).balance)
    }

    @Test
    fun `the initial cash account without a name reactivates without clashing`() = runTest {
        world.archive()(world.cashId)

        assertEquals(MoneyAccountLifecycleResult.Done, world.reactivate()(world.cashId))
    }

    @Test
    fun `a storage failure leaves nothing and returns its reason`() = runTest {
        world.transactions.restoreInProgress = true

        assertEquals(
            SaveMoneyAccountResult.StorageFailed(StorageFailure.RESTORE_IN_PROGRESS),
            world.save()(null, MoneyAccountDraft("Banco", MoneyAccountKind.BANK)),
        )
        assertEquals(
            MoneyAccountLifecycleResult.StorageFailed(StorageFailure.RESTORE_IN_PROGRESS),
            world.archive()(world.cashId),
        )
        assertEquals(
            MoneyAccountLifecycleResult.StorageFailed(StorageFailure.RESTORE_IN_PROGRESS),
            world.reactivate()(world.cashId),
        )
    }

    @Test
    fun `the overview adds only the accounts in the business currency`() = runTest {
        created(MoneyAccountDraft("Banco", MoneyAccountKind.BANK, null, Money(12_000)))
        created(
            MoneyAccountDraft("Divisas", MoneyAccountKind.OTHER, MoneyWorld.OTHER, Money(9_999)),
        )
        val archived = created(MoneyAccountDraft("Vieja", MoneyAccountKind.BANK, null, Money(1)))
        world.archive()(archived)

        val overview = world.overview()().first()
        assertEquals(Money(12_000), overview.available)
        assertEquals(listOf(world.cashId, 2L), overview.inBookCurrency.map { it.id })
        assertEquals(listOf(3L), overview.inOtherCurrencies.map { it.id })
        assertEquals(MoneyWorld.BOOK, overview.bookCurrency)
        assertEquals(1, world.movements()(2L).first().size)
    }
}

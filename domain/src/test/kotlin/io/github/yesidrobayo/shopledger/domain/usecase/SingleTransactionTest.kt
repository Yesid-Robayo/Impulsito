package io.github.yesidrobayo.shopledger.domain.usecase

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.purchase.NewPurchase
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLine
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.testing.FakePurchaseRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeStockLedgerRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeTransactionRunner
import io.github.yesidrobayo.shopledger.domain.testing.FixedClock
import io.github.yesidrobayo.shopledger.domain.testing.InMemoryDatabase
import io.github.yesidrobayo.shopledger.domain.testing.InjectedFailure
import io.github.yesidrobayo.shopledger.domain.testing.ProductStockHeader
import io.github.yesidrobayo.shopledger.domain.testing.Tables
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Un caso de uso abre una sola transacción, y nada de lo que escribe queda si falla o se rechaza.
 *
 * Se prueba con un caso de uso falso y una base en memoria. Con la base real lo prueba `:data`.
 */
class SingleTransactionTest {
    private val database = InMemoryDatabase()
    private val transactions = FakeTransactionRunner(database)
    private val purchases = FakePurchaseRepository(database)
    private val ledger = FakeStockLedgerRepository(database)
    private val register = FakeRegisterPurchaseUseCase(
        transactions = transactions,
        purchases = purchases,
        ledger = ledger,
        clock = FixedClock.atEpochMillis(1_700_000_000_000L),
        zone = requireNotNull(BusinessTimeZone.of("UTC")),
    )

    // Dos líneas del mismo producto y una de otro: cabecera, tres líneas y tres filas del libro.
    private val lines = listOf(
        RequestedLine(productId = 1L, quantity = QuantityMilli(2_000L), total = Money(800L)),
        RequestedLine(productId = 1L, quantity = QuantityMilli(1_000L), total = Money(400L)),
        RequestedLine(productId = 2L, quantity = QuantityMilli(500L), total = Money(150L)),
    )

    @Test
    fun `a purchase opens exactly one transaction and confirms every write`() = runTest {
        val result = register(lines)

        assertEquals(FakePurchaseResult.Success(purchaseId = 1L), result)
        assertEquals(1, transactions.writeCalls)
        assertEquals(1, transactions.commits)
        assertEquals(0, transactions.rollbacks)
        val tables = database.tables.value
        assertEquals(1L, tables.changeSeq)
        assertEquals(listOf(1L), tables.purchases.keys.toList())
        assertEquals(3, tables.purchaseLines.size)
        assertEquals(3, tables.movements.size)
        // La segunda fila del producto 1 vio la primera, aunque la transacción no estaba confirmada.
        assertEquals(
            ProductStockHeader(QuantityMilli(3_000L), Money.ZERO, Money(1_200L)),
            tables.productStock[1L],
        )
        assertEquals(
            ProductStockHeader(QuantityMilli(500L), Money.ZERO, Money(150L)),
            tables.productStock[2L],
        )
        assertEquals(3, ledger.findByPurchase(1L).size)
    }

    @Test
    fun `an uncounted commit confirms every write and counts no change`() = runTest {
        register(lines)
        val counted = database.tables.value

        // Como guardar una venta en espera: queda escrita, pero no es un cambio sin respaldar.
        val purchaseId =
            transactions.write { TxResult.CommitUncounted(purchases.insert(samplePurchase())) }

        assertEquals(2, transactions.writeCalls)
        assertEquals(1, transactions.commits)
        assertEquals(1, transactions.uncountedCommits)
        assertEquals(0, transactions.rollbacks)
        val tables = database.tables.value
        assertEquals(counted.changeSeq, tables.changeSeq)
        assertEquals(listOf(1L, purchaseId), tables.purchases.keys.toList())
    }

    @Test
    fun `a failure inside an uncounted write leaves nothing`() = runTest {
        // Cada compra escribe dos veces, su id y su fila: falla la última de la segunda.
        database.failAtWrite = 4

        val failure = failureOf {
            transactions.write {
                purchases.insert(samplePurchase())
                purchases.insert(samplePurchase())
                TxResult.CommitUncounted(Unit)
            }
        }

        assertTrue(failure is InjectedFailure)
        assertEquals(0, transactions.uncountedCommits)
        assertEquals(1, transactions.rollbacks)
        assertEquals(Tables(), database.tables.value)
    }

    @Test
    fun `a restore in progress rejects an uncounted write before it writes`() = runTest {
        transactions.restoreInProgress = true

        val failure = failureOf {
            transactions.write<Long> {
                TxResult.CommitUncounted(purchases.insert(samplePurchase()))
            }
        }

        assertEquals(
            StorageFailure.RESTORE_IN_PROGRESS,
            (failure as StorageFailureException).failure,
        )
        assertEquals(0, transactions.uncountedCommits)
        assertEquals(Tables(), database.tables.value)
    }

    @Test
    fun `a business rejection opens one transaction and leaves nothing`() = runTest {
        assertEquals(FakePurchaseResult.EmptyPurchase, register(emptyList()))
        assertEquals(
            FakePurchaseResult.TotalExceedsLimit,
            register(listOf(RequestedLine(1L, QuantityMilli(1_000L), Money(Long.MAX_VALUE)))),
        )

        assertEquals(2, transactions.writeCalls)
        assertEquals(0, transactions.commits)
        assertEquals(2, transactions.rollbacks)
        assertEquals(Tables(), database.tables.value)
    }

    @Test
    fun `a programming error at any write leaves nothing`() = runTest {
        val writes = writesOfOnePurchase()
        for (step in 1..writes) {
            val fresh = Fixture()
            fresh.database.failAtWrite = step

            assertTrue("paso $step", failureOf { fresh.register(lines) } is InjectedFailure)

            assertEquals("paso $step", 1, fresh.transactions.writeCalls)
            assertEquals("paso $step", 1, fresh.transactions.rollbacks)
            assertEquals("paso $step", Tables(), fresh.database.tables.value)
        }
    }

    @Test
    fun `a storage failure at any write becomes a result and leaves nothing`() = runTest {
        val writes = writesOfOnePurchase()
        for (step in 1..writes) {
            val fresh = Fixture()
            fresh.database.failAtWrite = step
            fresh.database.failure = { StorageFailureException(StorageFailure.DISK_FULL) }

            assertEquals(
                "paso $step",
                FakePurchaseResult.StorageFailed(StorageFailure.DISK_FULL),
                fresh.register(lines),
            )
            assertEquals("paso $step", 1, fresh.transactions.writeCalls)
            assertEquals("paso $step", Tables(), fresh.database.tables.value)
        }
    }

    @Test
    fun `a restore in progress rejects the purchase before it writes`() = runTest {
        transactions.restoreInProgress = true

        assertEquals(
            FakePurchaseResult.StorageFailed(StorageFailure.RESTORE_IN_PROGRESS),
            register(lines),
        )
        assertEquals(1, transactions.writeCalls)
        assertEquals(0, transactions.commits)
        assertEquals(Tables(), database.tables.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `an observer only ever sees confirmed purchases`() = runTest {
        val seen = mutableListOf<List<PurchaseLine>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            purchases.observeLines(purchaseId = 1L).toList(seen)
        }

        database.failAtWrite = 4
        assertTrue(failureOf { register(lines) } is InjectedFailure)
        database.failAtWrite = null
        register(lines)

        // Antes de nada, y después de confirmar: nunca una compra a medias.
        assertEquals(listOf(0, 3), seen.map { it.size })
    }

    @Test
    fun `a repository write outside a transaction is a programming error`() = runTest {
        val failure = failureOf { purchases.insert(samplePurchase()) }

        assertTrue(failure is IllegalStateException)
        assertEquals(Tables(), database.tables.value)
    }

    @Test
    fun `a transaction never opens inside another`() = runTest {
        val failure = failureOf {
            transactions.write { TxResult.Commit(transactions.write { TxResult.Commit(Unit) }) }
        }

        assertTrue(failure is IllegalStateException)
        assertEquals(Tables(), database.tables.value)
    }

    /** La excepción que lanza [block], o `null` si termina bien. */
    private suspend fun failureOf(block: suspend () -> Unit): Throwable? = try {
        block()
        null
    } catch (failure: Throwable) {
        failure
    }

    /** Cuántas escrituras hace una compra que sale bien: la prueba falla en cada una. */
    private suspend fun writesOfOnePurchase(): Int {
        val probe = Fixture()
        probe.register(lines)
        return probe.database.lastTransactionWrites.also { assertTrue(it > lines.size) }
    }

    private fun samplePurchase(): NewPurchase {
        val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)
        return NewPurchase(
            supplierId = null,
            invoiceReference = null,
            netTotal = Money.ZERO,
            taxTotal = Money.ZERO,
            total = Money.ZERO,
            costTotal = Money.ZERO,
            amountPaid = Money.ZERO,
            moneyAccountId = null,
            dueDate = null,
            locationId = 1L,
            foreignCurrency = null,
            occurredAt = now,
            businessDate = BusinessDate.of(now, requireNotNull(BusinessTimeZone.of("UTC"))),
            createdAt = now,
        )
    }

    /** Una base y un caso de uso nuevos, para que cada paso de la falla empiece de cero. */
    private class Fixture {
        val database = InMemoryDatabase()
        val transactions = FakeTransactionRunner(database)
        val register = FakeRegisterPurchaseUseCase(
            transactions = transactions,
            purchases = FakePurchaseRepository(database),
            ledger = FakeStockLedgerRepository(database),
            clock = FixedClock.atEpochMillis(1_700_000_000_000L),
            zone = requireNotNull(BusinessTimeZone.of("UTC")),
        )
    }
}

package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.TEST_BUSINESS_CURRENCY
import io.github.yesidrobayo.shopledger.data.local.TEST_BUSINESS_DECIMALS
import io.github.yesidrobayo.shopledger.data.local.catalog.CatalogDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.MUCH_LATER
import io.github.yesidrobayo.shopledger.data.transaction.BackupStateChangeCounter
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.data.transaction.RoomTransactionRunner
import io.github.yesidrobayo.shopledger.data.transaction.runnerFor
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLockResult
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El bloqueo de la moneda en una base real que nace sin moneda: el primer registro con dinero la
 * bloquea en su misma transacción, un registro deshecho no deja bloqueo, y sin moneda no se guarda
 * nada. La moneda es `XTS`, la de prueba de ISO 4217.
 */
@RunWith(AndroidJUnit4::class)
internal class CurrencyLockRepositoryTest : CatalogDatabaseTest() {
    override val confirmsCurrency: Boolean = false

    private lateinit var runner: RoomTransactionRunner
    private val settings by lazy { RoomAppSettingsRepository(database, RestoreLock { false }) }
    private val products by lazy { RoomProductRepository(database) }
    private val lock by lazy { CurrencyLock(settings) }
    private val at = Instant.fromEpochMilliseconds(LATER)

    @Before
    fun createRunner() {
        runner = runnerFor(database, BackupStateChangeCounter(database.backupStateDao()))
    }

    private suspend fun confirmCurrency() {
        val dao = database.appSettingsDao()
        check(dao.updateCurrency(TEST_BUSINESS_CURRENCY, TEST_BUSINESS_DECIMALS, LATER) == 1)
    }

    private suspend fun lockedAt(): Long? =
        requireNotNull(database.appSettingsDao().get()).currencyLockedAt

    private suspend fun productCount(): Long = count("SELECT COUNT(*) FROM products")

    private suspend fun changeSeq(): Long =
        requireNotNull(database.backupStateDao().get()).changeSeq

    /** Lo que hace un caso de uso con dinero: bloquear y escribir, en una sola transacción. */
    private suspend fun saveProductWithPrice(name: String): CurrencyLockResult = runner.write {
        when (val result = lock.lockForMoneyRecord(at)) {
            CurrencyLockResult.Unconfirmed -> TxResult.Rollback(result)

            is CurrencyLockResult.Locked -> {
                products.insert(newProduct(name))
                TxResult.Commit(result)
            }
        }
    }

    @Test
    fun withoutACurrencyNothingIsSavedNorLocked() = runBlocking {
        val seq = changeSeq()

        assertEquals(CurrencyLockResult.Unconfirmed, saveProductWithPrice("Té verde"))

        assertNull(lockedAt())
        assertEquals(0L, productCount())
        assertEquals(seq, changeSeq())
    }

    @Test
    fun theFirstRecordWithMoneyLocksTheCurrencyInItsOwnTransaction() = runBlocking {
        confirmCurrency()
        val seq = changeSeq()

        val result = saveProductWithPrice("Té verde")

        assertEquals(LATER, lockedAt())
        assertEquals(1L, productCount())
        assertEquals(seq + 1, changeSeq())
        val currency = (result as CurrencyLockResult.Locked).currency
        assertEquals(TEST_BUSINESS_CURRENCY, currency.code.code)
        assertEquals(TEST_BUSINESS_DECIMALS.toInt(), currency.decimals)
        assertEquals(at, currency.lockedAt)
    }

    @Test
    fun laterRecordsKeepTheFirstLock() = runBlocking {
        confirmCurrency()
        saveProductWithPrice("Té verde")

        val later =
            runner.write {
                val result = lock.lockForMoneyRecord(Instant.fromEpochMilliseconds(MUCH_LATER))
                products.insert(newProduct("Té negro"))
                TxResult.Commit(result)
            }

        assertEquals(LATER, lockedAt())
        assertEquals(at, (later as CurrencyLockResult.Locked).currency.lockedAt)
        assertFalse(runner.write { TxResult.Commit(settings.lockCurrency(at)) })
    }

    @Test
    fun aRecordUndoneTakesItsLockWithIt() = runBlocking {
        confirmCurrency()

        runner.write {
            lock.lockForMoneyRecord(at)
            TxResult.Rollback(Unit)
        }
        assertNull(lockedAt())

        try {
            runner.write<Unit> {
                lock.lockForMoneyRecord(at)
                error("Falla inyectada después del bloqueo")
            }
            fail("La falla debía llegar a quien llamó")
        } catch (_: IllegalStateException) {
            // Lo esperado: la transacción se deshizo entera.
        }
        assertNull(lockedAt())
    }

    @Test
    fun beforeTheLockTheGuardRejectsAPriceAndAfterItAccepts() = runBlocking {
        confirmCurrency()

        val refused =
            storageFailureOf {
                runner.write {
                    products.insert(newProduct("Té verde"))
                    TxResult.Commit(Unit)
                }
            }
        assertEquals(StorageFailure.INVARIANT_VIOLATION, refused)
        // Un producto sin dinero no necesita moneda: no se bloquea nada.
        runner.write {
            TxResult.Commit(
                products.insert(
                    newProduct("Té blanco").copy(salePrice = Money.ZERO, initialCost = Money.ZERO),
                ),
            )
        }
        assertNull(lockedAt())

        saveProductWithPrice("Té verde")
        assertEquals(2L, productCount())
    }

    @Test
    fun theDatabaseNeverLocksAnUnconfirmedCurrency() = runBlocking {
        val refused =
            storageFailureOf {
                runner.write {
                    settings.lockCurrency(at)
                    TxResult.Commit(Unit)
                }
            }

        assertEquals(StorageFailure.INVARIANT_VIOLATION, refused)
        assertNull(lockedAt())
    }

    @Test
    fun outsideATransactionTheLockIsNotWritten() = runBlocking {
        confirmCurrency()
        try {
            settings.lockCurrency(at)
            fail("Fuera de la transacción del registro no debía bloquear")
        } catch (_: IllegalStateException) {
            // Lo esperado: el bloqueo va siempre con su registro.
        }
        assertTrue(lockedAt() == null)
    }
}

package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.catalog.CatalogDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.MUCH_LATER
import io.github.yesidrobayo.shopledger.data.transaction.BackupStateChangeCounter
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.data.transaction.RoomTransactionRunner
import io.github.yesidrobayo.shopledger.data.transaction.runnerFor
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.settings.CsvSeparator
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.model.settings.RegionalSettingsInput
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.region.RegionalSettingsProblem
import io.github.yesidrobayo.shopledger.domain.usecase.region.SaveRegionalSettingsResult
import io.github.yesidrobayo.shopledger.domain.usecase.region.SaveRegionalSettingsUseCase
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guardar la configuración regional en una base real que nace sin moneda: una sola transacción que
 * cuenta un cambio, la cuenta de efectivo inicial con la misma moneda hasta el bloqueo, y las
 * guardas que impiden cambiar la moneda después. Las monedas son los códigos de prueba de ISO 4217.
 */
@RunWith(AndroidJUnit4::class)
internal class RegionalSettingsRepositoryTest : CatalogDatabaseTest() {
    override val confirmsCurrency: Boolean = false

    private lateinit var runner: RoomTransactionRunner
    private val settings by lazy { RoomAppSettingsRepository(database, RestoreLock { false }) }
    private val save by lazy {
        SaveRegionalSettingsUseCase(
            runner,
            settings,
            Clock {
                Instant.fromEpochMilliseconds(LATER)
            },
        )
    }

    private val chosen =
        RegionalSettingsInput(
            currencyCode = requireNotNull(CurrencyCode.of("XTS")),
            currencyDecimals = 3,
            currencySymbol = "¤",
            cashRounding = Money(50),
            formatLocaleTag = "pt-CV",
            businessTimeZoneId = "Etc/GMT-9",
            weekStartDay = DayOfWeek.SATURDAY,
            csvSeparator = CsvSeparator.TAB,
        )

    @Before
    fun createRunner() {
        runner = runnerFor(database, BackupStateChangeCounter(database.backupStateDao()))
    }

    private suspend fun changeSeq(): Long =
        requireNotNull(database.backupStateDao().get()).changeSeq

    private suspend fun cashAccount() =
        requireNotNull(database.moneyAccountDao().findBySystemKey("CASH"))

    @Test
    fun confirmingWritesEveryRegionalColumnAndTheCashAccountCurrency() = runBlocking {
        val before = requireNotNull(database.appSettingsDao().get())
        val seqBefore = changeSeq()

        assertEquals(SaveRegionalSettingsResult.Saved, save(chosen))

        val after = requireNotNull(database.appSettingsDao().get())
        assertEquals(
            before.copy(
                currencyCode = "XTS",
                currencyDecimals = 3,
                currencySymbol = "¤",
                cashRounding = 50,
                formatLocale = "pt-CV",
                businessTimeZone = "Etc/GMT-9",
                weekStartDay = 6,
                csvSeparator = "TAB",
                updatedAt = LATER,
            ),
            after,
        )
        assertEquals(seqBefore + 1, changeSeq())
        val cash = cashAccount()
        assertEquals("XTS", cash.currencyCode)
        assertEquals(3L, cash.currencyDecimals)
    }

    @Test
    fun confirmingTheSameValuesTwiceCountsOneChange() = runBlocking {
        save(chosen)
        val seq = changeSeq()

        assertEquals(SaveRegionalSettingsResult.Saved, save(chosen))

        assertEquals(seq, changeSeq())
    }

    @Test
    fun afterTheLockOnlyTheSymbolRoundingAndFormatsChange() = runBlocking {
        save(chosen)
        assertEquals(1, database.appSettingsDao().lockCurrency(MUCH_LATER))

        val other = chosen.copy(currencyCode = requireNotNull(CurrencyCode.of("XTT")))
        assertEquals(
            SaveRegionalSettingsResult.Rejected(setOf(RegionalSettingsProblem.CURRENCY_LOCKED)),
            save(other),
        )
        val editable =
            chosen.copy(
                currencySymbol = null,
                cashRounding = Money.ZERO,
                formatLocaleTag = "es-419",
                businessTimeZoneId = "Etc/GMT+5",
                weekStartDay = DayOfWeek.MONDAY,
                csvSeparator = CsvSeparator.SEMICOLON,
            )
        assertEquals(SaveRegionalSettingsResult.Saved, save(editable))

        val after = requireNotNull(database.appSettingsDao().get())
        assertEquals("XTS", after.currencyCode)
        assertEquals(3L, after.currencyDecimals)
        assertEquals(null, after.currencySymbol)
        assertEquals("es-419", after.formatLocale)
        assertEquals(MUCH_LATER, after.currencyLockedAt)
    }

    @Test
    fun theGuardRejectsAnotherCurrencyAfterTheLockEvenWithoutTheRule() = runBlocking {
        save(chosen)
        database.appSettingsDao().lockCurrency(MUCH_LATER)
        val seq = changeSeq()

        val failure =
            storageFailureOf {
                runner.write {
                    settings.updateRegional(
                        chosen.copy(currencyDecimals = 2),
                        Instant.fromEpochMilliseconds(MUCH_LATER),
                    )
                    TxResult.Commit(Unit)
                }
            }

        assertEquals(StorageFailure.INVARIANT_VIOLATION, failure)
        assertEquals(3L, requireNotNull(database.appSettingsDao().get()).currencyDecimals)
        assertEquals(seq, changeSeq())
    }

    @Test
    fun outsideATransactionNothingIsWritten() = runBlocking {
        try {
            settings.updateRegional(chosen, Instant.fromEpochMilliseconds(LATER))
            fail("Fuera de la transacción del caso de uso no debía escribir")
        } catch (_: IllegalStateException) {
            // Lo esperado: la escritura exige la transacción abierta.
        }
        assertEquals("XXX", requireNotNull(database.appSettingsDao().get()).currencyCode)
    }
}

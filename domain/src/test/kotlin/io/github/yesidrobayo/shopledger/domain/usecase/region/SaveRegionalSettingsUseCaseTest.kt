package io.github.yesidrobayo.shopledger.domain.usecase.region

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.settings.CsvSeparator
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.model.settings.RegionalSettingsInput
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.rule.region.RegionalSettingsProblem
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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guardar la configuración regional: una sola transacción, que relee la moneda guardada y respeta
 * su bloqueo, y que no escribe ni cuenta un cambio si nada cambió.
 */
class SaveRegionalSettingsUseCaseTest {
    private val now = Instant.fromEpochMilliseconds(1_793_593_800_000)
    private val xts = requireNotNull(CurrencyCode.of("XTS"))
    private val xtt = requireNotNull(CurrencyCode.of("XTT"))

    // El negocio nace sin moneda, como en una instalación nueva.
    private val born =
        regionalSettings("Etc/GMT+5", DayOfWeek.MONDAY).let {
            it.copy(currency = it.currency.copy(code = CurrencyCode.UNCONFIRMED, decimals = 0))
        }
    private val repository = FakeAppSettingsRepository(appSettings(born))
    private val transactions = FakeTransactionRunner(InMemoryDatabase())
    private val save = SaveRegionalSettingsUseCase(transactions, repository, FixedClock(now))

    private val chosen =
        RegionalSettingsInput(
            currencyCode = xts,
            currencyDecimals = 2,
            currencySymbol = " ¤ ",
            cashRounding = Money(5),
            formatLocaleTag = "es-419",
            businessTimeZoneId = "Etc/GMT-9",
            weekStartDay = DayOfWeek.SUNDAY,
            csvSeparator = CsvSeparator.COMMA,
        )

    @Test
    fun `confirming writes everything in one counted transaction`() = runTest {
        assertEquals(SaveRegionalSettingsResult.Saved, save(chosen))

        assertEquals(listOf(chosen.copy(currencySymbol = "¤") to now), repository.regionalWrites)
        assertEquals(1, transactions.writeCalls)
        assertEquals(1, transactions.commits)
        val regional = repository.observe().first().regional
        assertEquals(xts, regional.currency.code)
        assertEquals(2, regional.currency.decimals)
        assertEquals("¤", regional.currency.symbol)
        assertEquals("Etc/GMT-9", regional.businessTimeZoneId)
    }

    @Test
    fun `confirming the same values again writes nothing and counts no change`() = runTest {
        save(chosen)

        assertEquals(SaveRegionalSettingsResult.Saved, save(chosen))

        assertEquals(1, repository.regionalWrites.size)
        assertEquals(1, transactions.commits)
        assertEquals(1, transactions.rollbacks)
    }

    @Test
    fun `each field alone counts as a change`() = runTest {
        save(chosen)
        val changes =
            listOf(
                chosen.copy(currencyCode = xtt),
                chosen.copy(currencyCode = xtt, currencyDecimals = 3),
                chosen.copy(currencyCode = xtt, currencyDecimals = 3, currencySymbol = null),
                chosen.copy(currencyCode = xtt, currencyDecimals = 3, cashRounding = Money(10)),
            )
        changes.forEach { assertEquals(SaveRegionalSettingsResult.Saved, save(it)) }
        val saved = changes.last()
        listOf(
            saved.copy(formatLocaleTag = "pt"),
            saved.copy(formatLocaleTag = "pt", businessTimeZoneId = "Etc/GMT+5"),
            saved.copy(
                formatLocaleTag = "pt",
                businessTimeZoneId = "Etc/GMT+5",
                weekStartDay = DayOfWeek.MONDAY,
            ),
            saved.copy(
                formatLocaleTag = "pt",
                businessTimeZoneId = "Etc/GMT+5",
                weekStartDay = DayOfWeek.MONDAY,
                csvSeparator = CsvSeparator.TAB,
            ),
        ).forEach { assertEquals(SaveRegionalSettingsResult.Saved, save(it)) }

        assertEquals(9, repository.regionalWrites.size)
        assertEquals(9, transactions.commits)
    }

    @Test
    fun `without a currency nothing is written`() = runTest {
        val result = save(chosen.copy(currencyCode = CurrencyCode.UNCONFIRMED))

        assertEquals(
            SaveRegionalSettingsResult.Rejected(
                setOf(RegionalSettingsProblem.CURRENCY_UNCONFIRMED),
            ),
            result,
        )
        assertTrue(repository.regionalWrites.isEmpty())
        assertEquals(0, transactions.commits)
        assertEquals(1, transactions.rollbacks)
    }

    @Test
    fun `the lock is read inside the same transaction`() = runTest {
        save(chosen)
        // Un registro con dinero bloqueó la moneda entre la lectura de la pantalla y el guardado.
        val current = repository.settings.value
        repository.settings.value =
            current.copy(
                regional =
                    current.regional.copy(
                        currency = current.regional.currency.copy(lockedAt = now),
                    ),
            )

        val result = save(chosen.copy(currencyDecimals = 3))

        assertEquals(
            SaveRegionalSettingsResult.Rejected(setOf(RegionalSettingsProblem.CURRENCY_LOCKED)),
            result,
        )
        assertEquals(1, repository.regionalWrites.size)
        assertEquals(
            SaveRegionalSettingsResult.Saved,
            save(chosen.copy(cashRounding = Money(50))),
        )
    }

    @Test
    fun `a storage failure is returned as such`() = runTest {
        transactions.restoreInProgress = true

        assertEquals(
            SaveRegionalSettingsResult.Failed(StorageFailure.RESTORE_IN_PROGRESS),
            save(chosen),
        )
        assertTrue(repository.regionalWrites.isEmpty())
    }

    @Test
    fun `observing gives only regional changes`() = runTest {
        val observe = ObserveRegionalSettingsUseCase(repository)
        assertEquals(born, observe().first())

        save(chosen)

        assertEquals("es-419", observe().first().formatLocaleTag)
    }
}

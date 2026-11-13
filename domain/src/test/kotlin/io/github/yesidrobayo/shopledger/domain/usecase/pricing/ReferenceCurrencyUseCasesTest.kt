package io.github.yesidrobayo.shopledger.domain.usecase.pricing

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.pricing.FxRate
import io.github.yesidrobayo.shopledger.domain.model.pricing.NewFxRate
import io.github.yesidrobayo.shopledger.domain.model.settings.AppSettings
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.model.settings.ReferenceCurrency
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.FxRateRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.pricing.ReferenceRateRules
import io.github.yesidrobayo.shopledger.domain.testing.FakeAppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.testing.FixedClock
import io.github.yesidrobayo.shopledger.domain.testing.appSettings
import io.github.yesidrobayo.shopledger.domain.testing.regionalSettings
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Elegir la moneda de referencia y escribir su cotización: solo agrega, la fecha es la del negocio,
 * nada se recalcula solo y una falla en cualquier escritura no deja nada. Códigos y cotizaciones
 * inventados.
 */
class ReferenceCurrencyUseCasesTest {
    private val business = requireNotNull(CurrencyCode.of("XTS"))
    private val other = requireNotNull(CurrencyCode.of("XXX"))
    private val reference = ReferenceCurrency(other, 2)
    private val now = Instant.fromEpochMilliseconds(1_793_631_600_000L)

    private val settings = FakeAppSettingsRepository(
        appSettings(regionalSettings("Etc/GMT+5", DayOfWeek.MONDAY)),
    )
    private val rates = FakeRates()
    private val transactions = SnapshotRunner()
    private val clock = FixedClock(now)

    /** Los ajustes, con su escritura contada por la transacción para poder hacerla fallar. */
    private val counted = object : AppSettingsRepository by settings {
        override suspend fun updateReferenceCurrency(
            reference: ReferenceCurrency?,
            updatedAt: Instant,
        ) {
            transactions.write()
            settings.updateReferenceCurrency(reference, updatedAt)
        }
    }
    private val setReference = SetReferenceCurrencyUseCase(transactions, counted, clock)
    private val record = RecordReferenceRateUseCase(transactions, counted, rates, clock)
    private val observe = ObserveReferenceRateUseCase(counted, rates)

    @Test
    fun `choosing a reference currency saves it once, and clearing it keeps its rates`() = runTest {
        assertEquals(SetReferenceCurrencyResult.Saved, setReference(reference))
        assertEquals(reference, settings.settings.value.regional.referenceCurrency)
        assertEquals(now, settings.settings.value.updatedAt)
        assertEquals(1, transactions.commits)

        // La misma otra vez no escribe.
        assertEquals(SetReferenceCurrencyResult.Saved, setReference(reference))
        assertEquals(1, transactions.commits)

        record(4_150_500_000, null)
        assertEquals(SetReferenceCurrencyResult.Saved, setReference(null))
        assertNull(settings.settings.value.regional.referenceCurrency)
        assertEquals(1, rates.rows.value.size)
    }

    @Test
    fun `the business currency or bad decimals are rejected without writing`() = runTest {
        assertEquals(
            SetReferenceCurrencyResult.Rejected(ReferenceRateRules.ReferenceIssue.SAME_AS_BUSINESS),
            setReference(ReferenceCurrency(business, 2)),
        )
        assertEquals(
            SetReferenceCurrencyResult.Rejected(
                ReferenceRateRules.ReferenceIssue.DECIMALS_OUT_OF_RANGE,
            ),
            setReference(ReferenceCurrency(other, 7)),
        )
        assertNull(settings.settings.value.regional.referenceCurrency)
        assertEquals(0, transactions.commits)
    }

    @Test
    fun `a rate is appended with the business date, and the old one stays`() = runTest {
        setReference(reference)

        val first = record(4_150_500_000, "  Casa de cambio ")
        val second = record(4_200_000_000, null)

        assertEquals(RecordReferenceRateResult.Recorded(1), first)
        assertEquals(RecordReferenceRateResult.Recorded(2), second)
        val saved = rates.rows.value
        assertEquals(listOf(4_150_500_000, 4_200_000_000), saved.map { it.content.rateMicro })
        assertEquals("Casa de cambio", saved.first().content.note)
        assertEquals(other, saved.first().content.currency)
        // Las 15:00 UTC del 2026-11-02 son las 10:00 del mismo día en la zona del negocio.
        assertEquals(BusinessDate(LocalDate(2026, 11, 2)), saved.first().content.businessDate)
        assertEquals(now, saved.first().content.occurredAt)
    }

    @Test
    fun `without a reference currency, a bad rate or a long note nothing is written`() = runTest {
        assertEquals(RecordReferenceRateResult.NoReferenceCurrency, record(1, null))
        setReference(reference)
        assertEquals(RecordReferenceRateResult.RateOutOfRange, record(0, null))
        assertEquals(
            RecordReferenceRateResult.NoteTooLong,
            record(1, "a".repeat(ReferenceRateRules.NOTE_MAX + 1)),
        )
        assertTrue(rates.rows.value.isEmpty())
        assertEquals(
            RecordReferenceRateResult.Recorded(1),
            record(1, "a".repeat(ReferenceRateRules.NOTE_MAX)),
        )
    }

    @Test
    fun `a past date goes at noon, and a future one is rejected`() = runTest {
        setReference(reference)

        val yesterday = BusinessDate(LocalDate(2026, 11, 1))
        assertEquals(RecordReferenceRateResult.Recorded(1), record(1, null, yesterday))
        assertEquals(yesterday, rates.rows.value.single().content.businessDate)
        // Las 12:00 en la zona del negocio, cinco horas detrás de UTC.
        assertEquals(
            Instant.parse("2026-11-01T17:00:00Z"),
            rates.rows.value.single().content.occurredAt,
        )
        assertEquals(
            RecordReferenceRateResult.FutureDate,
            record(1, null, BusinessDate(LocalDate(2026, 11, 3))),
        )
        assertEquals(
            RecordReferenceRateResult.Recorded(2),
            record(1, null, BusinessDate(LocalDate(2026, 11, 2))),
        )
        assertEquals(now, rates.rows.value.last().content.occurredAt)
    }

    @Test
    fun `an unknown zone cannot date the rate`() = runTest {
        settings.settings.value = appSettings(regionalSettings("Invented/Zone", DayOfWeek.MONDAY))
            .withReference(reference)

        assertEquals(RecordReferenceRateResult.UnknownTimeZone, record(1, null))
        assertTrue(rates.rows.value.isEmpty())
    }

    @Test
    fun `a failure at each write leaves nothing, and a storage failure comes back`() = runTest {
        transactions.failAtWrite = 1
        assertEquals(
            SetReferenceCurrencyResult.Failed(StorageFailure.DISK_FULL),
            setReference(reference),
        )
        assertNull(settings.settings.value.regional.referenceCurrency)

        transactions.failAtWrite = null
        setReference(reference)
        transactions.failAtWrite = 1
        assertEquals(
            RecordReferenceRateResult.Failed(StorageFailure.DISK_FULL),
            record(4_150_500_000, null),
        )
        assertTrue(rates.rows.value.isEmpty())
    }

    @Test
    fun `the view follows the reference currency, its latest rate and its history`() = runTest {
        assertEquals(ReferenceRateView(null, null, emptyList()), observe().first())

        setReference(reference)
        record(4_150_500_000, null)
        record(4_200_000_000, null)
        val view = observe().first()

        assertEquals(reference, view.reference)
        assertEquals(4_200_000_000, view.latest?.content?.rateMicro)
        assertEquals(listOf(2L, 1L), view.history.map { it.id })
    }

    private fun AppSettings.withReference(value: ReferenceCurrency) =
        copy(regional = regional.copy(referenceCurrency = value))

    /** Cotizaciones en memoria, que la transacción de prueba guarda y restaura. */
    private inner class FakeRates : FxRateRepository {
        val rows = MutableStateFlow(emptyList<FxRate>())

        override fun observeLatest(currency: CurrencyCode): Flow<FxRate?> =
            observeHistory(currency).map { it.firstOrNull() }

        override fun observeHistory(currency: CurrencyCode): Flow<List<FxRate>> = rows.map { all ->
            all.filter { it.content.currency == currency }.sortedByDescending { it.id }
        }

        override suspend fun append(rate: NewFxRate): Long {
            transactions.write()
            val id = rows.value.size + 1L
            rows.value = rows.value + FxRate(id, rate)
            return id
        }
    }

    /**
     * Una transacción que guarda los ajustes y las cotizaciones al empezar y los restaura si no se
     * confirma. [failAtWrite] hace fallar esa escritura con el disco lleno, antes de escribir.
     */
    private inner class SnapshotRunner : TransactionRunner {
        var failAtWrite: Int? = null
        var commits = 0
            private set
        private var writes = 0

        fun write() {
            writes += 1
            if (writes == failAtWrite) throw StorageFailureException(StorageFailure.DISK_FULL)
        }

        override suspend fun <T> write(block: suspend () -> TxResult<T>): T {
            writes = 0
            val savedSettings = settings.settings.value
            val savedRates = rates.rows.value
            val result = try {
                block()
            } catch (failure: Throwable) {
                settings.settings.value = savedSettings
                rates.rows.value = savedRates
                throw failure
            }
            return when (result) {
                is TxResult.Commit -> {
                    commits += 1
                    result.value
                }

                is TxResult.CommitUncounted -> result.value

                is TxResult.Rollback -> {
                    settings.settings.value = savedSettings
                    rates.rows.value = savedRates
                    result.value
                }
            }
        }

        override suspend fun <T> read(block: suspend () -> T): T = block()
    }
}

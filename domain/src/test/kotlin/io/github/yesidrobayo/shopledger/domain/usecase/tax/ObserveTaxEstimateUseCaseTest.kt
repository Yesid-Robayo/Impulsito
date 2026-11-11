package io.github.yesidrobayo.shopledger.domain.usecase.tax

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxSettings
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxEstimate
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxTotals
import io.github.yesidrobayo.shopledger.domain.port.TaxReportRepository
import io.github.yesidrobayo.shopledger.domain.port.TaxSettingsRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Test

class ObserveTaxEstimateUseCaseTest {
    private val settings = MutableStateFlow(TaxSettings(TaxMode.INCLUDED, null, false))

    /** Las sumas de cada período, como las daría la base. */
    private val totals = MutableStateFlow(emptyMap<ReportPeriod, TaxTotals>())

    /** Los períodos que se pidieron, en orden. */
    private val asked = mutableListOf<ReportPeriod>()

    private val taxSettings = object : TaxSettingsRepository {
        override fun observe(): Flow<TaxSettings> = settings

        override suspend fun get(): TaxSettings = settings.value

        override suspend fun update(taxes: TaxSettings, updatedAt: Instant) =
            error("No se usa en estas pruebas")
    }

    private val reports = object : TaxReportRepository {
        override fun observeTotals(period: ReportPeriod): Flow<TaxTotals> {
            asked += period
            return totals.map { it[period] ?: TaxTotals.ZERO }
        }
    }

    private val observe = ObserveTaxEstimateUseCase(taxSettings, reports)

    private val november = ReportPeriod.Month(YearMonth(2026, 11))

    @Test
    fun `the worked example of the period comes back as an estimate`() = runTest {
        totals.value = mapOf(november to TaxTotals(Money(52_500), Money(2_100), Money(21_000)))

        val state = observe(november).first()

        assertEquals(TaxEstimateState.Estimated(TaxEstimate(Money(50_400), Money(21_000))), state)
        assertEquals(listOf<ReportPeriod>(november), asked)
    }

    @Test
    fun `with added tax the estimate is shown too`() = runTest {
        settings.value = settings.value.copy(mode = TaxMode.ADDED)
        totals.value = mapOf(november to TaxTotals(Money(1_900), Money.ZERO, Money.ZERO))

        assertEquals(
            TaxEstimateState.Estimated(TaxEstimate(Money(1_900), Money.ZERO)),
            observe(november).first(),
        )
    }

    @Test
    fun `without tax the report does not appear`() = runTest {
        settings.value = settings.value.copy(mode = TaxMode.NONE)
        totals.value = mapOf(november to TaxTotals(Money(52_500), Money.ZERO, Money.ZERO))

        assertEquals(TaxEstimateState.Hidden, observe(november).first())
    }

    @Test
    fun `each period reads only its own sums`() = runTest {
        val day = ReportPeriod.Day(BusinessDate(LocalDate(2026, 11, 2)))
        totals.value = mapOf(
            november to TaxTotals(Money(52_500), Money.ZERO, Money.ZERO),
            day to TaxTotals(Money(2_100), Money.ZERO, Money.ZERO),
        )

        assertEquals(
            TaxEstimateState.Estimated(TaxEstimate(Money(2_100), Money.ZERO)),
            observe(day).first(),
        )
        assertEquals(listOf<ReportPeriod>(day), asked)
    }

    @Test
    fun `a new sale or a change of mode emits again, and nothing else does`() =
        runTest(UnconfinedTestDispatcher()) {
            val states = mutableListOf<TaxEstimateState>()
            val job = launch { observe(november).take(4).toList(states) }

            totals.value = mapOf(november to TaxTotals(Money(2_100), Money.ZERO, Money.ZERO))
            // El mismo modo con otra tasa por defecto no cambia la estimación.
            settings.value = settings.value.copy(defaultTaxRateId = 7)
            settings.value = settings.value.copy(mode = TaxMode.NONE)
            settings.value = settings.value.copy(mode = TaxMode.ADDED)
            job.join()

            assertEquals(
                listOf(
                    TaxEstimateState.Estimated(TaxEstimate(Money.ZERO, Money.ZERO)),
                    TaxEstimateState.Estimated(TaxEstimate(Money(2_100), Money.ZERO)),
                    TaxEstimateState.Hidden,
                    TaxEstimateState.Estimated(TaxEstimate(Money(2_100), Money.ZERO)),
                ),
                states,
            )
        }
}

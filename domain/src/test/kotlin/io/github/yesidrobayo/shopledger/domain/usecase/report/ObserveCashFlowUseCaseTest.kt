package io.github.yesidrobayo.shopledger.domain.usecase.report

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.experience.BusinessTool
import io.github.yesidrobayo.shopledger.domain.model.report.CashFlowSums
import io.github.yesidrobayo.shopledger.domain.model.report.CashFlowWindow
import io.github.yesidrobayo.shopledger.domain.port.CashFlowSource
import io.github.yesidrobayo.shopledger.domain.testing.FakeAppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.testing.FakeRecurringExpenseRepository
import io.github.yesidrobayo.shopledger.domain.testing.FixedClock
import io.github.yesidrobayo.shopledger.domain.testing.InMemoryDatabase
import io.github.yesidrobayo.shopledger.domain.testing.appSettings
import io.github.yesidrobayo.shopledger.domain.testing.regionalSettings
import io.github.yesidrobayo.shopledger.domain.usecase.region.BusinessCalendarSource
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * El flujo proyectado de hoy: parte de la fecha del negocio, pide los dos horizontes, y no aparece
 * sin cuentas ni sin zona. Hoy es el lunes 2026-11-02 en una zona cinco horas al oeste de UTC.
 */
class ObserveCashFlowUseCaseTest {
    private val clock = FixedClock(Instant.parse("2026-11-02T15:00:00Z"))
    private val database = InMemoryDatabase()
    private val recurring = FakeRecurringExpenseRepository(database)
    private val asked = mutableListOf<Triple<BusinessDate, BusinessDate, BusinessDate>>()
    private val sums = MutableStateFlow(
        CashFlowSums(
            accountCount = 1,
            balance = Money(100_000),
            notYetAvailable = Money(15_000),
            short = CashFlowWindow(Money(15_000), Money(20_000), Money(30_000)),
            long = CashFlowWindow(Money(15_000), Money(20_000), Money(30_000)),
        ),
    )
    private val inUse = MutableStateFlow(setOf(BusinessTool.MONEY_ACCOUNTS))
    private val source = CashFlowSource { today, shortUntil, longUntil ->
        asked += Triple(today, shortUntil, longUntil)
        sums
    }

    private fun date(text: String) = requireNotNull(BusinessDate.parse(text))

    private fun observe(zone: String = "Etc/GMT+5") = ObserveCashFlowUseCase(
        source,
        recurring,
        { inUse },
        BusinessCalendarSource(
            FakeAppSettingsRepository(appSettings(regionalSettings(zone, DayOfWeek.MONDAY))),
        ),
        clock,
    )

    @Test
    fun `it starts today and asks for seven and thirty days`() = runTest {
        val projection = requireNotNull(observe()().first())

        assertEquals(date("2026-11-02"), projection.today)
        assertEquals(Money(85_000), projection.availableToday)
        assertEquals(
            listOf(Triple(date("2026-11-02"), date("2026-11-09"), date("2026-12-02"))),
            asked,
        )
        assertEquals(Money(90_000), projection.horizons.first().projected)
    }

    @Test
    fun `without accounts in use, in the currency or a known zone it does not appear`() = runTest {
        inUse.value = emptySet()
        assertNull(observe()().first())
        assertEquals(emptyList<Any>(), asked)

        inUse.value = setOf(BusinessTool.MONEY_ACCOUNTS)
        sums.value = sums.value.copy(accountCount = 0)
        assertNull(observe()().first())

        sums.value = sums.value.copy(accountCount = 1)
        assertNull(observe(zone = "Nowhere/Unknown")().first())
    }
}

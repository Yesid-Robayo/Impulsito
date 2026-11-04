package io.github.yesidrobayo.shopledger.domain.usecase.region

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.BusinessTimeZone
import io.github.yesidrobayo.shopledger.domain.model.settings.BusinessProfile
import io.github.yesidrobayo.shopledger.domain.model.settings.RegionalSettings
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.testing.FakeAppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.testing.FixedClock
import io.github.yesidrobayo.shopledger.domain.testing.ForeignSystemDefaults
import io.github.yesidrobayo.shopledger.domain.testing.ForeignSystemDefaultsRule
import io.github.yesidrobayo.shopledger.domain.testing.appSettings
import io.github.yesidrobayo.shopledger.domain.testing.regionalSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * El calendario leído de los ajustes: cada lectura toma la zona y la semana vigentes, y una zona que
 * el teléfono no conoce llega como tal, sin otra en su lugar.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(Parameterized::class)
class BusinessCalendarSourceTest(defaults: ForeignSystemDefaults) {
    @get:Rule
    val foreignDefaults = ForeignSystemDefaultsRule(defaults)

    private val initial = regionalSettings("Etc/GMT+5", DayOfWeek.MONDAY)
    private val repository = FakeAppSettingsRepository(appSettings(initial))
    private val source = BusinessCalendarSource(repository)

    @Test
    fun `each read dates with the zone in force at that moment`() = runTest {
        val occurredAt = FixedClock.atEpochMillis(1_793_593_800_000).now()

        val before = ready(source.current()).stamp(occurredAt)
        changeRegional { it.copy(businessTimeZoneId = "Etc/GMT-9") }
        val after = ready(source.current()).stamp(occurredAt)

        // La fila escrita antes conserva su fecha; la nueva, en el mismo instante, usa la zona nueva.
        assertEquals(date("2026-11-01"), before.businessDate)
        assertEquals(date("2026-11-02"), after.businessDate)
        assertEquals(2, repository.reads)
    }

    @Test
    fun `the first day of the week comes from the settings`() = runTest {
        val wednesday = date("2026-11-04")

        val weekStarts =
            listOf(
                DayOfWeek.MONDAY to "2026-11-02",
                DayOfWeek.SATURDAY to "2026-10-31",
                DayOfWeek.SUNDAY to "2026-11-01",
            )
        for ((firstDay, start) in weekStarts) {
            changeRegional { it.copy(weekStartDay = firstDay) }

            assertEquals(date(start), ready(source.current()).week(wednesday).start)
        }
    }

    @Test
    fun `a stored zone this phone does not know arrives as unknown`() = runTest {
        changeRegional { it.copy(businessTimeZoneId = "Mars/Olympus_Mons") }

        assertEquals(BusinessCalendarResult.UnknownZone("Mars/Olympus_Mons"), source.current())
    }

    @Test
    fun `observing emits again only when the zone or the week change`() = runTest {
        val emissions = mutableListOf<BusinessCalendarResult>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            source.observe().toList(emissions)
        }

        // Otro ajuste del negocio no cambia el calendario.
        repository.settings.value =
            repository.settings.value.copy(
                profile = BusinessProfile("Tienda de prueba", BasisPoints(3_000)),
            )
        changeRegional { it.copy(weekStartDay = DayOfWeek.SUNDAY) }
        changeRegional { it.copy(businessTimeZoneId = "Mars/Olympus_Mons") }
        changeRegional { it.copy(businessTimeZoneId = "Asia/Kolkata") }

        assertEquals(
            listOf(
                calendar("Etc/GMT+5", DayOfWeek.MONDAY),
                calendar("Etc/GMT+5", DayOfWeek.SUNDAY),
                BusinessCalendarResult.UnknownZone("Mars/Olympus_Mons"),
                calendar("Asia/Kolkata", DayOfWeek.SUNDAY),
            ),
            emissions,
        )
    }

    private fun changeRegional(change: (RegionalSettings) -> RegionalSettings) {
        val current = repository.settings.value
        repository.settings.value = current.copy(regional = change(current.regional))
    }

    private companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun defaults(): List<ForeignSystemDefaults> = ForeignSystemDefaults.ALL

        fun ready(result: BusinessCalendarResult): BusinessCalendar = when (result) {
            is BusinessCalendarResult.Ready -> result.calendar
            is BusinessCalendarResult.UnknownZone -> error("Zona desconocida en la prueba")
        }

        fun calendar(zoneId: String, firstDay: DayOfWeek): BusinessCalendarResult =
            BusinessCalendarResult.Ready(
                BusinessCalendar(requireNotNull(BusinessTimeZone.of(zoneId)), firstDay),
            )

        fun date(text: String): BusinessDate = BusinessDate(LocalDate.parse(text))
    }
}

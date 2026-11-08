package io.github.yesidrobayo.shopledger.domain.usecase.payment

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.testing.FakeAppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.testing.appSettings
import io.github.yesidrobayo.shopledger.domain.testing.regionalSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Test

/** El redondeo de efectivo se lee de los ajustes regionales, tal como está guardado. */
class ObserveCashRoundingUseCaseTest {
    @Test
    fun `the cash rounding comes from the regional settings`() = runTest {
        val start = appSettings(regionalSettings("UTC", DayOfWeek.MONDAY))
        val settings = FakeAppSettingsRepository(start)
        val observe = ObserveCashRoundingUseCase(settings)

        assertEquals(Money.ZERO, observe().first())

        settings.settings.value =
            start.copy(regional = start.regional.copy(cashRounding = Money(50)))
        assertEquals(Money(50), observe().first())
    }
}

package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.report.MonthGoals
import io.github.yesidrobayo.shopledger.domain.model.settings.RevenueLimit
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las metas de cada mes sobre Room: un mes nuevo se inserta, uno con metas se actualiza sin
 * cambiar de fila, y quitarlas borra la fila. Cada escritura cuenta como cambio para la copia.
 * Montos inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class GoalRepositoryTest : RepositoryTest() {
    private val goals by lazy { RoomGoalRepository(database) }
    private val november = YearMonth(2026, 11)

    @Test
    fun aMonthIsSavedUpdatedInPlaceAndRemoved() = runBlocking {
        goals.observe(november).test {
            assertNull(awaitItem())

            val before = changeSeq()
            commit { goals.save(MonthGoals(november, Money(1_000_000), null), AT) }
            awaitValue(MonthGoals(november, Money(1_000_000), null))
            assertEquals(before + 1, changeSeq())
            val id = count("SELECT id FROM business_goals WHERE month = '2026-11'")

            commit { goals.save(MonthGoals(november, Money(1_200_000), Money(300_000)), LATER_AT) }
            awaitValue(MonthGoals(november, Money(1_200_000), Money(300_000)))
            // La misma fila, nunca una borrada y escrita de nuevo.
            assertEquals(id, count("SELECT id FROM business_goals WHERE month = '2026-11'"))
            assertEquals(1L, count("SELECT COUNT(*) FROM business_goals"))

            commit { goals.remove(november) }
            awaitValue(null)
            assertEquals(0L, count("SELECT COUNT(*) FROM business_goals"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun eachMonthKeepsItsOwnGoalsAndMonthsAreStoredWithTwoDigits() = runBlocking {
        val january = YearMonth(2027, 1)
        commit { goals.save(MonthGoals(january, Money(500_000), null), AT) }
        commit { goals.save(MonthGoals(november, null, Money(300_000)), AT) }

        assertEquals(MonthGoals(january, Money(500_000), null), goals.find(january))
        assertEquals(MonthGoals(november, null, Money(300_000)), goals.find(november))
        assertNull(goals.find(YearMonth(2026, 12)))
        assertEquals(1L, count("SELECT COUNT(*) FROM business_goals WHERE month = '2027-01'"))
    }

    @Test
    fun theRevenueLimitIsWrittenWithItsMonthsAndRemovedTogether() = runBlocking {
        val before = changeSeq()
        commit { goals.saveRevenueLimit(RevenueLimit(Money(10_000_000), 12), AT) }

        assertEquals(before + 1, changeSeq())
        assertEquals(
            10_000_000L,
            count("SELECT revenue_limit_amount FROM app_settings WHERE id = 1"),
        )
        assertEquals(12L, count("SELECT revenue_limit_months FROM app_settings WHERE id = 1"))

        commit { goals.saveRevenueLimit(null, LATER_AT) }
        assertEquals(
            1L,
            count(
                "SELECT COUNT(*) FROM app_settings WHERE id = 1 " +
                    "AND revenue_limit_amount IS NULL AND revenue_limit_months IS NULL",
            ),
        )
    }

    @Test
    fun aGoalTheGuardRejectsIsNotWritten() = runBlocking {
        assertEquals(
            StorageFailure.INVARIANT_VIOLATION,
            storageFailureOf {
                commit { goals.save(MonthGoals(november, Money(-1), null), AT) }
            },
        )
        assertNull(goals.find(november))
    }
}

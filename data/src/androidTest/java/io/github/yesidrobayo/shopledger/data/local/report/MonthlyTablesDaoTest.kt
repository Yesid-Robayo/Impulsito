package io.github.yesidrobayo.shopledger.data.local.report

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.catalog.CatalogDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.Emissions
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.UNIQUE
import io.github.yesidrobayo.shopledger.data.local.catalog.constraintFailure
import io.github.yesidrobayo.shopledger.data.local.foreignKeysOf
import io.github.yesidrobayo.shopledger.data.local.indexesOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las dos tablas de un mes `YYYY-MM`: las metas y el índice de precios. Cada mes tiene a lo sumo
 * una fila, se lee y se escribe por su mes, y se borra sin dejar rastro, porque nada depende de
 * ella.
 */
@RunWith(AndroidJUnit4::class)
internal class MonthlyTablesDaoTest : CatalogDatabaseTest() {
    private val goals get() = database.businessGoalDao()
    private val index get() = database.priceIndexEntryDao()

    @Test
    fun aMonthHasAtMostOneRowOfGoals() = runBlocking {
        val goal = BusinessGoalEntity(
            month = "2026-03",
            salesGoal = 5_000_000,
            profitGoal = -200_000,
            updatedAt = CREATED_AT,
        )
        val id = goals.insert(goal)
        goals.insert(goal.copy(month = "2026-04", salesGoal = null, profitGoal = null))

        assertEquals(goal.copy(id = id), goals.findByMonth("2026-03"))
        assertNull(goals.findByMonth("2026-05"))
        constraintFailure(UNIQUE) { goals.insert(goal.copy(salesGoal = 1)) }
        assertEquals(5_000_000L, goals.findByMonth("2026-03")?.salesGoal)
    }

    @Test
    fun goalsChangeByMonthAndAreSeenAgain() = runBlocking {
        val emissions =
            Emissions(this, goals.observeByMonth("2026-03").map { it?.salesGoal to it?.profitGoal })
        try {
            emissions.awaitValue(null to null)
            assertEquals(0, goals.update("2026-03", 1_000, 100, updatedAt = LATER))

            goals.insert(BusinessGoalEntity(0, "2026-03", 5_000_000, null, CREATED_AT))
            emissions.awaitValue(5_000_000L to null)

            assertEquals(1, goals.update("2026-03", null, 300_000, updatedAt = LATER))
            emissions.awaitValue(null to 300_000L)
            assertEquals(LATER, goals.findByMonth("2026-03")?.updatedAt)

            assertEquals(1, goals.delete("2026-03"))
            emissions.awaitValue(null to null)
            assertEquals(0, goals.delete("2026-03"))
        } finally {
            emissions.stop()
        }
    }

    @Test
    fun theIndexComesByMonthAndAMonthHasAtMostOneChange() = runBlocking {
        val march = entry("2026-03", changeBp = 120)
        val marchId = index.insert(march)
        index.insert(entry("2025-12", changeBp = -35))
        index.insert(entry("2026-01", changeBp = 0))
        constraintFailure(UNIQUE) { index.insert(entry("2026-03", changeBp = 999)) }

        val emissions = Emissions(this, index.observeAll().map { rows -> rows.map { it.month } })
        try {
            // Por mes, no por orden de carga: es el orden de la comparación.
            emissions.awaitValue(listOf("2025-12", "2026-01", "2026-03"))

            assertEquals(1, index.update("2026-03", -10_000, null, updatedAt = LATER))
            assertEquals(
                march.copy(id = marchId, changeBp = -10_000, sourceNote = null, updatedAt = LATER),
                index.findByMonth("2026-03"),
            )
            assertEquals(0, index.update("2026-02", 50, null, updatedAt = LATER))

            assertEquals(1, index.delete("2026-01"))
            emissions.awaitValue(listOf("2025-12", "2026-03"))
            assertNull(index.findByMonth("2026-01"))
        } finally {
            emissions.stop()
        }
    }

    @Test
    fun keysAndIndexesAreTheOnesOfTheDataModel() = runBlocking {
        assertEquals(
            listOf("unique index_business_goals_month (month)"),
            database.indexesOf("business_goals"),
        )
        assertEquals(
            listOf("unique index_price_index_entries_month (month)"),
            database.indexesOf("price_index_entries"),
        )
        assertEquals(emptyList<String>(), database.foreignKeysOf("business_goals"))
        assertEquals(emptyList<String>(), database.foreignKeysOf("price_index_entries"))
    }

    private fun entry(month: String, changeBp: Long): PriceIndexEntryEntity = PriceIndexEntryEntity(
        month = month,
        changeBp = changeBp,
        sourceNote = "Boletín mensual",
        createdAt = CREATED_AT,
        updatedAt = CREATED_AT,
    )
}

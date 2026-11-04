package io.github.yesidrobayo.shopledger.data.local.stock

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.LedgerDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.catalog.Emissions
import io.github.yesidrobayo.shopledger.data.local.catalog.FOREIGN_KEY
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.MUCH_LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.category
import io.github.yesidrobayo.shopledger.data.local.catalog.constraintFailure
import io.github.yesidrobayo.shopledger.data.local.catalog.guardFailure
import io.github.yesidrobayo.shopledger.data.local.catalog.product
import io.github.yesidrobayo.shopledger.data.local.ids
import io.github.yesidrobayo.shopledger.data.local.movement
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las sesiones de conteo: cada columna en su lugar, la categoría que se va sin borrar la sesión, la
 * lista de abiertas, lo contado que solo cambia con la sesión abierta, un cierre una sola vez, y
 * una sesión con ajustes en el libro, que no se borra. Sin ajustes, se borra con sus líneas.
 */
@RunWith(AndroidJUnit4::class)
internal class CountSessionDaoTest : LedgerDatabaseTest() {
    private val sessions get() = database.countSessionDao()

    @Test
    fun aSessionAndItsLinesReadBackWithEveryColumn() = runBlocking {
        val categoryId = database.categoryDao().insert(category("Lácteos"))
        val warehouse = database.locationDao().insert(location("Bodega"))
        val cheese = database.productDao().insert(product("Queso").copy(tracksLots = 1))
        val lotId = database.stockLotDao().insert(lot(cheese, "Q-01"))
        val open =
            session().copy(locationId = warehouse, categoryId = categoryId, note = "Fin de mes")

        val id = sessions.insert(open)
        val line =
            CountSessionLineEntity(
                countSessionId = id,
                productId = cheese,
                lotId = lotId,
                expectedMilli = -1_500,
                countedMilli = 2_750,
            )
        val lineId = sessions.insertLine(line)

        assertEquals(open.copy(id = id), sessions.findById(id))
        assertEquals(listOf(line.copy(id = lineId)), sessions.findLines(id))
        assertNull(sessions.findById(MISSING_ID))
    }

    @Test
    fun aSessionNeedsItsLocationAndLosesItsCategoryWithoutLosingItself() = runBlocking {
        constraintFailure(FOREIGN_KEY) { sessions.insert(session().copy(locationId = MISSING_ID)) }
        constraintFailure(FOREIGN_KEY) { sessions.insert(session().copy(categoryId = MISSING_ID)) }
        val categoryId = database.categoryDao().insert(category("Bebidas"))
        val id = sessions.insert(session().copy(categoryId = categoryId))
        constraintFailure(FOREIGN_KEY) { sessions.insertLine(countLine(id, MISSING_ID)) }
        constraintFailure(FOREIGN_KEY) {
            sessions.insertLine(countLine(id, productId).copy(lotId = MISSING_ID))
        }

        // Eliminar la categoría deja la sesión sin ella, y la guarda de la sesión lo deja pasar.
        database.categoryDao().delete(categoryId)
        assertNull(sessions.findById(id)?.categoryId)
        // Una ubicación con sesiones no se borra.
        val warehouse = database.locationDao().insert(location("Bodega"))
        sessions.insert(session().copy(locationId = warehouse))
        constraintFailure(FOREIGN_KEY) { database.locationDao().delete(warehouse) }
    }

    @Test
    fun countingChangesOnlyWhileTheSessionIsOpen() = runBlocking {
        val id = sessions.insert(session())
        val lineId = sessions.insertLine(countLine(id, productId))
        val lines =
            Emissions(this, sessions.observeLines(id).map { rows -> rows.map { it.countedMilli } })
        try {
            lines.awaitValue(listOf(null))
            assertEquals(1, sessions.setCounted(lineId, 9_500))
            lines.awaitValue(listOf(9_500L))
            assertEquals(1, sessions.updateNote(id, "Faltó un estante"))

            assertEquals(1, sessions.finish(id, "APPLIED", LATER))
            // Cerrada, ni se cuenta, ni se agregan líneas, ni cambia la nota, ni se cierra otra vez.
            guardFailure(LINES) { sessions.setCounted(lineId, 9_000) }
            guardFailure(LINES) { sessions.insertLine(countLine(id, productId)) }
            assertEquals(0, sessions.updateNote(id, "Otra nota"))
            assertEquals(0, sessions.finish(id, "CANCELLED", MUCH_LATER))
        } finally {
            lines.stop()
        }
        assertEquals(
            session().copy(
                id = id,
                status = "APPLIED",
                note = "Faltó un estante",
                finishedAt = LATER,
            ),
            sessions.findById(id),
        )
        assertEquals(listOf(9_500L), sessions.findLines(id).map { it.countedMilli })
        assertEquals(0, sessions.setCounted(MISSING_ID, 1))
    }

    @Test
    fun onlyOpenSessionsAreListedInTheirOrder() = runBlocking {
        val first = sessions.insert(session())
        val second = sessions.insert(session().copy(isBlind = 0))
        val third = sessions.insert(session())

        val open = Emissions(this, sessions.observeOpen().ids { it.id })
        try {
            open.awaitValue(listOf(first, second, third))
            sessions.finish(second, "CANCELLED", LATER)
            open.awaitValue(listOf(first, third))
        } finally {
            open.stop()
        }
        assertEquals("CANCELLED", sessions.findById(second)?.status)
    }

    @Test
    fun aSessionWithAdjustmentsStaysAndOneWithoutGoesWithItsLines() = runBlocking {
        val applied = sessions.insert(session())
        sessions.insertLine(countLine(applied, productId))
        database.stockMovementDao().insert(
            movement(productId, "ADJUSTMENT", -500, 1_200, -600, -500, 1_200, 0)
                .copy(reason = "COUNT_CORRECTION", countSessionId = applied),
        )
        sessions.finish(applied, "APPLIED", LATER)
        constraintFailure(FOREIGN_KEY) { sessions.delete(applied) }

        val cancelled = sessions.insert(session())
        sessions.insertLine(countLine(cancelled, productId))
        sessions.insertLine(countLine(cancelled, productId).copy(countedMilli = 3_000))
        sessions.finish(cancelled, "CANCELLED", LATER)
        assertEquals(1, sessions.delete(cancelled))

        assertEquals(emptyList<CountSessionLineEntity>(), sessions.findLines(cancelled))
        assertEquals(1, sessions.findLines(applied).size)
        assertEquals(0, sessions.delete(MISSING_ID))
    }

    private fun session(): CountSessionEntity = CountSessionEntity(
        status = "OPEN",
        isBlind = 1,
        locationId = MAIN_LOCATION_ID,
        categoryId = null,
        note = null,
        startedAt = CREATED_AT,
        finishedAt = null,
        businessDate = "2026-01-31",
    )

    private fun countLine(sessionId: Long, productId: Long): CountSessionLineEntity =
        CountSessionLineEntity(
            countSessionId = sessionId,
            productId = productId,
            lotId = null,
            expectedMilli = 10_000,
            countedMilli = null,
        )

    private companion object {
        const val MISSING_ID = 999L
        const val LINES = "guard:count_session_lines"
    }
}

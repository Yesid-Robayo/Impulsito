package io.github.yesidrobayo.shopledger.data.local.cash

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.GuardProbeTest
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import io.github.yesidrobayo.shopledger.data.local.money.moneyAccountRow
import io.github.yesidrobayo.shopledger.data.local.update
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las sondas de las guardas de caja: una sola sesión abierta por cuenta, que se cierra una vez y
 * con sus cifras, denominaciones con valor, y conteos que no cambian. Cada guarda rechaza lo que
 * debe, con su mensaje, y deja pasar lo válido.
 */
@RunWith(AndroidJUnit4::class)
internal class CashGuardsTest : GuardProbeTest() {
    @Test
    fun anAccountHasOneOpenSessionAtATime() = runBlocking {
        val accountId = insert("money_accounts", moneyAccountRow())
        val otherId = insert("money_accounts", moneyAccountRow("billetera"))
        val valid = cashSessionRow(accountId)
        for (broken in listOf(
            // Nace abierta, sin cierre.
            valid.with("status" to "CLOSED"),
            valid.with("closed_at" to LATER),
            valid.with("status" to "PAUSED"),
            valid.with("opening_amount" to 20_000.5),
            valid.with("expected_amount" to "veinte mil"),
            valid.with("business_date" to "2026-02-30"),
            valid.with("note" to ""),
            valid.with("opened_at" to null),
        )) {
            rejected(SESSIONS, broken.insertInto(TABLE))
        }

        val first = insert(TABLE, valid)
        // La segunda abierta de la misma cuenta se rechaza; la de otra cuenta, no.
        rejected(SESSIONS, valid.insertInto(TABLE))
        rejected(SESSIONS, valid.with("opening_amount" to 0L).insertInto(TABLE))
        insert(TABLE, cashSessionRow(otherId))
        // INSERT OR REPLACE sobre la abierta tampoco abre otra.
        rejected(SESSIONS, valid.withId(first).insertOrReplaceInto(TABLE))
        assertEquals(2L, count("SELECT COUNT(*) FROM cash_sessions WHERE status = 'OPEN'"))

        // Cerrada, la cuenta puede abrir la siguiente.
        execute(closing(first, expected = 40_000L, counted = 39_500L, difference = -500L))
        insert(TABLE, valid)
        assertEquals(3L, count("SELECT COUNT(*) FROM cash_sessions"))
        assertEquals(
            1L,
            count(
                "SELECT COUNT(*) FROM cash_sessions " +
                    "WHERE money_account_id = $accountId AND status = 'OPEN'",
            ),
        )
    }

    @Test
    fun aSessionClosesOnceWithItsFiguresAndThenNeverChanges() = runBlocking {
        val accountId = insert("money_accounts", moneyAccountRow())
        val id = insert(TABLE, cashSessionRow(accountId))

        // Lo que no cambia mientras está abierta.
        rejected(SESSIONS, update(TABLE, id, "opening_amount" to 25_000L))
        rejected(SESSIONS, update(TABLE, id, "opened_at" to LATER))
        val otherId = insert("money_accounts", moneyAccountRow("caja chica"))
        rejected(SESSIONS, update(TABLE, id, "money_account_id" to otherId))
        // Cerrar exige las tres cifras, el instante, y que la diferencia sea lo contado menos lo
        // esperado.
        rejected(SESSIONS, closing(id, expected = 40_000L, counted = 39_500L, difference = 500L))
        rejected(SESSIONS, update(TABLE, id, "status" to "CLOSED", "closed_at" to LATER))
        rejected(
            SESSIONS,
            update(
                TABLE,
                id,
                "status" to "CLOSED",
                "expected_amount" to 40_000L,
                "counted_amount" to 39_500L,
                "difference_amount" to -500L,
            ),
        )
        // Abierta, todavía admite su nota.
        execute(update(TABLE, id, "note" to "Turno de la mañana"))
        execute(closing(id, expected = 40_000L, counted = 39_500L, difference = -500L))

        // Cerrada, ya no cambia ni se reabre.
        rejected(SESSIONS, update(TABLE, id, "note" to "Corregida"))
        rejected(SESSIONS, update(TABLE, id, "status" to "OPEN", "closed_at" to null))
        assertEquals(-500L, count("SELECT difference_amount FROM cash_sessions WHERE id = $id"))
    }

    @Test
    fun aDenominationHasAPositiveValue() = runBlocking {
        val valid = denominationRow()
        for (broken in listOf(
            valid.with("value" to 0L),
            valid.with("value" to 1_000_000_000_001L),
            valid.with("value" to 0.5),
            valid.with("is_active" to 2L),
            valid.with("sort_order" to "primero"),
            valid.with("updated_at" to null),
        )) {
            rejected(DENOMINATIONS, broken.insertInto("cash_denominations"))
        }
        val id = insert("cash_denominations", valid.with("value" to 1_000_000_000_000L))

        rejected(DENOMINATIONS, update("cash_denominations", id, "value" to -1L))
        execute(update("cash_denominations", id, "value" to 1L, "is_active" to 0L))
        assertEquals(1L, count("SELECT value FROM cash_denominations WHERE id = $id"))
    }

    @Test
    fun aCountIsAWholeNumberAndNeverChanges() = runBlocking {
        val accountId = insert("money_accounts", moneyAccountRow())
        val sessionId = insert(TABLE, cashSessionRow(accountId))
        val denominationId = insert("cash_denominations", denominationRow())
        val valid = countRow(sessionId, denominationId)
        for (broken in listOf(
            valid.with("phase" to "MIDDAY"),
            valid.with("count" to -1L),
            valid.with("count" to 1_000_000_001L),
            valid.with("count" to 2.5),
        )) {
            rejected(COUNTS, broken.insertInto("cash_session_counts"))
        }
        val id = insert("cash_session_counts", valid.with("count" to 0L))
        insert("cash_session_counts", valid.with("phase" to "OPENING", "count" to 1_000_000_000L))

        rejected(COUNTS_APPEND_ONLY, update("cash_session_counts", id, "count" to 4L))
        rejected(COUNTS_APPEND_ONLY, "DELETE FROM cash_session_counts WHERE id = $id")
        rejected(COUNTS, valid.withId(id).insertOrReplaceInto("cash_session_counts"))
        assertEquals(0L, count("SELECT count FROM cash_session_counts WHERE id = $id"))
    }

    /** El cierre de la sesión [id] con sus tres cifras, como lo escribe su DAO. */
    private fun closing(id: Long, expected: Long, counted: Long, difference: Long): String = update(
        TABLE,
        id,
        "status" to "CLOSED",
        "expected_amount" to expected,
        "counted_amount" to counted,
        "difference_amount" to difference,
        "closed_at" to LATER,
    )

    private companion object {
        const val TABLE = "cash_sessions"
        const val SESSIONS = "guard:cash_sessions"
        const val DENOMINATIONS = "guard:cash_denominations"
        const val COUNTS = "guard:cash_session_counts"
        const val COUNTS_APPEND_ONLY = "guard:cash_session_counts_append_only"
    }
}

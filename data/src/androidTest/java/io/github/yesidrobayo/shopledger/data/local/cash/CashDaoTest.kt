package io.github.yesidrobayo.shopledger.data.local.cash

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.LedgerDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.Emissions
import io.github.yesidrobayo.shopledger.data.local.catalog.FOREIGN_KEY
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.MUCH_LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.UNIQUE
import io.github.yesidrobayo.shopledger.data.local.catalog.constraintFailure
import io.github.yesidrobayo.shopledger.data.local.catalog.guardFailure
import io.github.yesidrobayo.shopledger.data.local.expense.expense
import io.github.yesidrobayo.shopledger.data.local.expense.expenseCategory
import io.github.yesidrobayo.shopledger.data.local.foreignKeysOf
import io.github.yesidrobayo.shopledger.data.local.ids
import io.github.yesidrobayo.shopledger.data.local.indexesOf
import io.github.yesidrobayo.shopledger.data.local.money.appendMoneyMovement
import io.github.yesidrobayo.shopledger.data.local.money.moneyAccount
import io.github.yesidrobayo.shopledger.data.local.money.moneyMovement
import io.github.yesidrobayo.shopledger.data.local.sale
import io.github.yesidrobayo.shopledger.data.local.saleReturn
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La caja: sus índices y llaves son los del modelo de datos, la base nace sin denominaciones, una
 * cuenta tiene una sola sesión abierta, y lo que guardan la sesión, sus movimientos y sus conteos
 * basta para rehacer lo esperado, lo contado y la diferencia. El ejemplo es el de las reglas de lo
 * esperado y de la diferencia de caja.
 */
@RunWith(AndroidJUnit4::class)
internal class CashDaoTest : LedgerDatabaseTest() {
    private val sessions get() = database.cashSessionDao()
    private val denominations get() = database.cashDenominationDao()
    private val counts get() = database.cashSessionCountDao()
    private val accounts get() = database.moneyAccountDao()

    @Test
    fun indexesAndKeysAreTheOnesOfTheDataModel() = runBlocking {
        val indexes =
            mapOf(
                "cash_sessions" to
                    listOf(
                        "index_cash_sessions_money_account_id_status (money_account_id, status)",
                    ),
                "cash_denominations" to emptyList(),
                "cash_session_counts" to
                    listOf(
                        "unique index_cash_session_counts_cash_session_id_phase_denomination_id " +
                            "(cash_session_id, phase, denomination_id)",
                        "index_cash_session_counts_denomination_id (denomination_id)",
                    ),
            )
        val keys =
            mapOf(
                "cash_sessions" to listOf("money_account_id -> money_accounts RESTRICT"),
                "cash_denominations" to emptyList(),
                "cash_session_counts" to
                    listOf(
                        "cash_session_id -> cash_sessions RESTRICT",
                        "denomination_id -> cash_denominations RESTRICT",
                    ),
            )

        for ((table, expected) in indexes) assertEquals(table, expected, database.indexesOf(table))
        for ((table, expected) in keys) assertEquals(table, expected, database.foreignKeysOf(table))
        // La venta y el movimiento de dinero ya apuntan a su sesión.
        val saleKeys = database.foreignKeysOf("sales")
        val movementKeys = database.foreignKeysOf("money_movements")
        assertEquals(true, "cash_session_id -> cash_sessions RESTRICT" in saleKeys)
        assertEquals(true, "cash_session_id -> cash_sessions RESTRICT" in movementKeys)
    }

    @Test
    fun aNewDatabaseHasNoDenominations() = runBlocking {
        // Cuáles billetes y monedas existen depende de la moneda: la persona los escribe.
        assertEquals(0L, count("SELECT COUNT(*) FROM cash_denominations"))
        assertEquals(0L, count("SELECT COUNT(*) FROM cash_sessions"))
    }

    @Test
    fun theCashExampleRebuildsTheExpectedTheCountedAndTheDifference() = runBlocking {
        val accountId = accounts.insert(moneyAccount("Caja", kind = "CASH"))
        val categoryId = database.expenseCategoryDao().insert(expenseCategory("Empaques"))
        val expenseId = database.expenseDao().insert(expense(categoryId))
        val sessionId = sessions.insert(openSession(accountId, openingAmount = 20_000))
        assertEquals(sessionId, sessions.findOpen(accountId)?.id)
        // La devolución ya tiene llave: el reembolso apunta a una que existe.
        val sold = database.saleDao().insert(sale(insertPriceList("lista")))
        val saleReturnId = database.saleReturnDao().insert(saleReturn(sold))

        // Apertura de 20.000, ventas en efectivo por 35.000, una devolución de 2.000, un gasto de
        // 3.000 y un retiro de 10.000: se esperan 40.000.
        var balance = 0L
        for (movement in listOf(
            moneyMovement(accountId, "OPENING_BALANCE", 20_000, 0),
            moneyMovement(accountId, "SALE_RECEIPT", 35_000, 0).copy(salePaymentId = 1),
            moneyMovement(accountId, "REFUND", -2_000, 0).copy(saleReturnId = saleReturnId),
            moneyMovement(accountId, "EXPENSE_PAYMENT", -3_000, 0).copy(expenseId = expenseId),
            moneyMovement(accountId, "OWNER_DRAW", -10_000, 0),
        )) {
            balance += movement.amount
            val inSession = if (movement.type == "OPENING_BALANCE") null else sessionId
            database.appendMoneyMovement(
                movement.copy(balanceAfter = balance, cashSessionId = inSession),
            )
        }
        val expected = Math.addExact(20_000L, sessions.movementsTotal(sessionId))
        assertEquals(40_000L, expected)

        // Conteo al cerrar: 2 de 10.000, 3 de 5.000, 4 de 1.000 y 1 de 500 suman 39.500.
        val pieces = listOf(10_000L to 2L, 5_000L to 3L, 1_000L to 4L, 500L to 1L)
        val ids = pieces.mapIndexed { index, (value, _) ->
            denominations.insert(denomination(value, sortOrder = index.toLong()))
        }
        counts.insertAll(
            ids.zip(pieces).map { (id, piece) ->
                CashSessionCountEntity(
                    cashSessionId = sessionId,
                    phase = "CLOSING",
                    denominationId = id,
                    count = piece.second,
                )
            },
        )
        val stored = counts.findBySession(sessionId, "CLOSING")
        val counted =
            stored.sumOf { row ->
                val value = requireNotNull(denominations.findById(row.denominationId)).value
                Math.multiplyExact(value, row.count)
            }
        assertEquals(39_500L, counted)
        assertEquals(ids, stored.map { it.denominationId })
        assertEquals(0, counts.findBySession(sessionId, "OPENING").size)

        // Faltan 500: la sesión se cierra con la diferencia, y un movimiento la lleva a la cuenta.
        val difference = counted - expected
        assertEquals(1, sessions.close(sessionId, expected, counted, difference, LATER, null))
        database.appendMoneyMovement(
            moneyMovement(accountId, "CASH_DIFFERENCE", difference, balance + difference)
                .copy(cashSessionId = sessionId),
        )
        // La diferencia no cuenta en lo esperado, y la sesión cerrada ya no cambia.
        assertEquals(20_000L, sessions.movementsTotal(sessionId))
        assertEquals(39_500L, accounts.findById(accountId)?.balance)
        assertEquals(0, sessions.close(sessionId, 1, 1, 0, MUCH_LATER, "otra vez"))
        val closed = requireNotNull(sessions.findById(sessionId))
        assertEquals("CLOSED", closed.status)
        assertEquals(
            listOf(40_000L, 39_500L, -500L),
            listOf(closed.expectedAmount, closed.countedAmount, closed.differenceAmount),
        )
        assertNull(sessions.findOpen(accountId))
    }

    @Test
    fun aSecondOpenSessionOfTheSameAccountIsRejected() = runBlocking {
        val accountId = accounts.insert(moneyAccount("Caja", kind = "CASH"))
        val otherId = accounts.insert(moneyAccount("Banco"))
        val first = sessions.insert(openSession(accountId))

        guardFailure("guard:cash_sessions") { sessions.insert(openSession(accountId)) }
        sessions.insert(openSession(otherId))
        sessions.close(first, 20_000, 20_000, 0, LATER, null)
        val second = sessions.insert(openSession(accountId, openingAmount = 20_000))

        assertEquals(second, sessions.findOpen(accountId)?.id)
        val emissions = Emissions(this, sessions.observeByAccount(accountId).ids { it.id })
        try {
            emissions.awaitValue(listOf(second, first))
        } finally {
            emissions.stop()
        }
    }

    @Test
    fun whatHasHistoryIsNotDeletedAndNothingIsAnOrphan() = runBlocking {
        val accountId = accounts.insert(moneyAccount("Caja", kind = "CASH"))
        val sessionId = sessions.insert(openSession(accountId))
        val used = denominations.insert(denomination(1_000))
        val unused = denominations.insert(denomination(500))
        val opening =
            CashSessionCountEntity(
                cashSessionId = sessionId,
                phase = "OPENING",
                denominationId = used,
                count = 20,
            )
        counts.insertAll(listOf(opening))

        constraintFailure(UNIQUE) { counts.insertAll(listOf(opening.copy(count = 21))) }
        constraintFailure(FOREIGN_KEY) { denominations.delete(used) }
        assertEquals(1, denominations.delete(unused))
        constraintFailure(FOREIGN_KEY) { accounts.delete(accountId) }
        constraintFailure(FOREIGN_KEY) {
            counts.insertAll(listOf(opening.copy(cashSessionId = MISSING_ID, phase = "CLOSING")))
        }
        constraintFailure(FOREIGN_KEY) {
            sessions.insert(openSession(MISSING_ID))
        }
        constraintFailure(FOREIGN_KEY) {
            database.saleDao().insert(sale(priceListId).copy(cashSessionId = MISSING_ID))
        }
        constraintFailure(FOREIGN_KEY) {
            database.moneyMovementDao().insert(
                moneyMovement(accountId, "CASH_DIFFERENCE", -1, -1)
                    .copy(cashSessionId = MISSING_ID),
            )
        }
        database.saleDao().insert(sale(priceListId).copy(cashSessionId = sessionId))
        assertEquals(1L, count("SELECT COUNT(*) FROM sales WHERE cash_session_id = $sessionId"))
    }

    @Test
    fun denominationsAreListedInThePersonsOrderAndCanBeDeactivated() = runBlocking {
        val ten = denominations.insert(denomination(10_000, sortOrder = 2))
        val five = denominations.insert(denomination(5_000, sortOrder = 1))
        val alsoFive = denominations.insert(denomination(5_000, sortOrder = 1))
        val stored = requireNotNull(denominations.findById(ten))
        assertEquals(denomination(10_000, sortOrder = 2).copy(id = ten), stored)

        val active = Emissions(this, denominations.observeActive().ids { it.id })
        val all = Emissions(this, denominations.observeAll().ids { it.id })
        try {
            active.awaitValue(listOf(five, alsoFive, ten))
            assertEquals(1, denominations.update(alsoFive, 5_000, 0, sortOrder = 1, LATER))
            active.awaitValue(listOf(five, ten))
            all.awaitValue(listOf(five, alsoFive, ten))
        } finally {
            active.stop()
            all.stop()
        }
    }

    private companion object {
        const val MISSING_ID = 999L
    }
}

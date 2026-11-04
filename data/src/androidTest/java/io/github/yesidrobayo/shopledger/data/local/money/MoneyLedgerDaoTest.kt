package io.github.yesidrobayo.shopledger.data.local.money

import androidx.room3.useReaderConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.cash.openSession
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.catalog.CatalogDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.Emissions
import io.github.yesidrobayo.shopledger.data.local.catalog.FIXED_CLOCK
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.MUCH_LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.guardFailure
import io.github.yesidrobayo.shopledger.data.local.catalog.supplier
import io.github.yesidrobayo.shopledger.data.local.deleteDatabaseFiles
import io.github.yesidrobayo.shopledger.data.local.expense.expense
import io.github.yesidrobayo.shopledger.data.local.expense.expenseCategory
import io.github.yesidrobayo.shopledger.data.local.ids
import io.github.yesidrobayo.shopledger.data.local.payable.supplierEntry
import io.github.yesidrobayo.shopledger.data.local.payment.insertSaleWithCardPayment
import io.github.yesidrobayo.shopledger.data.local.purchaseReturn
import io.github.yesidrobayo.shopledger.data.local.sale
import io.github.yesidrobayo.shopledger.data.local.saleReturn
import io.github.yesidrobayo.shopledger.data.local.testDatabaseFile
import io.github.yesidrobayo.shopledger.data.local.texts
import io.github.yesidrobayo.shopledger.domain.port.Clock
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las cuentas y el libro de dinero: la cuenta de caja nace con la base y una sola vez, cada columna
 * vuelve de su lugar, el libro se lee en el orden en que se registró, y lo que guarda basta para
 * rehacer el saldo de cada cuenta y su cadena de `balance_after`, con las consultas de los
 * invariantes del modelo de datos. El ejemplo es el de la regla del libro de dinero.
 */
@RunWith(AndroidJUnit4::class)
internal class MoneyLedgerDaoTest : CatalogDatabaseTest() {
    private val accounts get() = database.moneyAccountDao()
    private val movements get() = database.moneyMovementDao()

    @Test
    fun aNewDatabaseHasTheCashAccountWithoutCurrencyYet() = runBlocking {
        val cash = requireNotNull(accounts.findBySystemKey("CASH"))

        // Sin nombre propio: se muestra con el texto traducido de su clave. La moneda queda sin
        // confirmar hasta que la persona confirme la del negocio.
        assertEquals(
            MoneyAccountEntity(
                id = cash.id,
                systemKey = "CASH",
                name = null,
                normalizedName = null,
                kind = "CASH",
                currencyCode = "XXX",
                currencyDecimals = 0,
                balance = 0,
                archivedAt = null,
                createdAt = CREATED_AT,
                updatedAt = CREATED_AT,
            ),
            cash,
        )
        assertEquals(1L, count("SELECT COUNT(*) FROM money_accounts"))
        assertNull(accounts.findBySystemKey("BANK"))

        // Confirmar la moneda la escribe en la cuenta mientras no tiene movimientos.
        assertEquals(1, accounts.setCurrency(cash.id, TEST_CURRENCY, 2, LATER))
        assertEquals(TEST_CURRENCY, accounts.findById(cash.id)?.currencyCode)
        database.appendMoneyMovement(moneyMovement(cash.id, "OPENING_BALANCE", 20_000, 20_000))
        guardFailure("guard:money_accounts") { accounts.setCurrency(cash.id, "XXX", 0, MUCH_LATER) }
    }

    @Test
    fun anExistingDatabaseDoesNotGetASecondCashAccount() = runBlocking {
        val file = testDatabaseFile(context, "cash-account-reopen.db")
        deleteDatabaseFiles(file)
        try {
            val first = AppDatabase.open(context, file, Dispatchers.IO, FIXED_CLOCK)
            val cashId =
                try {
                    val cash = requireNotNull(first.moneyAccountDao().findBySystemKey("CASH"))
                    first.moneyAccountDao().update(cash.id, "Caja", "caja", "CASH", LATER)
                    cash.id
                } finally {
                    first.close()
                }

            // Otro reloj: si la fila naciera otra vez, saldría con este instante.
            val later = Clock { Instant.fromEpochMilliseconds(LATER + 1) }
            val reopened = AppDatabase.open(context, file, Dispatchers.IO, later)
            try {
                val counted =
                    reopened.useReaderConnection { it.texts("SELECT id FROM money_accounts") }
                assertEquals(listOf("$cashId"), counted)
                assertEquals("Caja", reopened.moneyAccountDao().findById(cashId)?.name)
            } finally {
                reopened.close()
            }
        } finally {
            deleteDatabaseFiles(file)
        }
    }

    @Test
    fun everyColumnReadsBackFromItsOwnPlace() = runBlocking {
        val accountId = accounts.insert(moneyAccount("Banco"))
        val complete = moneyAccount("Billetera", kind = "WALLET", currencyDecimals = 4).copy(
            balance = 7,
            archivedAt = MUCH_LATER,
            updatedAt = LATER,
        )
        val completeId = accounts.insert(complete)
        val supplierId = database.supplierDao().insert(supplier("Distribuidora Andina"))
        val sessionId = database.cashSessionDao().insert(openSession(accountId))
        val supplierPayment =
            database.supplierLedgerEntryDao()
                .insert(supplierEntry(supplierId, "PAYMENT", -5, -5))
        val priceListId = insertPriceList("lista")
        val sold = database.saleDao().insert(sale(priceListId))
        val saleReturnId = database.saleReturnDao().insert(saleReturn(sold))
        val purchaseReturnId = database.purchaseReturnDao().insert(purchaseReturn())
        val salePayment = database.insertSaleWithCardPayment(priceListId)
        // Cada columna con un valor distinto dentro de su fila, para que dos columnas cruzadas no
        // pasen. Cada origen existe, porque todas las columnas ya tienen llave.
        val rows =
            listOf(
                moneyMovement(accountId, "FEE", -300, -301).copy(
                    availableDate = "2026-01-03",
                    salePaymentId = salePayment,
                    note = "Comisión",
                    occurredAt = 11,
                    createdAt = 12,
                ),
                moneyMovement(accountId, "TRANSFER_OUT", -1_000, -1_301).copy(
                    transferGroupId = TRANSFER_GROUP,
                    fxRateMicro = 4_150_000_000,
                ),
                moneyMovement(accountId, "SUPPLIER_PAYMENT", -5, -1_306)
                    .copy(supplierLedgerEntryId = supplierPayment),
                moneyMovement(accountId, "REFUND", -6, -1_312).copy(saleReturnId = saleReturnId),
                moneyMovement(accountId, "SUPPLIER_REFUND", 7, -1_305)
                    .copy(purchaseReturnId = purchaseReturnId),
                moneyMovement(accountId, "CASH_DIFFERENCE", -8, -1_313)
                    .copy(cashSessionId = sessionId),
            )

        val stored = rows.map { it.copy(id = movements.insert(it)) }

        for (row in stored) assertEquals(row, movements.findById(row.id))
        assertEquals(complete.copy(id = completeId), accounts.findById(completeId))
        assertNull(movements.findById(MISSING_ID))
    }

    @Test
    fun theMoneyLedgerExampleRebuildsTheBalanceAndTheChain() = runBlocking {
        val accountId = accounts.insert(moneyAccount("Banco"))
        val categoryId = database.expenseCategoryDao().insert(expenseCategory("Arriendo"))
        val expenseId = database.expenseDao().insert(expense(categoryId))
        val salePayment = database.insertSaleWithCardPayment(insertPriceList("lista"))
        assertNull(movements.lastBalanceAfter(accountId))

        // El ejemplo de la regla: saldo inicial, una venta cobrada, un gasto y un retiro. Cada
        // movimiento toma su saldo del anterior, como lo hará el repositorio.
        val steps =
            listOf(
                Triple("OPENING_BALANCE", 50_000L, 50_000L),
                Triple("SALE_RECEIPT", 12_000L, 62_000L),
                Triple("EXPENSE_PAYMENT", -3_000L, 59_000L),
                Triple("OWNER_DRAW", -10_000L, 49_000L),
            )
        for ((type, amount, expected) in steps) {
            val previous = movements.lastBalanceAfter(accountId) ?: 0
            assertEquals(expected, previous + amount)
            val movement = moneyMovement(accountId, type, amount, previous + amount)
            // Venta y gasto llevan su origen.
            val withOrigin =
                when (type) {
                    "SALE_RECEIPT" -> movement.copy(salePaymentId = salePayment)
                    "EXPENSE_PAYMENT" -> movement.copy(expenseId = expenseId)
                    else -> movement
                }
            database.appendMoneyMovement(withOrigin)
        }

        val rows = movements.findByAccount(accountId)
        assertEquals(steps.map { it.third }, rows.map { it.balanceAfter })
        assertEquals(49_000L, accounts.findById(accountId)?.balance)
        assertEquals(rows.sumOf { it.amount }, accounts.findById(accountId)?.balance)
        assertEquals(emptyList<String>(), invariant(MONEY_BALANCE_SQL))
        assertEquals(emptyList<String>(), invariant(MONEY_CHAIN_SQL))
        // La cuenta inicial no tiene movimientos: su saldo de cero también es su suma, y la
        // consulta del saldo no la señala.
        assertEquals(0L, accounts.findBySystemKey("CASH")?.balance)
    }

    @Test
    fun aTransferIsTwoRowsOfOneGroupAndEachAccountKeepsItsOwnCurrency() = runBlocking {
        val bank = accounts.insert(moneyAccount("Banco", currencyDecimals = 2))
        val cash =
            accounts.insert(
                moneyAccount("Caja", kind = "CASH", currencyCode = "XXX", currencyDecimals = 0),
            )
        database.appendMoneyMovement(moneyMovement(bank, "OPENING_BALANCE", 50_000, 50_000))
        // Diez mil de una cuenta de dos decimales entran como 415.000 en una de cero, con su
        // cotización: cada monto va en la moneda de su cuenta y nunca se mezclan.
        val out =
            moneyMovement(bank, "TRANSFER_OUT", -10_000, 40_000)
                .copy(transferGroupId = TRANSFER_GROUP, fxRateMicro = 4_150_000_000)
        val into =
            moneyMovement(cash, "TRANSFER_IN", 415_000, 415_000)
                .copy(transferGroupId = TRANSFER_GROUP, fxRateMicro = 4_150_000_000)
        val outId = database.appendMoneyMovement(out)
        assertEquals(listOf(TRANSFER_GROUP), invariant(TRANSFER_PAIRS_SQL))
        val intoId = database.appendMoneyMovement(into)

        val group = movements.findByTransferGroup(TRANSFER_GROUP)
        assertEquals(listOf(outId, intoId), group.map { it.id })
        assertEquals(emptyList<String>(), invariant(TRANSFER_PAIRS_SQL))
        assertEquals(40_000L, accounts.findById(bank)?.balance)
        assertEquals(415_000L, accounts.findById(cash)?.balance)
        assertEquals(emptyList<String>(), invariant(MONEY_BALANCE_SQL))
        assertEquals(emptyList<String>(), invariant(MONEY_CHAIN_SQL))
    }

    @Test
    fun theInvariantQueriesFindAWrongBalanceAndABrokenChain() = runBlocking {
        val accountId = accounts.insert(moneyAccount("Banco"))
        val drawId =
            database.appendMoneyMovement(moneyMovement(accountId, "OWNER_DRAW", -10_000, -10_000))
        val undo =
            moneyMovement(accountId, "MOVEMENT_VOID", 10_000, 0).copy(reversesMovementId = drawId)
        database.appendMoneyMovement(undo)
        assertTrue(movements.isCompensated(drawId))
        assertFalse(movements.isCompensated(MISSING_ID))
        assertEquals(emptyList<String>(), invariant(MONEY_CHAIN_SQL))

        accounts.setBalance(accountId, balance = 1, updatedAt = MUCH_LATER)
        assertEquals(listOf("$accountId"), invariant(MONEY_BALANCE_SQL))
        // Un movimiento cuyo saldo no sigue al anterior entra, porque la guarda no recalcula la
        // cadena, pero su consulta lo señala sin ambigüedad.
        val broken = movements.insert(moneyMovement(accountId, "OTHER_INCOME", 500, 700))
        assertEquals(listOf("$broken"), invariant(MONEY_CHAIN_SQL))
    }

    @Test
    fun theLedgerIsReadInTheOrderItWasRecordedAndTheListsAreStable() = runBlocking {
        val cashId = requireNotNull(accounts.findBySystemKey("CASH")).id
        val bank = accounts.insert(moneyAccount("Banco"))
        // El reloj del teléfono retrocedió: el segundo dice ser anterior al primero.
        val first = moneyMovement(bank, "OPENING_BALANCE", 5_000, 5_000, occurredAt = MUCH_LATER)
        val firstId = movements.insert(first)
        val second = moneyMovement(bank, "OWNER_DRAW", -2_000, 3_000, occurredAt = CREATED_AT)
        val secondId = movements.insert(second)
        val expected = listOf(first.copy(id = firstId), second.copy(id = secondId))
        assertEquals(expected, movements.findByAccount(bank))

        val ledger = Emissions(this, movements.observeByAccount(bank))
        val active = Emissions(this, accounts.observeActive().ids { it.id })
        try {
            ledger.awaitValue(expected)
            active.awaitValue(listOf(cashId, bank))

            val third = moneyMovement(bank, "OTHER_INCOME", 1_000, 4_000)
            val thirdId = movements.insert(third)
            ledger.awaitValue(expected + third.copy(id = thirdId))
            accounts.setArchivedAt(bank, archivedAt = LATER, updatedAt = LATER)
            active.awaitValue(listOf(cashId))
        } finally {
            ledger.stop()
            active.stop()
        }
    }

    private suspend fun invariant(sql: String): List<String> =
        database.useReaderConnection { it.texts(sql) }

    private companion object {
        const val MISSING_ID = 999L
    }
}

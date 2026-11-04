package io.github.yesidrobayo.shopledger.data.local.money

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.LedgerDatabaseTest
import io.github.yesidrobayo.shopledger.data.local.catalog.FOREIGN_KEY
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import io.github.yesidrobayo.shopledger.data.local.catalog.UNIQUE
import io.github.yesidrobayo.shopledger.data.local.catalog.constraintFailure
import io.github.yesidrobayo.shopledger.data.local.catalog.guardFailure
import io.github.yesidrobayo.shopledger.data.local.customer.customer
import io.github.yesidrobayo.shopledger.data.local.customer.customerEntry
import io.github.yesidrobayo.shopledger.data.local.expense.expense
import io.github.yesidrobayo.shopledger.data.local.expense.expenseCategory
import io.github.yesidrobayo.shopledger.data.local.foreignKeysOf
import io.github.yesidrobayo.shopledger.data.local.indexesOf
import io.github.yesidrobayo.shopledger.data.local.purchase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Los índices y las llaves de cuentas y movimientos de dinero, tal como quedan en la base,
 * comparados con el modelo de datos, y las llaves de las compras y los gastos hacia la cuenta. Lo
 * que más importa: `reverses_movement_id` es único, porque un movimiento se anula una
 * sola vez, y cada columna de origen cuya tabla existe tiene su llave `RESTRICT`.
 */
@RunWith(AndroidJUnit4::class)
internal class MoneySchemaTest : LedgerDatabaseTest() {
    private val accounts get() = database.moneyAccountDao()
    private val movements get() = database.moneyMovementDao()

    @Test
    fun indexesAreTheOnesOfTheDataModel() = runBlocking {
        val expected =
            mapOf(
                "money_accounts" to
                    listOf(
                        "index_money_accounts_normalized_name (normalized_name)",
                        "unique index_money_accounts_system_key (system_key)",
                    ),
                "money_movements" to
                    listOf(
                        "index_money_movements_cash_session_id (cash_session_id)",
                        "index_money_movements_money_account_id (money_account_id)",
                        "unique index_money_movements_reverses_movement_id " +
                            "(reverses_movement_id)",
                        "index_money_movements_sale_payment_id (sale_payment_id)",
                    ),
            )

        for ((table, indexes) in expected) assertEquals(table, indexes, database.indexesOf(table))
    }

    @Test
    fun foreignKeysPointToTheTablesThatExist() = runBlocking {
        val expected =
            mapOf(
                "money_accounts" to emptyList(),
                "money_movements" to
                    listOf(
                        "cash_session_id -> cash_sessions RESTRICT",
                        "customer_ledger_entry_id -> customer_ledger_entries RESTRICT",
                        "expense_id -> expenses RESTRICT",
                        "money_account_id -> money_accounts RESTRICT",
                        "purchase_id -> purchases RESTRICT",
                        "purchase_return_id -> purchase_returns RESTRICT",
                        "reverses_movement_id -> money_movements RESTRICT",
                        "sale_return_id -> sale_returns RESTRICT",
                        "supplier_ledger_entry_id -> supplier_ledger_entries RESTRICT",
                    ),
            )

        for ((table, keys) in expected) assertEquals(table, keys, database.foreignKeysOf(table))
    }

    @Test
    fun anAccountInUseIsNotDeletedAndOneWithoutHistoryIs() = runBlocking {
        val withMovement = accounts.insert(moneyAccount("Banco"))
        val withPurchase = accounts.insert(moneyAccount("Billetera"))
        val withExpense = accounts.insert(moneyAccount("Caja chica"))
        val without = accounts.insert(moneyAccount("Ahorros"))
        movements.insert(moneyMovement(withMovement, "OPENING_BALANCE", 50_000, 50_000))
        database.purchaseDao()
            .insert(purchase(supplierId = null).copy(moneyAccountId = withPurchase))
        val categoryId = database.expenseCategoryDao().insert(expenseCategory("Arriendo"))
        database.expenseDao().insert(expense(categoryId).copy(moneyAccountId = withExpense))

        for (id in listOf(withMovement, withPurchase, withExpense)) {
            constraintFailure(FOREIGN_KEY) { accounts.delete(id) }
        }
        assertEquals(1, accounts.delete(without))
        assertNull(accounts.findById(without))
    }

    @Test
    fun noRowPointsToAnAccountOrAnOriginThatDoesNotExist() = runBlocking {
        val accountId = accounts.insert(moneyAccount("Banco"))
        val customerId = database.customerDao().insert(customer("Ana"))
        val entryId =
            database.customerLedgerEntryDao()
                .insert(customerEntry(customerId, "PAYMENT", -2_000, -2_000))
        val categoryId = database.expenseCategoryDao().insert(expenseCategory("Arriendo"))
        val expenseId = database.expenseDao().insert(expense(categoryId))
        val purchaseId = database.purchaseDao().insert(purchase(supplierId = null))
        val payment = moneyMovement(accountId, "CUSTOMER_PAYMENT", 2_000, 2_000)
        val expensePayment = moneyMovement(accountId, "EXPENSE_PAYMENT", -45_000, -43_000)
        val purchasePayment = moneyMovement(accountId, "PURCHASE_PAYMENT", -20_000, -63_000)

        for (orphan in listOf(
            moneyMovement(MISSING_ID, "OPENING_BALANCE", 50_000, 50_000),
            payment.copy(customerLedgerEntryId = MISSING_ID),
            expensePayment.copy(expenseId = MISSING_ID),
            purchasePayment.copy(purchaseId = MISSING_ID),
        )) {
            constraintFailure(FOREIGN_KEY) { movements.insert(orphan) }
        }
        constraintFailure(FOREIGN_KEY) {
            database.purchaseDao()
                .insert(purchase(supplierId = null).copy(moneyAccountId = MISSING_ID))
        }
        constraintFailure(FOREIGN_KEY) {
            database.expenseDao().insert(expense(categoryId).copy(moneyAccountId = MISSING_ID))
        }

        movements.insert(payment.copy(customerLedgerEntryId = entryId))
        movements.insert(expensePayment.copy(expenseId = expenseId))
        movements.insert(purchasePayment.copy(purchaseId = purchaseId))
        assertEquals(3L, count("SELECT COUNT(*) FROM money_movements"))
    }

    @Test
    fun anArchivedAccountDoesNotReserveItsNameAndSeededOnesDoNotCollide() = runBlocking {
        val first = accounts.insert(moneyAccount("Banco"))

        // Entre vigentes el nombre no se repite: lo impide la guarda, no el índice.
        guardFailure(ACCOUNTS) { accounts.insert(moneyAccount("BANCO")) }
        accounts.setArchivedAt(first, archivedAt = LATER, updatedAt = LATER)
        val second = accounts.insert(moneyAccount("BANCO"))
        // Reactivar la archivada con el nombre de una vigente tampoco pasa.
        guardFailure(ACCOUNTS) { accounts.setArchivedAt(first, null, updatedAt = LATER) }
        assertEquals("banco", accounts.findById(second)?.normalizedName)
        // La clave inicial sí es única en toda la tabla, y las cuentas sin nombre no chocan.
        constraintFailure(UNIQUE) {
            accounts.insert(
                moneyAccount("Otra").copy(systemKey = "CASH", name = null, normalizedName = null),
            )
        }
        accounts.insert(
            moneyAccount("Otra").copy(systemKey = "SAVINGS", name = null, normalizedName = null),
        )
        // Con la inicial, que tampoco tiene nombre propio.
        assertEquals(4L, count("SELECT COUNT(*) FROM money_accounts"))
    }

    private companion object {
        const val MISSING_ID = 999L
        const val ACCOUNTS = "guard:money_accounts"
    }
}

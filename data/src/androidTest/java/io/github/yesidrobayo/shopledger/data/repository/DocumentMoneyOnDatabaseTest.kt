package io.github.yesidrobayo.shopledger.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.backup.InvariantQueries
import io.github.yesidrobayo.shopledger.data.transaction.RestoreLock
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.Voiding
import io.github.yesidrobayo.shopledger.domain.model.expense.ExpenseRequest
import io.github.yesidrobayo.shopledger.domain.model.expense.NewExpense
import io.github.yesidrobayo.shopledger.domain.model.money.AccountCurrency
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountDetails
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountKind
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyDocument
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementType
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyOrigin
import io.github.yesidrobayo.shopledger.domain.model.money.NewMoneyAccount
import io.github.yesidrobayo.shopledger.domain.model.money.NewMoneyMovement
import io.github.yesidrobayo.shopledger.domain.model.purchase.NewPurchase
import io.github.yesidrobayo.shopledger.domain.model.purchase.NewPurchaseLine
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineAmount
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineRequest
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseRequest
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockMovement
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.ExpenseRepository
import io.github.yesidrobayo.shopledger.domain.port.MoneyLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.PurchaseRepository
import io.github.yesidrobayo.shopledger.domain.port.StockLedgerRepository
import io.github.yesidrobayo.shopledger.domain.usecase.expense.RegisterExpenseResult
import io.github.yesidrobayo.shopledger.domain.usecase.expense.RegisterExpenseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.expense.VoidExpenseResult
import io.github.yesidrobayo.shopledger.domain.usecase.expense.VoidExpenseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.money.DocumentMoney
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.RegisterPurchaseResult
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.RegisterPurchaseUseCase
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.VoidPurchaseResult
import io.github.yesidrobayo.shopledger.domain.usecase.purchase.VoidPurchaseUseCase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El gasto y la compra que pagan desde una cuenta, sobre la base real y sus guardas: el documento y
 * su `EXPENSE_PAYMENT` o `PURCHASE_PAYMENT` entran en una sola transacción, y su anulación escribe
 * el `MOVEMENT_VOID` en la misma que la marca. Una falla inyectada en cada escritura no deja nada a
 * medias, y las consultas de invariantes quedan en cero filas.
 *
 * Las cuentas, los productos y los montos son inventados.
 */
@RunWith(AndroidJUnit4::class)
internal class DocumentMoneyOnDatabaseTest : RepositoryTest() {
    private val settings by lazy { RoomAppSettingsRepository(database, RestoreLock { false }) }
    private val accounts by lazy { RoomMoneyAccountRepository(database) }
    private val money by lazy { RoomMoneyLedgerRepository(database) }
    private val expenses by lazy { RoomExpenseRepository(database) }
    private val purchases by lazy { RoomPurchaseRepository(database) }
    private val products by lazy { RoomProductRepository(database) }
    private val stock by lazy { RoomStockLedgerRepository(database) }

    /** La escritura número N falla; `null` no falla nunca. */
    private var failAt: Int? = null
    private var writes = 0

    private var bankId = 0L
    private var flourId = 0L

    private fun tick() {
        writes++
        if (writes == failAt) throw InjectedWriteFailure()
    }

    private val countedExpenses by lazy {
        object : ExpenseRepository by expenses {
            override suspend fun insert(expense: NewExpense): Long {
                tick()
                return expenses.insert(expense)
            }

            override suspend fun markVoided(id: Long, voiding: Voiding) {
                tick()
                expenses.markVoided(id, voiding)
            }
        }
    }

    private val countedPurchases by lazy {
        object : PurchaseRepository by purchases {
            override suspend fun insert(purchase: NewPurchase): Long {
                tick()
                return purchases.insert(purchase)
            }

            override suspend fun insertLine(purchaseId: Long, line: NewPurchaseLine): Long {
                tick()
                return purchases.insertLine(purchaseId, line)
            }

            override suspend fun markVoided(id: Long, voiding: Voiding) {
                tick()
                purchases.markVoided(id, voiding)
            }
        }
    }

    private val countedStock by lazy {
        object : StockLedgerRepository by stock {
            override suspend fun append(movement: NewStockMovement): Long {
                tick()
                return stock.append(movement)
            }
        }
    }

    private val countedMoney by lazy {
        object : MoneyLedgerRepository by money {
            override suspend fun append(movement: NewMoneyMovement): Long {
                tick()
                return money.append(movement)
            }
        }
    }

    private val documentMoney by lazy { DocumentMoney(accounts, countedMoney) }

    private val registerExpense by lazy {
        RegisterExpenseUseCase(
            runner,
            settings,
            countedExpenses,
            RoomExpenseCategoryRepository(database),
            Clock { AT },
            documentMoney,
        )
    }

    private val voidExpense by lazy {
        VoidExpenseUseCase(runner, countedExpenses, Clock { LATER_AT }, settings, documentMoney)
    }

    private val registerPurchase by lazy {
        RegisterPurchaseUseCase(
            transactions = runner,
            settings = settings,
            products = products,
            purchases = countedPurchases,
            suppliers = RoomSupplierRepository(database),
            units = RoomUnitRepository(database),
            systemRows = RoomSystemRowRepository(database),
            ledger = countedStock,
            taxRates = RoomTaxRateRepository(database),
            clock = Clock { AT },
            money = documentMoney,
        )
    }

    private val voidPurchase by lazy {
        VoidPurchaseUseCase(
            transactions = runner,
            settings = settings,
            products = products,
            purchases = countedPurchases,
            ledger = countedStock,
            clock = Clock { LATER_AT },
            money = documentMoney,
        )
    }

    @Before
    fun createAccountAndProduct() = runBlocking {
        bankId = commit {
            accounts.insert(
                NewMoneyAccount(
                    MoneyAccountDetails(
                        "Banco inventado",
                        "banco inventado",
                        MoneyAccountKind.BANK,
                    ),
                    AccountCurrency.of(settings.get().regional.currency),
                    AT,
                ),
            )
        }
        flourId = commit { products.insert(newProduct("Harina inventada")) }
    }

    private suspend fun otherCategory(): Long =
        count("SELECT id FROM expense_categories WHERE system_key = 'OTHER'")

    private suspend fun expense(): ExpenseRequest =
        ExpenseRequest(Money(3_000), otherCategory(), "Alquiler inventado", moneyAccountId = bankId)

    private fun purchase(): PurchaseRequest = PurchaseRequest(
        lines = listOf(
            PurchaseLineRequest(
                flourId,
                QuantityMilli(2_000),
                PurchaseLineAmount.UnitCost(Money(1_500)),
            ),
        ),
        moneyAccountId = bankId,
    )

    private suspend fun balance(): Money? = accounts.findById(bankId)?.balance

    /** Corre [block] con una falla en cada escritura hasta que pasa, y devuelve cuántas hubo. */
    private suspend fun failEachWrite(check: suspend () -> Unit, block: suspend () -> Unit): Int {
        var at = 1
        while (true) {
            failAt = at
            writes = 0
            val failed = runCatching { block() }.exceptionOrNull() ?: break
            assertTrue(
                "Escritura $at: ${failed.javaClass.simpleName}",
                failed is InjectedWriteFailure,
            )
            check()
            at++
        }
        failAt = null
        return at - 1
    }

    @Test
    fun anExpenseFromAnAccountWritesItsPaymentAndPassesEveryInvariant() = runBlocking {
        val id = (registerExpense(expense()) as RegisterExpenseResult.Registered).expenseId

        val payment = money.findByExpense(id).single()
        assertEquals(MoneyMovementType.EXPENSE_PAYMENT, payment.type)
        assertEquals(Money(-3_000), payment.amount)
        assertEquals(Money(-3_000), balance())
        assertEquals(MoneyDocument.Expense(id), money.findDocument(payment.id))
        assertInvariantsHold()
    }

    @Test
    fun aFailureAtEachWriteOfTheExpenseAndItsVoidLeavesNothingHalfWay() = runBlocking {
        val registerWrites = failEachWrite({
            assertEquals(0L, count("SELECT COUNT(*) FROM expenses"))
            assertEquals(0L, count("SELECT COUNT(*) FROM money_movements"))
            assertEquals(Money.ZERO, balance())
        }) { registerExpense(expense()) }
        // El gasto y su movimiento.
        assertEquals(2, registerWrites)
        val id = count("SELECT id FROM expenses")

        val voidWrites = failEachWrite({
            assertEquals(1L, count("SELECT COUNT(*) FROM expenses WHERE voided_at IS NULL"))
            assertEquals(1L, count("SELECT COUNT(*) FROM money_movements"))
            assertEquals(Money(-3_000), balance())
        }) { assertEquals(VoidExpenseResult.Voided, voidExpense(id, VoidReason.ENTRY_ERROR, null)) }
        // La marca de anulado y la devolución del dinero.
        assertEquals(2, voidWrites)
        val payment = money.findByExpense(id).single()
        val void = money.findByAccount(bankId).last()
        assertEquals(MoneyOrigin.Void(payment.id), void.draft.origin)
        assertEquals(Money.ZERO, balance())
        assertInvariantsHold()
    }

    @Test
    fun aPurchaseFromAnAccountWritesItsPaymentAndPassesEveryInvariant() = runBlocking {
        val id = (registerPurchase(purchase()) as RegisterPurchaseResult.Registered).purchaseId

        val payment = money.findByPurchase(id).single()
        assertEquals(MoneyMovementType.PURCHASE_PAYMENT, payment.type)
        assertEquals(Money(-3_000), payment.amount)
        assertEquals(Money(-3_000), balance())
        assertEquals(MoneyDocument.Purchase(id), money.findDocument(payment.id))
        assertInvariantsHold()
    }

    @Test
    fun aFailureAtEachWriteOfThePurchaseAndItsVoidLeavesNothingHalfWay() = runBlocking {
        val registerWrites = failEachWrite({
            assertEquals(0L, count("SELECT COUNT(*) FROM purchases"))
            assertEquals(0L, count("SELECT COUNT(*) FROM money_movements"))
            assertEquals(Money.ZERO, balance())
        }) { registerPurchase(purchase()) }
        // La compra, su línea, su fila de stock y su pago.
        assertEquals(4, registerWrites)
        val id = count("SELECT id FROM purchases")

        val voidWrites = failEachWrite({
            assertEquals(1L, count("SELECT COUNT(*) FROM purchases WHERE voided_at IS NULL"))
            assertEquals(1L, count("SELECT COUNT(*) FROM money_movements"))
            assertEquals(Money(-3_000), balance())
        }) {
            assertEquals(VoidPurchaseResult.Voided, voidPurchase(id, VoidReason.ENTRY_ERROR, null))
        }
        // La marca de anulada, la fila de stock que sale y la devolución del dinero.
        assertEquals(3, voidWrites)
        assertEquals(Money.ZERO, balance())
        assertInvariantsHold()
    }

    private suspend fun assertInvariantsHold() {
        for (query in InvariantQueries.MODEL) {
            val rows = query.sql.trimEnd().removeSuffix(";")
            assertEquals(query.label, 0L, count("SELECT COUNT(*) FROM ($rows)"))
        }
    }

    private class InjectedWriteFailure : RuntimeException("Falla a propósito")
}

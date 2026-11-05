package io.github.yesidrobayo.shopledger.data.seed

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.customer.CustomerLedgerEntryEntity
import io.github.yesidrobayo.shopledger.data.local.customer.LoyaltyLedgerEntryEntity
import io.github.yesidrobayo.shopledger.data.local.money.MoneyMovementEntity
import io.github.yesidrobayo.shopledger.data.local.payable.SupplierLedgerEntryEntity
import io.github.yesidrobayo.shopledger.data.repository.toStoredMillis
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import kotlin.time.Instant

/**
 * Los libros de dinero, de clientes, de proveedores y de puntos del negocio inventado.
 *
 * Siguen el patrón del libro de stock: cada fila guarda el saldo que deja, el anterior más su
 * monto, y la cabecera guarda la suma. El saldo de cada cuenta, cliente o proveedor se lleva aquí
 * en memoria, y las cabeceras se escriben al cerrar cada transacción, con [flush], en la misma
 * transacción que sus filas: ninguna cabecera queda confirmada sin su libro, ni al revés.
 *
 * Todavía no hay repositorios para estos libros, así que escribe con sus DAO, dentro de la
 * transacción del runner: cada fila pasa igual por sus guardas.
 */
internal class SeedBooks(private val database: AppDatabase) {
    private val accountBalances = LinkedHashMap<Long, Long>()
    private val customerBalances = LinkedHashMap<Long, Long>()
    private val customerPoints = LinkedHashMap<Long, Long>()
    private val supplierBalances = LinkedHashMap<Long, Long>()
    private val dirtyAccounts = LinkedHashSet<Long>()
    private val dirtyCustomers = LinkedHashSet<Long>()
    private val dirtyPoints = LinkedHashSet<Long>()
    private val dirtySuppliers = LinkedHashSet<Long>()

    fun customerBalance(customerId: Long): Long = customerBalances[customerId] ?: 0L

    /** Agrega un movimiento de dinero a [money] y devuelve la fila escrita. */
    suspend fun money(money: MoneyEntry, at: Instant, date: BusinessDate): BookEntry {
        val after = Math.addExact(accountBalances[money.accountId] ?: 0L, money.amount)
        val id =
            database.moneyMovementDao().insert(
                MoneyMovementEntity(
                    moneyAccountId = money.accountId,
                    type = money.type,
                    amount = money.amount,
                    balanceAfter = after,
                    availableDate = money.availableDate?.toString(),
                    transferGroupId = null,
                    fxRateMicro = null,
                    salePaymentId = money.salePaymentId,
                    customerLedgerEntryId = money.customerLedgerEntryId,
                    supplierLedgerEntryId = money.supplierLedgerEntryId,
                    expenseId = money.expenseId,
                    purchaseId = money.purchaseId,
                    saleReturnId = null,
                    purchaseReturnId = null,
                    cashSessionId = null,
                    reversesMovementId = money.reversesMovementId,
                    note = null,
                    occurredAt = at.toStoredMillis(),
                    businessDate = date.toString(),
                    createdAt = at.toStoredMillis(),
                ),
            )
        accountBalances[money.accountId] = after
        dirtyAccounts += money.accountId
        return BookEntry(id, money.accountId, money.amount)
    }

    /** Anula un movimiento de dinero con la cantidad opuesta, en la misma cuenta. */
    suspend fun voidMoney(original: BookEntry, at: Instant, date: BusinessDate) {
        money(
            MoneyEntry(
                accountId = original.ownerId,
                type = MONEY_VOID,
                amount = -original.amount,
                reversesMovementId = original.id,
            ),
            at,
            date,
        )
    }

    /** Agrega una fila al libro del cliente: deuda positiva, pago negativo. */
    suspend fun customer(
        customerId: Long,
        type: String,
        amount: Long,
        saleId: Long?,
        reverses: Long?,
        dueDate: BusinessDate?,
        at: Instant,
        date: BusinessDate,
    ): BookEntry {
        val after = Math.addExact(customerBalance(customerId), amount)
        val id =
            database.customerLedgerEntryDao().insert(
                CustomerLedgerEntryEntity(
                    customerId = customerId,
                    type = type,
                    amount = amount,
                    balanceAfter = after,
                    saleId = saleId,
                    saleReturnId = null,
                    reversesEntryId = reverses,
                    dueDate = dueDate?.toString(),
                    note = null,
                    occurredAt = at.toStoredMillis(),
                    businessDate = date.toString(),
                    createdAt = at.toStoredMillis(),
                ),
            )
        customerBalances[customerId] = after
        dirtyCustomers += customerId
        return BookEntry(id, customerId, amount)
    }

    /** Agrega una fila al libro de puntos del cliente. Los puntos no son dinero. */
    suspend fun points(
        customerId: Long,
        type: String,
        points: Long,
        saleId: Long?,
        reverses: Long?,
        at: Instant,
        date: BusinessDate,
    ): BookEntry {
        val after = Math.addExact(customerPoints[customerId] ?: 0L, points)
        val id =
            database.loyaltyLedgerEntryDao().insert(
                LoyaltyLedgerEntryEntity(
                    customerId = customerId,
                    type = type,
                    points = points,
                    balanceAfter = after,
                    saleId = saleId,
                    reversesEntryId = reverses,
                    note = null,
                    occurredAt = at.toStoredMillis(),
                    businessDate = date.toString(),
                    createdAt = at.toStoredMillis(),
                ),
            )
        customerPoints[customerId] = after
        dirtyPoints += customerId
        return BookEntry(id, customerId, points)
    }

    /** Agrega una fila al libro del proveedor: deuda positiva, pago negativo. */
    suspend fun supplier(
        supplierId: Long,
        type: String,
        amount: Long,
        purchaseId: Long?,
        dueDate: BusinessDate?,
        at: Instant,
        date: BusinessDate,
    ): BookEntry {
        val after = Math.addExact(supplierBalances[supplierId] ?: 0L, amount)
        val id =
            database.supplierLedgerEntryDao().insert(
                SupplierLedgerEntryEntity(
                    supplierId = supplierId,
                    type = type,
                    amount = amount,
                    balanceAfter = after,
                    purchaseId = purchaseId,
                    purchaseReturnId = null,
                    reversesEntryId = null,
                    dueDate = dueDate?.toString(),
                    note = null,
                    occurredAt = at.toStoredMillis(),
                    businessDate = date.toString(),
                    createdAt = at.toStoredMillis(),
                ),
            )
        supplierBalances[supplierId] = after
        dirtySuppliers += supplierId
        return BookEntry(id, supplierId, amount)
    }

    /**
     * Deja en cada cabecera tocada la suma de su libro. Va al final de cada transacción, dentro de
     * ella, en el orden en que se tocaron.
     */
    suspend fun flush(at: Instant) {
        val millis = at.toStoredMillis()
        for (id in dirtyAccounts) {
            val balance = balanceOf(accountBalances, id)
            requireOne(database.moneyAccountDao().setBalance(id, balance, millis))
        }
        for (id in dirtyCustomers) {
            requireOne(
                database.customerDao().setBalance(id, balanceOf(customerBalances, id), millis),
            )
        }
        for (id in dirtyPoints) {
            val points = balanceOf(customerPoints, id)
            requireOne(database.customerDao().setPointsBalance(id, points, millis))
        }
        for (id in dirtySuppliers) {
            val balance = balanceOf(supplierBalances, id)
            requireOne(database.supplierDao().setBalance(id, balance, millis))
        }
        dirtyAccounts.clear()
        dirtyCustomers.clear()
        dirtyPoints.clear()
        dirtySuppliers.clear()
    }

    private fun requireOne(changed: Int) {
        check(changed == 1) { "Una cabecera tocada no existe" }
    }

    private fun balanceOf(balances: Map<Long, Long>, id: Long): Long =
        requireNotNull(balances[id]) { "Una cabecera tocada sin saldo" }

    companion object {
        const val MONEY_VOID = "MOVEMENT_VOID"
    }
}

/**
 * Un movimiento de dinero por escribir. [type] es el nombre guardado del tipo, y solo lleva el
 * documento que ese tipo exige.
 */
internal class MoneyEntry(
    val accountId: Long,
    val type: String,
    val amount: Long,
    val availableDate: BusinessDate? = null,
    val salePaymentId: Long? = null,
    val customerLedgerEntryId: Long? = null,
    val supplierLedgerEntryId: Long? = null,
    val expenseId: Long? = null,
    val purchaseId: Long? = null,
    val reversesMovementId: Long? = null,
)

/** Una fila ya escrita en un libro: su id, de quién es el libro y su monto con signo. */
internal class BookEntry(val id: Long, val ownerId: Long, val amount: Long)

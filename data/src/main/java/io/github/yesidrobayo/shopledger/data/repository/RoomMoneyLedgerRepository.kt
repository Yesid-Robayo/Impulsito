package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.money.MoneyAccountDao
import io.github.yesidrobayo.shopledger.data.local.money.MoneyMovementDao
import io.github.yesidrobayo.shopledger.data.local.money.MoneyMovementEntity
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovement
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementDraft
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementType
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyOrigin
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyOriginKind
import io.github.yesidrobayo.shopledger.domain.model.money.NewMoneyMovement
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import io.github.yesidrobayo.shopledger.domain.port.MoneyLedgerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * El libro de dinero sobre `money_movements`, con su copia derivada: el saldo de `money_accounts`.
 *
 * Las dos escrituras de [append] van en la transacción del caso de uso: si cualquiera falla, la
 * transacción se deshace entera, y ningún saldo queda sin su movimiento ni un movimiento sin su saldo.
 *
 * Recibe los DAO por separado para que las pruebas puedan hacer fallar cada paso.
 */
internal class RoomMoneyLedgerRepository(
    private val movements: MoneyMovementDao,
    private val accounts: MoneyAccountDao,
    private val writes: RepositoryWrites,
) : MoneyLedgerRepository {
    constructor(database: AppDatabase) : this(
        database.moneyMovementDao(),
        database.moneyAccountDao(),
        RepositoryWrites(database),
    )

    override fun observeByAccount(accountId: Long): Flow<List<MoneyMovement>> = movements
        .observeByAccount(accountId)
        .map { rows -> rows.map(::toMovement) }
        .distinctUntilChanged()

    override suspend fun findById(id: Long): MoneyMovement? =
        movements.findById(id)?.let(::toMovement)

    override suspend fun findByAccount(accountId: Long): List<MoneyMovement> =
        movements.findByAccount(accountId).map(::toMovement)

    override suspend fun findByTransferGroup(groupId: String): List<MoneyMovement> =
        movements.findByTransferGroup(groupId).map(::toMovement)

    override suspend fun findBySupplierLedgerEntry(
        supplierLedgerEntryId: Long,
    ): List<MoneyMovement> =
        movements.findBySupplierLedgerEntry(supplierLedgerEntryId).map(::toMovement)

    override suspend fun isCompensated(movementId: Long): Boolean =
        movements.isCompensated(movementId)

    /**
     * Agrega el movimiento y mueve el saldo de su cuenta, en este orden:
     *
     * 1. Comprueba que el saldo que el movimiento dice dejar sea el de la cuenta más su monto. Si no,
     *    el caso de uso calculó con una cuenta vieja, y el libro quedaría descuadrado para siempre.
     * 2. Inserta el movimiento. Su guarda revisa tipo, signo, origen, transferencia y anulación.
     * 3. Deja la cuenta con ese saldo.
     *
     * Un saldo que no cuadra o que no cabe en `Long` sale como regla rota, y la transacción se
     * deshace.
     */
    override suspend fun append(movement: NewMoneyMovement): Long = writes.inTransaction {
        val draft = movement.draft
        val account = accounts.findById(draft.accountId) ?: brokenRow()
        val expected =
            try {
                Math.addExact(account.balance, draft.amount.minor)
            } catch (_: ArithmeticException) {
                brokenRow()
            }
        if (expected != movement.balanceAfter.minor) brokenRow()
        val id = movements.insert(toEntity(movement))
        requireOneRow(
            accounts.setBalance(
                id = draft.accountId,
                balance = movement.balanceAfter.minor,
                updatedAt = movement.createdAt.toStoredMillis(),
            ),
        )
        id
    }

    private fun toEntity(movement: NewMoneyMovement): MoneyMovementEntity {
        val draft = movement.draft
        val origin = draft.origin
        return MoneyMovementEntity(
            moneyAccountId = draft.accountId,
            type = draft.type.name,
            amount = draft.amount.minor,
            balanceAfter = movement.balanceAfter.minor,
            availableDate = draft.availableDate?.toStored(),
            transferGroupId = (origin as? MoneyOrigin.Transfer)?.groupId,
            fxRateMicro = (origin as? MoneyOrigin.Transfer)?.fxRateMicro,
            salePaymentId = (origin as? MoneyOrigin.SalePayment)?.salePaymentId,
            customerLedgerEntryId = (origin as? MoneyOrigin.CustomerPayment)?.customerLedgerEntryId,
            supplierLedgerEntryId = (origin as? MoneyOrigin.SupplierPayment)?.supplierLedgerEntryId,
            expenseId = (origin as? MoneyOrigin.Expense)?.expenseId,
            purchaseId = (origin as? MoneyOrigin.Purchase)?.purchaseId,
            saleReturnId = (origin as? MoneyOrigin.SaleReturn)?.saleReturnId,
            purchaseReturnId = (origin as? MoneyOrigin.PurchaseReturn)?.purchaseReturnId,
            cashSessionId = draft.cashSessionId,
            reversesMovementId = (origin as? MoneyOrigin.Void)?.reversesMovementId,
            note = draft.note,
            occurredAt = movement.time.occurredAt.toStoredMillis(),
            businessDate = movement.time.businessDate.toStored(),
            createdAt = movement.createdAt.toStoredMillis(),
        )
    }
}

/**
 * El movimiento guardado en [row]. Su origen sale de la columna que exige su tipo: una fila sin ella,
 * o con un signo que su tipo no admite, es una fila que su guarda no habría dejado entrar.
 */
internal fun toMovement(row: MoneyMovementEntity): MoneyMovement {
    val type = storedEnum<MoneyMovementType>(row.type)
    val origin = when (type.originKind) {
        MoneyOriginKind.NONE -> MoneyOrigin.None

        MoneyOriginKind.SALE_PAYMENT -> MoneyOrigin.SalePayment(row.salePaymentId ?: brokenRow())

        MoneyOriginKind.CUSTOMER_PAYMENT ->
            MoneyOrigin.CustomerPayment(row.customerLedgerEntryId ?: brokenRow())

        MoneyOriginKind.SUPPLIER_PAYMENT ->
            MoneyOrigin.SupplierPayment(row.supplierLedgerEntryId ?: brokenRow())

        MoneyOriginKind.EXPENSE -> MoneyOrigin.Expense(row.expenseId ?: brokenRow())

        MoneyOriginKind.PURCHASE -> MoneyOrigin.Purchase(row.purchaseId ?: brokenRow())

        MoneyOriginKind.SALE_RETURN -> MoneyOrigin.SaleReturn(row.saleReturnId ?: brokenRow())

        MoneyOriginKind.PURCHASE_RETURN ->
            MoneyOrigin.PurchaseReturn(row.purchaseReturnId ?: brokenRow())

        MoneyOriginKind.TRANSFER ->
            MoneyOrigin.Transfer(row.transferGroupId ?: brokenRow(), row.fxRateMicro)

        MoneyOriginKind.VOID -> MoneyOrigin.Void(row.reversesMovementId ?: brokenRow())
    }
    if (!type.allows(row.amount)) brokenRow()
    if (type == MoneyMovementType.CASH_DIFFERENCE && row.cashSessionId == null) brokenRow()
    if (row.note != null && row.note.length !in 1..MoneyMovementDraft.NOTE_MAX) brokenRow()
    return MoneyMovement(
        id = row.id,
        draft = MoneyMovementDraft(
            accountId = row.moneyAccountId,
            type = type,
            amount = Money(row.amount),
            origin = origin,
            cashSessionId = row.cashSessionId,
            availableDate = row.availableDate?.let(::storedDate),
            note = row.note,
        ),
        balanceAfter = Money(row.balanceAfter),
        time = RecordTime(row.occurredAt.toInstant(), storedDate(row.businessDate)),
        createdAt = row.createdAt.toInstant(),
    )
}

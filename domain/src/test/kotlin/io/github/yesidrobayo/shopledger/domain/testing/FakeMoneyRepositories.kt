package io.github.yesidrobayo.shopledger.domain.testing

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.money.AccountCurrency
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccount
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountDetails
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountKind
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovement
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyMovementType
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyOrigin
import io.github.yesidrobayo.shopledger.domain.model.money.NewMoneyAccount
import io.github.yesidrobayo.shopledger.domain.model.money.NewMoneyMovement
import io.github.yesidrobayo.shopledger.domain.port.MoneyAccountRepository
import io.github.yesidrobayo.shopledger.domain.port.MoneyLedgerRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Un [MoneyAccountRepository] sobre [InMemoryDatabase]. Repite lo que la base impide: dos vigentes con
 * el mismo nombre, y cambiar el saldo fuera del libro.
 */
internal class FakeMoneyAccountRepository(private val database: InMemoryDatabase) :
    MoneyAccountRepository {
    override fun observe(filter: ArchiveFilter): Flow<List<MoneyAccount>> = database.tables
        .map { tables ->
            tables.moneyAccounts.values
                .filter { it.isArchived == (filter == ArchiveFilter.ARCHIVED) }
                .sortedBy { it.id }
        }.distinctUntilChanged()

    override suspend fun findById(id: Long): MoneyAccount? = database.read().moneyAccounts[id]

    override suspend fun findActiveByNormalizedName(normalizedName: String): MoneyAccount? =
        database.read().moneyAccounts.values.firstOrNull {
            !it.isArchived && it.details.normalizedName == normalizedName
        }

    override suspend fun insert(account: NewMoneyAccount): Long {
        account.details.normalizedName?.let { check(findActiveByNormalizedName(it) == null) }
        val id = database.nextId(TABLE)
        val row = MoneyAccount(
            id = id,
            systemKey = null,
            details = account.details,
            currency = account.currency,
            balance = Money.ZERO,
            archivedAt = null,
            createdAt = account.createdAt,
            updatedAt = account.createdAt,
        )
        database.write { it.copy(moneyAccounts = it.moneyAccounts + (id to row)) }
        return id
    }

    override suspend fun update(id: Long, details: MoneyAccountDetails, updatedAt: Instant) {
        val account = checkNotNull(database.read().moneyAccounts[id]) { "La cuenta no existe" }
        database.write {
            it.copy(
                moneyAccounts = it.moneyAccounts +
                    (id to account.copy(details = details, updatedAt = updatedAt)),
            )
        }
    }

    override suspend fun setArchivedAt(id: Long, archivedAt: Instant?, updatedAt: Instant) {
        val account = checkNotNull(database.read().moneyAccounts[id]) { "La cuenta no existe" }
        database.write {
            it.copy(
                moneyAccounts = it.moneyAccounts +
                    (id to account.copy(archivedAt = archivedAt, updatedAt = updatedAt)),
            )
        }
    }

    private companion object {
        const val TABLE = "money_accounts"
    }
}

/**
 * Un [MoneyLedgerRepository] sobre [InMemoryDatabase]. Como la base y su guarda: solo agrega, exige
 * que el saldo nuevo sea el de la cuenta más el monto, y que una anulación compense una sola vez un
 * movimiento de la misma cuenta, con el monto opuesto, que no sea otra anulación.
 */
internal class FakeMoneyLedgerRepository(private val database: InMemoryDatabase) :
    MoneyLedgerRepository {
    override fun observeByAccount(accountId: Long): Flow<List<MoneyMovement>> = database.tables
        .map { tables -> tables.moneyMovements.values.filter { it.accountId == accountId } }
        .map { rows -> rows.sortedBy { it.id } }
        .distinctUntilChanged()

    override suspend fun findById(id: Long): MoneyMovement? = database.read().moneyMovements[id]

    override suspend fun findByAccount(accountId: Long): List<MoneyMovement> =
        database.read().moneyMovements.values.filter {
            it.accountId == accountId
        }.sortedBy { it.id }

    override suspend fun findByTransferGroup(groupId: String): List<MoneyMovement> =
        database.read().moneyMovements.values
            .filter { (it.draft.origin as? MoneyOrigin.Transfer)?.groupId == groupId }
            .sortedBy { it.id }

    override suspend fun isCompensated(movementId: Long): Boolean =
        database.read().moneyMovements.values.any {
            (it.draft.origin as? MoneyOrigin.Void)?.reversesMovementId == movementId
        }

    override suspend fun append(movement: NewMoneyMovement): Long {
        val draft = movement.draft
        val tables = database.read()
        val account = checkNotNull(tables.moneyAccounts[draft.accountId]) { "La cuenta no existe" }
        check(
            Math.addExact(account.balance.minor, draft.amount.minor) == movement.balanceAfter.minor,
        ) {
            "El saldo nuevo no es el de la cuenta más el monto"
        }
        val origin = draft.origin
        if (origin is MoneyOrigin.Void) {
            val target = checkNotNull(tables.moneyMovements[origin.reversesMovementId]) {
                "La anulación no tiene su movimiento"
            }
            check(target.accountId == draft.accountId && target.amount == -draft.amount)
            check(target.type != MoneyMovementType.MOVEMENT_VOID)
            check(!isCompensated(target.id)) { "El movimiento ya está anulado" }
        }
        val id = database.nextId(TABLE)
        val row = MoneyMovement(id, draft, movement.balanceAfter, movement.time, movement.createdAt)
        database.write {
            it.copy(
                moneyMovements = it.moneyMovements + (id to row),
                moneyAccounts = it.moneyAccounts +
                    (account.id to account.copy(balance = movement.balanceAfter)),
            )
        }
        return id
    }

    private companion object {
        const val TABLE = "money_movements"
    }
}

/**
 * Siembra la cuenta inicial de efectivo, sin nombre propio y sin movimientos, en [currency], como la
 * crea la base. Devuelve su id.
 */
internal fun InMemoryDatabase.seedCashAccount(currency: AccountCurrency, at: Instant): Long {
    var id = 0L
    seed { tables ->
        id = (tables.lastIds["money_accounts"] ?: 0L) + 1L
        tables.copy(
            moneyAccounts = tables.moneyAccounts + (
                id to MoneyAccount(
                    id = id,
                    systemKey = "CASH",
                    details = MoneyAccountDetails(null, null, MoneyAccountKind.CASH),
                    currency = currency,
                    balance = Money.ZERO,
                    archivedAt = null,
                    createdAt = at,
                    updatedAt = at,
                )
                ),
            lastIds = tables.lastIds + ("money_accounts" to id),
        )
    }
    return id
}

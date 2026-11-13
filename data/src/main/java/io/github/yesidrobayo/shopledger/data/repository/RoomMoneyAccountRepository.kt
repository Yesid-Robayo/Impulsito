package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.money.MoneyAccountDao
import io.github.yesidrobayo.shopledger.data.local.money.MoneyAccountEntity
import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.money.AccountCurrency
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccount
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountDetails
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccountKind
import io.github.yesidrobayo.shopledger.domain.model.money.NewMoneyAccount
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.port.MoneyAccountRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Las cuentas de dinero sobre `money_accounts`. El saldo no se escribe aquí: lo mueve el libro de
 * dinero, en la transacción que agrega cada movimiento.
 */
internal class RoomMoneyAccountRepository(
    private val accounts: MoneyAccountDao,
    private val writes: RepositoryWrites,
) : MoneyAccountRepository {
    constructor(
        database: AppDatabase,
    ) : this(database.moneyAccountDao(), RepositoryWrites(database))

    override fun observe(filter: ArchiveFilter): Flow<List<MoneyAccount>> = when (filter) {
        ArchiveFilter.ACTIVE -> accounts.observeActive()
        ArchiveFilter.ARCHIVED -> accounts.observeArchived()
    }.map { rows -> rows.map(::toAccount) }.distinctUntilChanged()

    override suspend fun findById(id: Long): MoneyAccount? = accounts.findById(id)?.let(::toAccount)

    override suspend fun findActiveByNormalizedName(normalizedName: String): MoneyAccount? =
        accounts.findActiveByNormalizedName(normalizedName)?.let(::toAccount)

    override suspend fun insert(account: NewMoneyAccount): Long = writes.inTransaction {
        val at = account.createdAt.toStoredMillis()
        accounts.insert(
            MoneyAccountEntity(
                systemKey = null,
                name = account.details.name,
                normalizedName = account.details.normalizedName,
                kind = account.details.kind.name,
                currencyCode = account.currency.code.code,
                currencyDecimals = account.currency.decimals.toLong(),
                balance = 0L,
                archivedAt = null,
                createdAt = at,
                updatedAt = at,
            ),
        )
    }

    override suspend fun update(id: Long, details: MoneyAccountDetails, updatedAt: Instant) =
        writes.inTransaction {
            requireOneRow(
                accounts.update(
                    id = id,
                    name = details.name,
                    normalizedName = details.normalizedName,
                    kind = details.kind.name,
                    updatedAt = updatedAt.toStoredMillis(),
                ),
            )
        }

    override suspend fun setArchivedAt(id: Long, archivedAt: Instant?, updatedAt: Instant) =
        writes.inTransaction {
            requireOneRow(
                accounts.setArchivedAt(
                    id = id,
                    archivedAt = archivedAt?.toStoredMillis(),
                    updatedAt = updatedAt.toStoredMillis(),
                ),
            )
        }
}

/** La cuenta guardada en [row]. Una moneda o una clase que su guarda no admite es una fila rota. */
internal fun toAccount(row: MoneyAccountEntity): MoneyAccount {
    val decimals = row.currencyDecimals
    if (decimals !in 0L..AccountCurrency.MAX_DECIMALS.toLong()) brokenRow()
    if (row.name == null && row.systemKey == null) brokenRow()
    return MoneyAccount(
        id = row.id,
        systemKey = row.systemKey,
        details = MoneyAccountDetails(
            name = row.name,
            normalizedName = row.normalizedName,
            kind = storedEnum<MoneyAccountKind>(row.kind),
        ),
        currency = AccountCurrency(
            CurrencyCode.of(row.currencyCode) ?: brokenRow(),
            decimals.toInt(),
        ),
        balance = Money(row.balance),
        archivedAt = row.archivedAt?.toInstant(),
        createdAt = row.createdAt.toInstant(),
        updatedAt = row.updatedAt.toInstant(),
    )
}

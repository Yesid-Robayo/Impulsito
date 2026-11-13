package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.cash.CashDenominationDao
import io.github.yesidrobayo.shopledger.data.local.cash.CashDenominationEntity
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.cash.CashDenomination
import io.github.yesidrobayo.shopledger.domain.port.CashDenominationRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Los billetes y monedas de la persona sobre `cash_denominations`. La base nace sin ninguno. */
internal class RoomCashDenominationRepository(
    private val denominations: CashDenominationDao,
    private val writes: RepositoryWrites,
) : CashDenominationRepository {
    constructor(database: AppDatabase) : this(
        database.cashDenominationDao(),
        RepositoryWrites(database),
    )

    override fun observeAll(): Flow<List<CashDenomination>> = denominations
        .observeAll()
        .map { rows -> rows.map(::toDenomination) }
        .distinctUntilChanged()

    override suspend fun findById(id: Long): CashDenomination? =
        denominations.findById(id)?.let(::toDenomination)

    override suspend fun insert(value: Money, sortOrder: Int, createdAt: Instant): Long =
        writes.inTransaction {
            val at = createdAt.toStoredMillis()
            denominations.insert(
                CashDenominationEntity(
                    value = value.minor,
                    isActive = true.toStoredFlag(),
                    sortOrder = sortOrder.toLong(),
                    createdAt = at,
                    updatedAt = at,
                ),
            )
        }

    override suspend fun update(
        id: Long,
        value: Money,
        isActive: Boolean,
        sortOrder: Int,
        updatedAt: Instant,
    ) {
        writes.inTransaction {
            requireOneRow(
                denominations.update(
                    id,
                    value.minor,
                    isActive.toStoredFlag(),
                    sortOrder.toLong(),
                    updatedAt.toStoredMillis(),
                ),
            )
        }
    }

    private fun toDenomination(row: CashDenominationEntity) = CashDenomination(
        id = row.id,
        value = Money(row.value),
        isActive = row.isActive.toFlag(),
        sortOrder = row.sortOrder.toStoredInt(),
        createdAt = row.createdAt.toInstant(),
        updatedAt = row.updatedAt.toInstant(),
    )
}

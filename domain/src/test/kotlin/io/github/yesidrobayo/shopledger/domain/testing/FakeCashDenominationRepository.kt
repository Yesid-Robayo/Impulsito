package io.github.yesidrobayo.shopledger.domain.testing

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.cash.CashDenomination
import io.github.yesidrobayo.shopledger.domain.port.CashDenominationRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Un [CashDenominationRepository] sobre [InMemoryDatabase], sin denominaciones iniciales. */
internal class FakeCashDenominationRepository(private val database: InMemoryDatabase) :
    CashDenominationRepository {
    override fun observeAll(): Flow<List<CashDenomination>> = database.tables.map { tables ->
        tables.cashDenominations.values.sortedWith(compareBy({ it.sortOrder }, { it.id }))
    }

    override suspend fun findById(id: Long): CashDenomination? =
        database.read().cashDenominations[id]

    override suspend fun insert(value: Money, sortOrder: Int, createdAt: Instant): Long {
        val id = database.nextId(TABLE)
        val row = CashDenomination(id, value, true, sortOrder, createdAt, createdAt)
        database.write { it.copy(cashDenominations = it.cashDenominations + (id to row)) }
        return id
    }

    override suspend fun update(
        id: Long,
        value: Money,
        isActive: Boolean,
        sortOrder: Int,
        updatedAt: Instant,
    ) {
        val row = checkNotNull(database.read().cashDenominations[id]) { "No existe" }
        val updated = row.copy(
            value = value,
            isActive = isActive,
            sortOrder = sortOrder,
            updatedAt = updatedAt,
        )
        database.write { it.copy(cashDenominations = it.cashDenominations + (id to updated)) }
    }

    private companion object {
        const val TABLE = "cash_denominations"
    }
}

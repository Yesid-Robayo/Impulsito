package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.customer.LoyaltyLedgerEntryEntity
import io.github.yesidrobayo.shopledger.domain.model.customer.LoyaltyEntrySource
import io.github.yesidrobayo.shopledger.domain.model.customer.LoyaltyEntryType
import io.github.yesidrobayo.shopledger.domain.model.customer.LoyaltyLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.customer.NewLoyaltyLedgerEntry
import io.github.yesidrobayo.shopledger.domain.port.LoyaltyLedgerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * El libro de puntos sobre `loyalty_ledger_entries`. Agregar una fila y dejar al cliente con su
 * `balance_after` van juntos, dentro de la transacción del caso de uso: así
 * `customers.points_balance` siempre es la suma del libro. Ninguna función edita ni borra una fila,
 * y las guardas de la base rechazan cualquier intento.
 *
 * Los puntos no son dinero: nada de aquí toca el saldo del cliente ni una cuenta.
 */
internal class RoomLoyaltyLedgerRepository(database: AppDatabase) : LoyaltyLedgerRepository {
    private val entries = database.loyaltyLedgerEntryDao()
    private val customers = database.customerDao()
    private val writes = RepositoryWrites(database)

    override fun observeByCustomer(customerId: Long): Flow<List<LoyaltyLedgerEntry>> =
        entries.observeByCustomer(customerId)
            .map { list -> list.map(::toEntry) }
            .distinctUntilChanged()

    override suspend fun findById(id: Long): LoyaltyLedgerEntry? =
        entries.findById(id)?.let(::toEntry)

    override suspend fun lastBalanceAfter(customerId: Long): Long? =
        entries.lastBalanceAfter(customerId)

    override suspend fun findBySale(saleId: Long): List<LoyaltyLedgerEntry> =
        entries.findBySale(saleId).map(::toEntry)

    override suspend fun isCompensated(entryId: Long): Boolean = entries.isCompensated(entryId)

    override suspend fun append(entry: NewLoyaltyLedgerEntry): Long = writes.inTransaction {
        val draft = entry.draft
        val at = entry.occurredAt.toStoredMillis()
        val source = draft.source
        val id = entries.insert(
            LoyaltyLedgerEntryEntity(
                customerId = draft.customerId,
                type = draft.type.name,
                points = draft.points,
                balanceAfter = entry.balanceAfter,
                saleId = (source as? LoyaltyEntrySource.Sale)?.saleId,
                reversesEntryId = (source as? LoyaltyEntrySource.Reverses)?.entryId,
                note = draft.note,
                occurredAt = at,
                businessDate = entry.businessDate.toStored(),
                createdAt = at,
            ),
        )
        requireOneRow(customers.setPointsBalance(draft.customerId, entry.balanceAfter, at))
        id
    }

    private fun toEntry(row: LoyaltyLedgerEntryEntity): LoyaltyLedgerEntry {
        val type = LoyaltyEntryType.entries.firstOrNull { it.name == row.type } ?: brokenRow()
        val source = when {
            row.saleId != null -> LoyaltyEntrySource.Sale(row.saleId)
            row.reversesEntryId != null -> LoyaltyEntrySource.Reverses(row.reversesEntryId)
            else -> LoyaltyEntrySource.None
        }
        return LoyaltyLedgerEntry(
            id = row.id,
            customerId = row.customerId,
            type = type,
            points = row.points,
            balanceAfter = row.balanceAfter,
            source = source,
            note = row.note,
            occurredAt = row.occurredAt.toInstant(),
            businessDate = storedDate(row.businessDate),
        )
    }
}

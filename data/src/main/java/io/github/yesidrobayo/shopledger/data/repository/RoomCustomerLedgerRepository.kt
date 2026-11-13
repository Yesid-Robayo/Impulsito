package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.customer.CustomerLedgerEntryEntity
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerEntrySource
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerLedgerEntryType
import io.github.yesidrobayo.shopledger.domain.model.customer.NewCustomerLedgerEntry
import io.github.yesidrobayo.shopledger.domain.port.CustomerLedgerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * El libro de los clientes sobre `customer_ledger_entries`. Agregar una fila y dejar al cliente con
 * su `balance_after` van juntos, dentro de la transacción del caso de uso: así `customers.balance`
 * siempre es la suma del libro. Ninguna función edita ni borra una fila, y las guardas de la base
 * rechazan cualquier intento.
 */
internal class RoomCustomerLedgerRepository(database: AppDatabase) : CustomerLedgerRepository {
    private val entries = database.customerLedgerEntryDao()
    private val customers = database.customerDao()
    private val writes = RepositoryWrites(database)

    override fun observeByCustomer(customerId: Long): Flow<List<CustomerLedgerEntry>> =
        entries.observeByCustomer(customerId)
            .map { list -> list.map(::toEntry) }
            .distinctUntilChanged()

    override suspend fun findByCustomer(customerId: Long): List<CustomerLedgerEntry> =
        entries.findByCustomer(customerId).map(::toEntry)

    override suspend fun findById(id: Long): CustomerLedgerEntry? =
        entries.findById(id)?.let(::toEntry)

    override suspend fun lastBalanceAfter(customerId: Long): Money? =
        entries.lastBalanceAfter(customerId)?.let(::Money)

    override suspend fun findBySale(saleId: Long): List<CustomerLedgerEntry> =
        entries.findBySale(saleId).map(::toEntry)

    override suspend fun isCompensated(entryId: Long): Boolean = entries.isCompensated(entryId)

    override suspend fun append(entry: NewCustomerLedgerEntry): Long = writes.inTransaction {
        val draft = entry.draft
        val at = entry.occurredAt.toStoredMillis()
        val source = draft.source
        val id = entries.insert(
            CustomerLedgerEntryEntity(
                customerId = draft.customerId,
                type = draft.type.name,
                amount = draft.amount.minor,
                balanceAfter = entry.balanceAfter.minor,
                saleId = (source as? CustomerEntrySource.Sale)?.saleId,
                saleReturnId = (source as? CustomerEntrySource.SaleReturn)?.saleReturnId,
                reversesEntryId = (source as? CustomerEntrySource.Reverses)?.entryId,
                dueDate = draft.dueDate?.toStored(),
                note = draft.note,
                occurredAt = at,
                businessDate = entry.businessDate.toStored(),
                createdAt = at,
            ),
        )
        requireOneRow(customers.setBalance(draft.customerId, entry.balanceAfter.minor, at))
        id
    }

    private fun toEntry(row: CustomerLedgerEntryEntity): CustomerLedgerEntry {
        val type =
            CustomerLedgerEntryType.entries.firstOrNull { it.name == row.type } ?: brokenRow()
        val source = when {
            row.saleId != null -> CustomerEntrySource.Sale(row.saleId)
            row.saleReturnId != null -> CustomerEntrySource.SaleReturn(row.saleReturnId)
            row.reversesEntryId != null -> CustomerEntrySource.Reverses(row.reversesEntryId)
            else -> CustomerEntrySource.None
        }
        return CustomerLedgerEntry(
            id = row.id,
            customerId = row.customerId,
            type = type,
            amount = Money(row.amount),
            balanceAfter = Money(row.balanceAfter),
            source = source,
            dueDate = row.dueDate?.let(::storedDate),
            note = row.note,
            occurredAt = row.occurredAt.toInstant(),
            businessDate = storedDate(row.businessDate),
        )
    }
}

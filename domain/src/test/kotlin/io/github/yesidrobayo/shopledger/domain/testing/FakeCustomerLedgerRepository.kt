package io.github.yesidrobayo.shopledger.domain.testing

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerEntrySource
import io.github.yesidrobayo.shopledger.domain.model.customer.CustomerLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.customer.NewCustomerLedgerEntry
import io.github.yesidrobayo.shopledger.domain.port.CustomerLedgerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * El libro de los clientes sobre [InMemoryDatabase], con las reglas de la base: solo crece, una
 * fila se compensa una sola vez, y agregar deja al cliente con el saldo de la fila en la misma
 * escritura.
 */
internal class FakeCustomerLedgerRepository(private val database: InMemoryDatabase) :
    CustomerLedgerRepository {
    override fun observeByCustomer(customerId: Long): Flow<List<CustomerLedgerEntry>> =
        database.tables.map { tables -> of(tables.customerLedger.values, customerId) }

    override suspend fun findByCustomer(customerId: Long): List<CustomerLedgerEntry> =
        of(database.read().customerLedger.values, customerId)

    override suspend fun findById(id: Long): CustomerLedgerEntry? =
        database.read().customerLedger[id]

    override suspend fun lastBalanceAfter(customerId: Long): Money? =
        findByCustomer(customerId).lastOrNull()?.balanceAfter

    override suspend fun findBySale(saleId: Long): List<CustomerLedgerEntry> =
        database.read().customerLedger.values
            .filter { it.source == CustomerEntrySource.Sale(saleId) }
            .sortedBy { it.id }

    override suspend fun findBySaleReturn(saleReturnId: Long): List<CustomerLedgerEntry> =
        database.read().customerLedger.values
            .filter { it.source == CustomerEntrySource.SaleReturn(saleReturnId) }
            .sortedBy { it.id }

    override suspend fun isCompensated(entryId: Long): Boolean =
        database.read().customerLedger.values.any {
            it.source == CustomerEntrySource.Reverses(entryId)
        }

    override suspend fun append(entry: NewCustomerLedgerEntry): Long {
        val draft = entry.draft
        val reversed = (draft.source as? CustomerEntrySource.Reverses)?.entryId
        // Como las guardas: el compensatorio es único y opuesto a su fila, del mismo cliente.
        if (reversed != null) {
            val original = checkNotNull(findById(reversed)) { "No existe la fila compensada" }
            check(original.customerId == draft.customerId && original.amount == -draft.amount)
            check(!isCompensated(reversed)) { "La fila ya se compensó" }
        }
        val id = database.nextId(TABLE)
        val stored = CustomerLedgerEntry(
            id = id,
            customerId = draft.customerId,
            type = draft.type,
            amount = draft.amount,
            balanceAfter = entry.balanceAfter,
            source = draft.source,
            dueDate = draft.dueDate,
            note = draft.note,
            occurredAt = entry.occurredAt,
            businessDate = entry.businessDate,
        )
        database.write { tables ->
            val customer =
                checkNotNull(tables.customers[draft.customerId]) { "No existe el cliente" }
            tables.copy(
                customerLedger = tables.customerLedger + (id to stored),
                customers = tables.customers + (
                    customer.id to customer.copy(
                        balance = entry.balanceAfter,
                        updatedAt = entry.occurredAt,
                    )
                    ),
            )
        }
        return id
    }

    private fun of(entries: Collection<CustomerLedgerEntry>, customerId: Long) =
        entries.filter { it.customerId == customerId }.sortedBy { it.id }

    private companion object {
        const val TABLE = "customer_ledger_entries"
    }
}

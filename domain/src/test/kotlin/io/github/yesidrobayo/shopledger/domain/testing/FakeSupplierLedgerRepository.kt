package io.github.yesidrobayo.shopledger.domain.testing

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.payable.NewSupplierLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.payable.SupplierLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.payable.SupplierLedgerEntryType
import io.github.yesidrobayo.shopledger.domain.port.SupplierLedgerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Un [SupplierLedgerRepository] sobre [InMemoryDatabase]. Como la base y su guarda: solo agrega,
 * exige que el saldo nuevo sea el de la última fila más el monto, el signo de cada tipo y que una
 * compensación anule una sola vez una fila del mismo proveedor, con el monto opuesto. Deja al
 * proveedor con el saldo de la fila, en la misma transacción.
 */
internal class FakeSupplierLedgerRepository(private val database: InMemoryDatabase) :
    SupplierLedgerRepository {
    override fun observeBySupplier(supplierId: Long): Flow<List<SupplierLedgerEntry>> =
        database.tables.map { tables -> entriesOf(tables, supplierId) }

    override suspend fun findById(id: Long): SupplierLedgerEntry? =
        database.read().supplierEntries[id]

    override suspend fun lastBalanceAfter(supplierId: Long): Money? =
        entriesOf(database.read(), supplierId).lastOrNull()?.content?.balanceAfter

    override suspend fun findByPurchase(purchaseId: Long): List<SupplierLedgerEntry> =
        database.read().supplierEntries.values
            .filter { it.content.purchaseId == purchaseId }
            .sortedBy { it.id }

    override suspend fun findByPurchaseReturn(purchaseReturnId: Long): List<SupplierLedgerEntry> =
        database.read().supplierEntries.values
            .filter { it.content.purchaseReturnId == purchaseReturnId }
            .sortedBy { it.id }

    override suspend fun isCompensated(entryId: Long): Boolean =
        database.read().supplierEntries.values.any { it.content.reversesEntryId == entryId }

    override suspend fun append(entry: NewSupplierLedgerEntry): Long {
        val tables = database.read()
        val supplier = checkNotNull(tables.suppliers[entry.supplierId]) { "Llave rota" }
        val previous = lastBalanceAfter(entry.supplierId) ?: Money.ZERO
        check(previous + entry.amount == entry.balanceAfter) { "El saldo no encadena" }
        check(entry.amount != Money.ZERO) { "Monto en cero" }
        check(
            when (entry.type) {
                SupplierLedgerEntryType.CREDIT_PURCHASE -> entry.amount > Money.ZERO

                SupplierLedgerEntryType.PAYMENT, SupplierLedgerEntryType.RETURN_CREDIT ->
                    entry.amount < Money.ZERO

                SupplierLedgerEntryType.OPENING_BALANCE,
                SupplierLedgerEntryType.ADJUSTMENT,
                SupplierLedgerEntryType.ENTRY_VOID,
                -> true
            },
        ) { "Signo distinto del de su tipo" }
        entry.reversesEntryId?.let { reverses ->
            val original = checkNotNull(tables.supplierEntries[reverses]) { "Llave rota" }
            check(original.content.supplierId == entry.supplierId)
            check(original.content.amount == -entry.amount)
            check(!isCompensated(reverses)) { "Ya compensada" }
        }
        val id = database.nextId(TABLE)
        database.write {
            it.copy(supplierEntries = it.supplierEntries + (id to SupplierLedgerEntry(id, entry)))
        }
        database.write {
            val updated = supplier.copy(balance = entry.balanceAfter, updatedAt = entry.createdAt)
            it.copy(suppliers = it.suppliers + (supplier.id to updated))
        }
        return id
    }

    private fun entriesOf(tables: Tables, supplierId: Long): List<SupplierLedgerEntry> =
        tables.supplierEntries.values.filter {
            it.content.supplierId == supplierId
        }.sortedBy { it.id }

    private companion object {
        const val TABLE = "supplier_ledger_entries"
    }
}

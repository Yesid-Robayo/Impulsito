package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.domain.port.PayablesSource
import io.github.yesidrobayo.shopledger.domain.port.SupplierAccount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Las cuentas por pagar sobre `suppliers` y `supplier_ledger_entries`: cada proveedor con saldo
 * distinto de cero, con su libro. Solo lee.
 *
 * Las dos lecturas son flujos de Room que emiten tras cada transacción confirmada; como una
 * transacción cambia el saldo y el libro a la vez, una emisión intermedia puede juntar un saldo
 * nuevo con un libro viejo por un instante. La lista lo resiste: lo abierto sale del libro, no del
 * saldo, y un proveedor sin filas en el libro leído todavía no aparece.
 */
internal class RoomPayablesSource(database: AppDatabase) : PayablesSource {
    private val suppliers = database.supplierDao()
    private val entries = database.supplierLedgerEntryDao()

    override fun observe(): Flow<List<SupplierAccount>> = combine(
        suppliers.observeWithBalance(),
        entries.observeOfOpenSuppliers(),
    ) { supplierRows, entryRows ->
        val bySupplier = entryRows.groupBy({ it.supplierId }, ::toSupplierLedgerEntry)
        supplierRows.mapNotNull { row ->
            bySupplier[row.id]?.let { SupplierAccount(toSupplier(row), it) }
        }
    }.distinctUntilChanged()
}

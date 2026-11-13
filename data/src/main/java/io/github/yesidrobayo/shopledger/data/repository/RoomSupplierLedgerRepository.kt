package io.github.yesidrobayo.shopledger.data.repository

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.catalog.SupplierDao
import io.github.yesidrobayo.shopledger.data.local.payable.SupplierLedgerEntryDao
import io.github.yesidrobayo.shopledger.data.local.payable.SupplierLedgerEntryEntity
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.payable.NewSupplierLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.payable.SupplierLedgerEntry
import io.github.yesidrobayo.shopledger.domain.model.payable.SupplierLedgerEntryType
import io.github.yesidrobayo.shopledger.domain.port.SupplierLedgerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * El libro de los proveedores sobre `supplier_ledger_entries`, con su copia derivada: el saldo de
 * `suppliers`.
 *
 * Las escrituras de [append] van en la transacción del caso de uso: si cualquiera falla, la
 * transacción se deshace entera, y ningún saldo queda sin su fila ni una fila sin su saldo. Así lo
 * que se le debe a cada proveedor es siempre la suma de su libro.
 *
 * Recibe los DAO por separado para que las pruebas puedan hacer fallar cada paso.
 */
internal class RoomSupplierLedgerRepository(
    private val entries: SupplierLedgerEntryDao,
    private val suppliers: SupplierDao,
    private val writes: RepositoryWrites,
) : SupplierLedgerRepository {
    constructor(database: AppDatabase) : this(
        database.supplierLedgerEntryDao(),
        database.supplierDao(),
        RepositoryWrites(database),
    )

    override fun observeBySupplier(supplierId: Long): Flow<List<SupplierLedgerEntry>> = entries
        .observeBySupplier(supplierId)
        .map { rows -> rows.map(::toEntry) }
        .distinctUntilChanged()

    override suspend fun findById(id: Long): SupplierLedgerEntry? =
        entries.findById(id)?.let(::toEntry)

    override suspend fun lastBalanceAfter(supplierId: Long): Money? =
        entries.lastBalanceAfter(supplierId)?.let(::Money)

    override suspend fun findByPurchase(purchaseId: Long): List<SupplierLedgerEntry> =
        entries.findByPurchase(purchaseId).map(::toEntry)

    override suspend fun isCompensated(entryId: Long): Boolean = entries.isCompensated(entryId)

    /**
     * Agrega la fila y mueve el saldo del proveedor, en este orden:
     *
     * 1. Comprueba que el saldo que la fila dice dejar sea el de la última fila del proveedor más
     *    su monto. Si no, el caso de uso calculó con un saldo viejo o sumó mal, y la fila dejaría
     *    el libro descuadrado para siempre, porque el libro no se edita.
     * 2. Inserta la fila. Su guarda revisa tipo, signo, origen, moneda y compensación.
     * 3. Deja al proveedor con el saldo de la fila.
     *
     * Un saldo que no cuadra o una suma que no cabe en `Long` salen como regla rota, y la
     * transacción se deshace.
     */
    override suspend fun append(entry: NewSupplierLedgerEntry): Long = writes.inTransaction {
        val before = entries.lastBalanceAfter(entry.supplierId) ?: 0L
        val expectedAfter =
            try {
                Math.addExact(before, entry.amount.minor)
            } catch (_: ArithmeticException) {
                brokenRow()
            }
        if (expectedAfter != entry.balanceAfter.minor) brokenRow()

        val id = entries.insert(toEntity(entry))
        requireOneRow(
            suppliers.setBalance(
                entry.supplierId,
                balance = entry.balanceAfter.minor,
                updatedAt = entry.createdAt.toStoredMillis(),
            ),
        )
        id
    }

    private fun toEntity(entry: NewSupplierLedgerEntry) = SupplierLedgerEntryEntity(
        supplierId = entry.supplierId,
        type = entry.type.name,
        amount = entry.amount.minor,
        balanceAfter = entry.balanceAfter.minor,
        purchaseId = entry.purchaseId,
        purchaseReturnId = entry.purchaseReturnId,
        reversesEntryId = entry.reversesEntryId,
        dueDate = entry.dueDate?.toStored(),
        note = entry.note,
        occurredAt = entry.occurredAt.toStoredMillis(),
        businessDate = entry.businessDate.toStored(),
        createdAt = entry.createdAt.toStoredMillis(),
    )

    private fun toEntry(row: SupplierLedgerEntryEntity) = SupplierLedgerEntry(
        id = row.id,
        content = NewSupplierLedgerEntry(
            supplierId = row.supplierId,
            type = storedEnum<SupplierLedgerEntryType>(row.type),
            amount = Money(row.amount),
            balanceAfter = Money(row.balanceAfter),
            purchaseId = row.purchaseId,
            purchaseReturnId = row.purchaseReturnId,
            reversesEntryId = row.reversesEntryId,
            dueDate = row.dueDate?.let(::storedDate),
            note = row.note,
            occurredAt = row.occurredAt.toInstant(),
            businessDate = storedDate(row.businessDate),
            createdAt = row.createdAt.toInstant(),
        ),
    )
}

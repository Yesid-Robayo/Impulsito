package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.catalog.Supplier
import io.github.yesidrobayo.shopledger.domain.model.payable.SupplierLedgerEntry
import kotlinx.coroutines.flow.Flow

/**
 * Las cuentas por pagar: cada proveedor con saldo distinto de cero, también archivado, con las
 * filas de su libro en el orden del libro. Un proveedor en cero no tiene nada abierto, así que no
 * sale. Solo lee, y vuelve a emitir con cada transacción confirmada que cambia lo leído.
 */
fun interface PayablesSource {
    fun observe(): Flow<List<SupplierAccount>>
}

/** Un proveedor con las filas de su libro, en el orden del libro. */
data class SupplierAccount(val supplier: Supplier, val entries: List<SupplierLedgerEntry>)

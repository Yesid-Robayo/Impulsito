package io.github.yesidrobayo.shopledger.domain.usecase.payable

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.Supplier
import io.github.yesidrobayo.shopledger.domain.model.payable.SupplierLedgerEntry
import io.github.yesidrobayo.shopledger.domain.port.SupplierLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.SupplierRepository
import io.github.yesidrobayo.shopledger.domain.rule.payable.SupplierDebt
import io.github.yesidrobayo.shopledger.domain.rule.payable.SupplierDebts
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * La cuenta de un proveedor tal como la guarda su libro: el proveedor, con su saldo, y sus filas en
 * el orden del libro. [debt] es lo abierto, con sus vencimientos, o `null` si las sumas no caben en
 * un monto, lo que solo sale de una copia dañada.
 */
data class SupplierStatement(
    val supplier: Supplier,
    val entries: List<SupplierLedgerEntry>,
    val debt: SupplierDebt?,
)

/**
 * El extracto de un proveedor, de nuevo con cada fila que entra a su libro y con cada cambio de sus
 * datos. Emite `null` si el proveedor no existe. Solo lee: el saldo que muestra es el que guardó el
 * libro, nunca uno recalculado.
 */
class ObserveSupplierStatementUseCase(
    private val suppliers: SupplierRepository,
    private val ledger: SupplierLedgerRepository,
) {
    operator fun invoke(supplierId: Long): Flow<SupplierStatement?> = combine(
        suppliers.observeById(supplierId),
        ledger.observeBySupplier(supplierId),
    ) { supplier, entries ->
        supplier?.let { SupplierStatement(it, entries, SupplierDebts.of(entries)) }
    }.distinctUntilChanged()
}

/**
 * Lo que falta pagar de una compra a plazo: lo abierto de su cargo en el libro de su proveedor,
 * cero si ya se pagó, o `null` si el libro tiene cifras que no caben. Cambia con cada pago, porque
 * lo que resta cubre primero los cargos más antiguos.
 */
class ObservePurchaseBalanceUseCase(private val ledger: SupplierLedgerRepository) {
    operator fun invoke(supplierId: Long, purchaseId: Long): Flow<Money?> =
        ledger.observeBySupplier(supplierId).map { entries ->
            SupplierDebts.of(entries)?.openCharges
                ?.filter { it.purchaseId == purchaseId }
                ?.fold(Money.ZERO) { sum, charge -> sum + charge.remaining }
        }.distinctUntilChanged()
}

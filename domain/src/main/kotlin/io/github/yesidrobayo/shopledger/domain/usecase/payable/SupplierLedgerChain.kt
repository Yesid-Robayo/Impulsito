package io.github.yesidrobayo.shopledger.domain.usecase.payable

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.payable.NewSupplierLedgerEntry
import io.github.yesidrobayo.shopledger.domain.port.SupplierLedgerRepository
import io.github.yesidrobayo.shopledger.domain.rule.payable.SupplierLedgerRules

/**
 * Agrega al libro de [supplierId] la fila que arma [build] con el saldo que deja, que es el de la
 * última fila del proveedor más [amount], leído dentro de la misma transacción. Devuelve su id, o
 * `null` si ese saldo no cabe: entonces no escribe nada, y el caso de uso deshace la transacción.
 *
 * Así cada caso de uso que mueve el libro encadena el saldo igual, y el saldo del proveedor sigue
 * siendo la suma de sus filas.
 */
internal suspend fun SupplierLedgerRepository.appendChained(
    supplierId: Long,
    amount: Money,
    build: (balanceAfter: Money) -> NewSupplierLedgerEntry,
): Long? {
    val after = SupplierLedgerRules.balanceAfter(lastBalanceAfter(supplierId), amount)
        ?: return null
    val entry = build(after)
    check(entry.supplierId == supplierId && entry.amount == amount) {
        "La fila debe ser del proveedor y del monto con que se encadenó"
    }
    check(SupplierLedgerRules.signFits(entry.type, entry.amount)) {
        "El monto no tiene el signo de su tipo"
    }
    return append(entry)
}

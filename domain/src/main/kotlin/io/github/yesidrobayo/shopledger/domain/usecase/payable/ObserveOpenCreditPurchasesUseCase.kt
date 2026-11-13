package io.github.yesidrobayo.shopledger.domain.usecase.payable

import io.github.yesidrobayo.shopledger.domain.port.PayablesSource
import io.github.yesidrobayo.shopledger.domain.rule.payable.SupplierDebts
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Las compras a plazo que todavía tienen algo por pagar, por su id. Una compra a plazo que no está
 * aquí ya se pagó, o se anuló.
 *
 * Lo que resta cubre los cargos más antiguos primero, como en el resto de cuentas por pagar: una
 * compra puede quedar pagada antes que otra más nueva del mismo proveedor aunque el pago no la
 * nombre. Un libro con cifras que no caben no aporta compras: solo sale de una copia dañada, y la
 * lista de cuentas por pagar ya lo dice.
 */
class ObserveOpenCreditPurchasesUseCase(private val source: PayablesSource) {
    operator fun invoke(): Flow<Set<Long>> = source.observe().map { accounts ->
        accounts.flatMapTo(HashSet()) { account ->
            SupplierDebts.of(account.entries)?.openCharges.orEmpty().mapNotNull { it.purchaseId }
        }
    }.distinctUntilChanged()
}

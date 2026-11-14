package io.github.yesidrobayo.shopledger.domain.usecase.sale

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.port.CustomerLedgerRepository
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.rule.customer.CustomerLedgerRules

/**
 * El saldo a favor que el cliente tenía justo antes de una venta, para que su comprobante separe
 * lo que el pago a crédito cubrió con ese saldo, como un anticipo, de lo que quedó debiendo.
 *
 * Lo lee del cargo a crédito de la venta en el libro del cliente, que guarda el saldo que dejó: no
 * escribe nada y no depende del saldo de hoy. Cero si la venta no cargó nada a crédito o si el
 * cliente debía.
 */
class ReadCreditBeforeSaleUseCase(
    private val transactions: TransactionRunner,
    private val ledger: CustomerLedgerRepository,
) {
    suspend operator fun invoke(saleId: Long): Money =
        CustomerLedgerRules.creditBefore(transactions.read { ledger.findBySale(saleId) })
}

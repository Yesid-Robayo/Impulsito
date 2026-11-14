package io.github.yesidrobayo.shopledger.domain.usecase.document

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.port.OrderRepository
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.usecase.customer.CustomerPaymentRequest
import io.github.yesidrobayo.shopledger.domain.usecase.customer.RecordCustomerPaymentResult
import io.github.yesidrobayo.shopledger.domain.usecase.customer.RecordCustomerPaymentUseCase

/** Resultado de [RecordOrderDepositUseCase]. */
sealed interface OrderDepositResult {
    /** El anticipo quedó como abono del cliente, con su resultado. */
    data class Recorded(val payment: RecordCustomerPaymentResult.Recorded) : OrderDepositResult

    /** El pedido no tiene cliente: un anticipo es un pago del cliente, y exige uno. */
    data object NeedsCustomer : OrderDepositResult

    /** El pedido ya no existe, o ya se entregó o canceló. */
    data object OrderNotOpen : OrderDepositResult

    /** El abono no se guardó, por [result]: la cantidad, la cuenta o la base. */
    data class Rejected(val result: RecordCustomerPaymentResult) : OrderDepositResult
}

/**
 * Registra el anticipo de un pedido: un `PAYMENT` en el libro del cliente, que le deja saldo a
 * favor, y su entrada en la cuenta de dinero si se eligió una, en una sola transacción, con el mismo
 * abono de siempre.
 *
 * El anticipo no queda atado al pedido: es saldo a favor del cliente, y la venta de la entrega lo
 * consume a crédito. Si el pedido se cancela, sigue a favor, y se puede devolver con un ajuste y su
 * salida de dinero.
 */
class RecordOrderDepositUseCase(
    private val transactions: TransactionRunner,
    private val orders: OrderRepository,
    private val payments: RecordCustomerPaymentUseCase,
) {
    suspend operator fun invoke(
        orderId: Long,
        amount: Money,
        moneyAccountId: Long?,
    ): OrderDepositResult {
        val order = transactions.read { orders.findById(orderId) }
        if (order == null || !order.status.isOpen) return OrderDepositResult.OrderNotOpen
        val customerId = order.customerId ?: return OrderDepositResult.NeedsCustomer
        val request =
            CustomerPaymentRequest(customerId, amount, moneyAccountId, date = null, note = null)
        val result = payments(request)
        return (result as? RecordCustomerPaymentResult.Recorded)
            ?.let(OrderDepositResult::Recorded)
            ?: OrderDepositResult.Rejected(result)
    }
}

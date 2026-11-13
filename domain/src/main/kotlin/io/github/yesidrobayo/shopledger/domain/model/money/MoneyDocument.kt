package io.github.yesidrobayo.shopledger.domain.model.money

/**
 * El documento al que lleva un movimiento de dinero que no se registró a mano: lo que la persona
 * abre para verlo o anularlo, porque desde la cuenta no se anula.
 *
 * Un abono lleva al cliente y un pago a proveedor al proveedor, porque es ahí donde se ven y se
 * anulan. Un gasto lleva a la lista de gastos, que no tiene una pantalla por gasto.
 */
sealed interface MoneyDocument {
    /** Una venta: su cobro, su comisión o su devolución. */
    data class Sale(val saleId: Long) : MoneyDocument

    /** El cliente de un abono. */
    data class Customer(val customerId: Long) : MoneyDocument

    /** El proveedor de un pago posterior, o de una devolución sin compra. */
    data class Supplier(val supplierId: Long) : MoneyDocument

    /** Una compra: su pago al confirmarla, o lo que el proveedor devolvió por ella. */
    data class Purchase(val purchaseId: Long) : MoneyDocument

    /** Un gasto. */
    data class Expense(val expenseId: Long) : MoneyDocument

    /** La sesión de caja de una diferencia al cerrar. */
    data class CashSession(val sessionId: Long) : MoneyDocument
}

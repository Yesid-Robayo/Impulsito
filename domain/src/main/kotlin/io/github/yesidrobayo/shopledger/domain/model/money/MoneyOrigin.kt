package io.github.yesidrobayo.shopledger.domain.model.money

/**
 * El documento del que sale un movimiento de dinero, con su id. Cada tipo exige uno solo, de la clase
 * de [MoneyMovementType.originKind].
 */
sealed interface MoneyOrigin {
    val kind: MoneyOriginKind

    /** Lo registró la persona a mano: un saldo inicial, un retiro, un aporte, otro ingreso o una
     * diferencia de caja, que lleva su sesión aparte. */
    data object None : MoneyOrigin {
        override val kind: MoneyOriginKind get() = MoneyOriginKind.NONE
    }

    /** El pago de una venta que cobró, o cuya comisión se pagó. */
    data class SalePayment(val salePaymentId: Long) : MoneyOrigin {
        override val kind: MoneyOriginKind get() = MoneyOriginKind.SALE_PAYMENT
    }

    /** El abono del cliente en su libro. */
    data class CustomerPayment(val customerLedgerEntryId: Long) : MoneyOrigin {
        override val kind: MoneyOriginKind get() = MoneyOriginKind.CUSTOMER_PAYMENT
    }

    /** El pago al proveedor en su libro. */
    data class SupplierPayment(val supplierLedgerEntryId: Long) : MoneyOrigin {
        override val kind: MoneyOriginKind get() = MoneyOriginKind.SUPPLIER_PAYMENT
    }

    data class Expense(val expenseId: Long) : MoneyOrigin {
        override val kind: MoneyOriginKind get() = MoneyOriginKind.EXPENSE
    }

    data class Purchase(val purchaseId: Long) : MoneyOrigin {
        override val kind: MoneyOriginKind get() = MoneyOriginKind.PURCHASE
    }

    data class SaleReturn(val saleReturnId: Long) : MoneyOrigin {
        override val kind: MoneyOriginKind get() = MoneyOriginKind.SALE_RETURN
    }

    data class PurchaseReturn(val purchaseReturnId: Long) : MoneyOrigin {
        override val kind: MoneyOriginKind get() = MoneyOriginKind.PURCHASE_RETURN
    }

    /**
     * Una de las dos partes de una transferencia. [groupId] une la que sale con la que entra, y
     * [fxRateMicro] es la cotización si las cuentas tienen distinta moneda.
     */
    data class Transfer(val groupId: String, val fxRateMicro: Long?) : MoneyOrigin {
        override val kind: MoneyOriginKind get() = MoneyOriginKind.TRANSFER
    }

    /** El movimiento que esta anulación compensa. */
    data class Void(val reversesMovementId: Long) : MoneyOrigin {
        override val kind: MoneyOriginKind get() = MoneyOriginKind.VOID
    }
}

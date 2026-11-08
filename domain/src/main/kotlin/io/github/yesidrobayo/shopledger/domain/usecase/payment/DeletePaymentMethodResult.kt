package io.github.yesidrobayo.shopledger.domain.usecase.payment

import io.github.yesidrobayo.shopledger.domain.port.StorageFailure

/** Cómo terminó eliminar un método de pago. */
sealed interface DeletePaymentMethodResult {
    data object Deleted : DeletePaymentMethodResult

    data object NotFound : DeletePaymentMethodResult

    /** Alguna venta, aunque esté anulada, se cobró con él: solo se archiva. */
    data object HasSalePayments : DeletePaymentMethodResult

    /** Es el único vigente: eliminarlo dejaría la venta sin con qué cobrar. */
    data object LastActive : DeletePaymentMethodResult

    data class StorageFailed(val failure: StorageFailure) : DeletePaymentMethodResult
}

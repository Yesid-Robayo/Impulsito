package io.github.yesidrobayo.shopledger.domain.usecase.payment

import io.github.yesidrobayo.shopledger.domain.port.StorageFailure

/** Cómo terminó reactivar un método de pago archivado. */
sealed interface ReactivatePaymentMethodResult {
    /** Vuelve a ofrecerse al cobrar, en el lugar que tenía en la lista. */
    data object Reactivated : ReactivatePaymentMethodResult

    data object NotFound : ReactivatePaymentMethodResult

    /** No estaba archivado. Nada cambió. */
    data object NotArchived : ReactivatePaymentMethodResult

    /**
     * Mientras estuvo archivado, otro vigente tomó su nombre. Se renombra uno de los dos y se
     * vuelve a intentar.
     */
    data object NameTaken : ReactivatePaymentMethodResult

    data class StorageFailed(val failure: StorageFailure) : ReactivatePaymentMethodResult
}

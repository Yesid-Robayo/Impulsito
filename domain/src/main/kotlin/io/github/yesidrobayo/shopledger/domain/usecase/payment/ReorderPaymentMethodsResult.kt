package io.github.yesidrobayo.shopledger.domain.usecase.payment

import io.github.yesidrobayo.shopledger.domain.port.StorageFailure

/** Cómo terminó guardar el orden de los métodos de pago. */
sealed interface ReorderPaymentMethodsResult {
    /** Quedó el orden pedido. */
    data object Reordered : ReorderPaymentMethodsResult

    /** Ya estaban en ese orden. Nada se escribió. */
    data object Unchanged : ReorderPaymentMethodsResult

    /**
     * La lista pedida no son exactamente los vigentes de hoy: otro cambio entró mientras se
     * ordenaba. Nada se escribió; la pantalla vuelve a leer la lista.
     */
    data object ListChanged : ReorderPaymentMethodsResult

    data class StorageFailed(val failure: StorageFailure) : ReorderPaymentMethodsResult
}

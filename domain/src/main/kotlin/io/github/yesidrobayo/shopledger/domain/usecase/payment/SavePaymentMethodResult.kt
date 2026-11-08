package io.github.yesidrobayo.shopledger.domain.usecase.payment

import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodInputError
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure

/** Cómo terminó crear o editar un método de pago. */
sealed interface SavePaymentMethodResult {
    /** Quedó guardado, o ya estaba exactamente así. [id] es el del método. */
    data class Saved(val id: Long) : SavePaymentMethodResult

    /** No se guardó nada: cada error va en su campo, y lo escrito se conserva. */
    data class Invalid(val errors: Set<PaymentMethodInputError>) : SavePaymentMethodResult

    /** El método ya no existe, por ejemplo porque se eliminó mientras se editaba. */
    data object NotFound : SavePaymentMethodResult

    /** La base no pudo escribir. No quedó nada a medias, y lo escrito se conserva. */
    data class StorageFailed(val failure: StorageFailure) : SavePaymentMethodResult
}

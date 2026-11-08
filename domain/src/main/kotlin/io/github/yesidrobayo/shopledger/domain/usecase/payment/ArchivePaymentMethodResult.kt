package io.github.yesidrobayo.shopledger.domain.usecase.payment

import io.github.yesidrobayo.shopledger.domain.port.StorageFailure

/** Cómo terminó archivar un método de pago. */
sealed interface ArchivePaymentMethodResult {
    /** Quedó archivado: ya no se ofrece al cobrar, y sus ventas no cambian. */
    data object Archived : ArchivePaymentMethodResult

    data object NotFound : ArchivePaymentMethodResult

    /** Ya estaba archivado. Nada cambió. */
    data object AlreadyArchived : ArchivePaymentMethodResult

    /** Es el único vigente: archivarlo dejaría la venta sin con qué cobrar. */
    data object LastActive : ArchivePaymentMethodResult

    data class StorageFailed(val failure: StorageFailure) : ArchivePaymentMethodResult
}

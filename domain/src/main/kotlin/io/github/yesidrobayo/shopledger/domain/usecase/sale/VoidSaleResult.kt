package io.github.yesidrobayo.shopledger.domain.usecase.sale

import io.github.yesidrobayo.shopledger.domain.port.StorageFailure

/**
 * Resultado de [VoidSaleUseCase]. Cualquier rechazo deshace la transacción entera: la venta sigue
 * activa y el libro no recibe ningún compensatorio.
 */
sealed interface VoidSaleResult {
    /** La venta quedó anulada y su stock volvió. */
    data object Voided : VoidSaleResult

    /** La venta no existe. */
    data object SaleNotFound : VoidSaleResult

    /** La venta ya estaba anulada: una venta se anula una sola vez, y no se reactiva. */
    data object AlreadyVoided : VoidSaleResult

    /** La venta tiene una devolución activa: lo que falta se devuelve, no se anula. */
    data object HasActiveReturns : VoidSaleResult

    /** El motivo no vale para una venta, como una devolución al proveedor. */
    data object ReasonNotAllowed : VoidSaleResult

    /** La nota pasa de su largo máximo. */
    data object NoteTooLong : VoidSaleResult

    /**
     * Este teléfono no conoce la zona del negocio [storedZoneId], así que no hay cómo fechar los
     * compensatorios. La persona elige una zona que el teléfono conozca.
     */
    data class UnknownTimeZone(val storedZoneId: String) : VoidSaleResult

    /**
     * El libro de un producto de la venta no se puede reproducir: tiene una fila que contradice su
     * tipo o cifras que no caben. La app no escribe un libro así; puede venir de una copia dañada.
     */
    data object LedgerDamaged : VoidSaleResult

    /** La base no pudo escribir, por [failure]. No quedó nada guardado. */
    data class StorageFailed(val failure: StorageFailure) : VoidSaleResult
}

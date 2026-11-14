package io.github.yesidrobayo.shopledger.domain.usecase.purchase

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure

/**
 * Resultado de [VoidPurchaseUseCase]. Cualquier rechazo deshace la transacción entera: la compra
 * sigue activa y el libro no recibe ningún compensatorio.
 */
sealed interface VoidPurchaseResult {
    /** La compra quedó anulada y su stock salió del libro efectivo. */
    data object Voided : VoidPurchaseResult

    /** La compra no existe. */
    data object PurchaseNotFound : VoidPurchaseResult

    /** La compra ya estaba anulada: una compra se anula una sola vez, y no se reactiva. */
    data object AlreadyVoided : VoidPurchaseResult

    /**
     * La compra tiene una devolución al proveedor activa: lo devuelto ya salió del stock y del
     * costo. Se devuelve el resto, o se anula primero la devolución.
     */
    data object HasActiveReturns : VoidPurchaseResult

    /** El motivo no vale para una compra, como una devolución de cliente. */
    data object ReasonNotAllowed : VoidPurchaseResult

    /** La nota pasa de su largo máximo. */
    data object NoteTooLong : VoidPurchaseResult

    /**
     * Este teléfono no conoce la zona del negocio [storedZoneId], así que no hay cómo fechar los
     * compensatorios. La persona elige una zona que el teléfono conozca.
     */
    data class UnknownTimeZone(val storedZoneId: String) : VoidPurchaseResult

    /**
     * El libro de un producto de la compra no se puede reproducir: tiene una fila que contradice
     * su tipo o cifras que no caben. La app no escribe un libro así; puede venir de una copia
     * dañada.
     */
    data object LedgerDamaged : VoidPurchaseResult

    /**
     * Parte de lo que entró al lote [lotId] del producto [productId] ya salió, por una venta o un
     * ajuste. Anular lo sacaría del mismo lote y lo dejaría negativo, y un lote con código nunca
     * queda negativo. [missing] es lo que falta en ese lote para anular: se devuelve primero, o se
     * corrige con un ajuste.
     */
    data class LotAlreadyUsed(val productId: Long, val lotId: Long, val missing: QuantityMilli) :
        VoidPurchaseResult

    /** La base no pudo escribir, por [failure]. No quedó nada guardado. */
    data class StorageFailed(val failure: StorageFailure) : VoidPurchaseResult
}

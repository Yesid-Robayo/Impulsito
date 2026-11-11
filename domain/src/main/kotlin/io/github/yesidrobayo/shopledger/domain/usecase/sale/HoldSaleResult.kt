package io.github.yesidrobayo.shopledger.domain.usecase.sale

import io.github.yesidrobayo.shopledger.domain.port.StorageFailure

/**
 * Resultado de [HoldSaleUseCase]. Cualquier rechazo deshace la transacción entera: no queda nada en
 * espera, y la venta sigue en edición tal como estaba.
 */
sealed interface HoldSaleResult {
    /** La venta quedó en espera con el id [heldSaleId]. */
    data class Held(val heldSaleId: Long) : HoldSaleResult

    /** No hay líneas: no hay nada que guardar. */
    data object EmptySale : HoldSaleResult

    /** Pasa del máximo de líneas de un documento. */
    data object TooManyLines : HoldSaleResult

    /** El nombre pasa de 40 caracteres. */
    data object LabelTooLong : HoldSaleResult

    /**
     * La línea en la posición [index] no se puede guardar: su producto ya no existe, o una cifra
     * pasa de su tope.
     */
    data class LineRejected(val index: Int) : HoldSaleResult

    /** La base no pudo escribir, por [failure]. No quedó nada guardado. */
    data class StorageFailed(val failure: StorageFailure) : HoldSaleResult
}

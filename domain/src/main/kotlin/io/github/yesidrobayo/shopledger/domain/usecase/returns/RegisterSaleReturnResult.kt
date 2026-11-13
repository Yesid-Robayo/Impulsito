package io.github.yesidrobayo.shopledger.domain.usecase.returns

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.money.AccountIssue

/** Por qué no se puede devolver una línea pedida. */
enum class ReturnLineRejection {
    /** La línea no es de esta venta. */
    NOT_IN_SALE,

    /** La misma línea viene dos veces en la devolución: se pide una sola vez, con su cantidad. */
    DUPLICATE,

    /** La cantidad es cero o negativa. */
    INVALID_QUANTITY,

    /** La unidad base del producto no admite fracciones, y la cantidad las tiene. */
    FRACTION_NOT_ALLOWED,

    /**
     * Es una línea de un combo. Hoy no se venden combos, así que solo puede venir de una base que
     * no escribió la app.
     */
    COMPONENTS_NOT_SUPPORTED,
}

/**
 * Resultado de [RegisterSaleReturnUseCase]. Cualquier rechazo deshace la transacción entera: no
 * queda devolución, ni filas en el libro de stock, ni dinero movido.
 */
sealed interface RegisterSaleReturnResult {
    /** La devolución quedó con el id [saleReturnId], y devuelve [totalRefund]. */
    data class Registered(val saleReturnId: Long, val totalRefund: Money) :
        RegisterSaleReturnResult

    /** La venta no existe. */
    data object SaleNotFound : RegisterSaleReturnResult

    /** La venta está anulada: ya no tiene nada que devolver. */
    data object SaleVoided : RegisterSaleReturnResult

    /** No se eligió ninguna línea. */
    data object NoLines : RegisterSaleReturnResult

    /** Más líneas de las que admite un documento. */
    data object TooManyLines : RegisterSaleReturnResult

    /** La nota pasa de su largo máximo. */
    data object NoteTooLong : RegisterSaleReturnResult

    /** La línea pedida en la posición [index] no se puede devolver, por [reason]. */
    data class LineRejected(val index: Int, val reason: ReturnLineRejection) :
        RegisterSaleReturnResult

    /**
     * La línea pedida en la posición [index] pasa de lo que falta por devolver, [remaining]: lo
     * vendido menos lo devuelto en devoluciones activas.
     */
    data class AboveRemaining(val index: Int, val remaining: QuantityMilli) :
        RegisterSaleReturnResult

    /** Un saldo a favor sin cliente: la venta no tiene, y no se eligió ninguno. */
    data object CustomerRequired : RegisterSaleReturnResult

    /** El cliente no existe, está archivado o se anonimizó: ya no recibe saldo nuevo. */
    data object CustomerUnavailable : RegisterSaleReturnResult

    /** Se eligió un cliente distinto del de la venta: el saldo a favor es de quien compró. */
    data object CustomerNotOfSale : RegisterSaleReturnResult

    /** La cuenta elegida para devolver el dinero no sirve, por [issue]. */
    data class AccountUnavailable(val issue: AccountIssue) : RegisterSaleReturnResult

    /**
     * Este teléfono no conoce la zona del negocio [storedZoneId], así que no hay cómo fechar la
     * devolución. La persona elige una zona que el teléfono conozca.
     */
    data class UnknownTimeZone(val storedZoneId: String) : RegisterSaleReturnResult

    /**
     * El costo de un producto devuelto, o el saldo de la cuenta que devuelve el dinero, no cabe en
     * las cifras de su libro. La app no escribe un libro así; puede venir de una copia dañada.
     */
    data object LedgerDamaged : RegisterSaleReturnResult

    /** La base no pudo escribir, por [failure]. No quedó nada guardado. */
    data class StorageFailed(val failure: StorageFailure) : RegisterSaleReturnResult
}

package io.github.yesidrobayo.shopledger.domain.usecase.purchase

import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseCostChange
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLineRejection
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.money.AccountIssue

/**
 * Resultado de [RegisterPurchaseUseCase]. Salvo [Registered], no se escribió nada: la compra que la
 * persona escribió sigue en pantalla, intacta.
 */
sealed interface RegisterPurchaseResult {
    /**
     * La compra quedó guardada como [purchaseId]. [costChanges] dice cómo quedó el costo promedio de
     * cada producto comprado, una vez por producto y en el orden de sus primeras líneas, para el
     * resumen que sigue a confirmar.
     */
    data class Registered(val purchaseId: Long, val costChanges: List<PurchaseCostChange>) :
        RegisterPurchaseResult

    /** La compra no tiene líneas. */
    data object EmptyPurchase : RegisterPurchaseResult

    /** La compra pasa del máximo de líneas de un documento. */
    data object TooManyLines : RegisterPurchaseResult

    /** La referencia del documento del proveedor pasa de su largo máximo. */
    data object InvoiceReferenceTooLong : RegisterPurchaseResult

    /** El proveedor elegido ya no existe. */
    data object SupplierNotFound : RegisterPurchaseResult

    /** El proveedor está archivado: no se elige en compras nuevas hasta reactivarlo. */
    data object SupplierArchived : RegisterPurchaseResult

    /** La línea número [index], desde cero, no se puede registrar, por [reason]. */
    data class LineRejected(val index: Int, val reason: PurchaseLineRejection) :
        RegisterPurchaseResult

    /** La suma de las líneas pasa del total máximo de un documento. */
    data object PurchaseTotalTooLarge : RegisterPurchaseResult

    /** La fecha elegida es futura: una compra se registra hoy o en una fecha pasada. */
    data object FutureDate : RegisterPurchaseResult

    /**
     * Este teléfono no conoce la zona del negocio [storedZoneId], así que no hay cómo fechar la
     * compra. La persona elige una zona que el teléfono conozca.
     */
    data class UnknownTimeZone(val storedZoneId: String) : RegisterPurchaseResult

    /** Lo pagado al confirmar es negativo o pasa del total de la compra. */
    data object AmountPaidOutOfRange : RegisterPurchaseResult

    /** La compra queda debiendo algo y no tiene proveedor: una deuda necesita a quién se le debe. */
    data object SupplierRequiredForCredit : RegisterPurchaseResult

    /** El vencimiento es anterior a la fecha de la compra. */
    data object DueDateBeforePurchase : RegisterPurchaseResult

    /**
     * Lo que se le debería al proveedor no cabe en un monto. Solo sale con cifras absurdas, que
     * pueden venir de una copia dañada.
     */
    data object SupplierBalanceTooLarge : RegisterPurchaseResult

    /** La moneda del negocio sigue sin confirmar: sin ella, un monto no tiene unidad. */
    data object CurrencyNotConfirmed : RegisterPurchaseResult

    /** La cuenta elegida no puede pagar la compra, por [issue]. La persona elige otra o ninguna. */
    data class AccountUnavailable(val issue: AccountIssue) : RegisterPurchaseResult

    /** El saldo de la cuenta no cabría en un entero de 64 bits con este pago. */
    data object AccountOverflow : RegisterPurchaseResult

    /** La base no pudo escribir, por [failure]. No quedó nada guardado. */
    data class StorageFailed(val failure: StorageFailure) : RegisterPurchaseResult
}

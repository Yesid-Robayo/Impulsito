package io.github.yesidrobayo.shopledger.domain.usecase.returns

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.returns.SupplierReturnLineRejection
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.usecase.money.AccountIssue

/**
 * Resultado de [RegisterSupplierReturnUseCase]. Salvo [Registered], no se escribió nada: lo que la
 * persona eligió sigue en la pantalla, intacto.
 */
sealed interface RegisterSupplierReturnResult {
    /** La devolución quedó como [purchaseReturnId], por [total]. */
    data class Registered(val purchaseReturnId: Long, val total: Money) :
        RegisterSupplierReturnResult

    /** No hay ninguna línea. */
    data object EmptyReturn : RegisterSupplierReturnResult

    /** Hay más líneas de las que admite un documento. */
    data object TooManyLines : RegisterSupplierReturnResult

    /** La nota pasa de su largo máximo. */
    data object NoteTooLong : RegisterSupplierReturnResult

    /** La línea número [index], contando desde cero, no se puede devolver por [reason]. */
    data class LineRejected(val index: Int, val reason: SupplierReturnLineRejection) :
        RegisterSupplierReturnResult

    /**
     * La línea número [index] pide más de lo que queda de su línea de compra: a lo sumo
     * [remaining] milésimas, contando lo ya devuelto y las líneas anteriores de esta devolución.
     */
    data class AboveRemaining(val index: Int, val remaining: QuantityMilli) :
        RegisterSupplierReturnResult

    /** El total de la devolución pasa del máximo de un documento. */
    data object TotalTooLarge : RegisterSupplierReturnResult

    /** La compra elegida ya no existe. */
    data object PurchaseNotFound : RegisterSupplierReturnResult

    /** La compra elegida está anulada: no devuelve nada. */
    data object PurchaseVoided : RegisterSupplierReturnResult

    /** El proveedor elegido no es el de la compra. */
    data object SupplierMismatch : RegisterSupplierReturnResult

    /** El proveedor elegido ya no existe. */
    data object SupplierNotFound : RegisterSupplierReturnResult

    /** Dejar crédito exige saber con qué proveedor. */
    data object SupplierRequiredForCredit : RegisterSupplierReturnResult

    /** Este teléfono no conoce la zona del negocio [storedZoneId]: no hay cómo fechar la devolución. */
    data class UnknownTimeZone(val storedZoneId: String) : RegisterSupplierReturnResult

    /** La moneda del negocio sigue sin confirmar: sin ella, un monto no tiene unidad. */
    data object CurrencyNotConfirmed : RegisterSupplierReturnResult

    /** La cuenta elegida para el reembolso no puede recibirlo, por [issue]. */
    data class AccountUnavailable(val issue: AccountIssue) : RegisterSupplierReturnResult

    /** El saldo de la cuenta no cabría en un monto. Solo sale con cifras absurdas. */
    data object AccountOverflow : RegisterSupplierReturnResult

    /** El saldo con el proveedor no cabría en un monto. Solo sale con cifras absurdas. */
    data object SupplierBalanceTooLarge : RegisterSupplierReturnResult

    /** La base no pudo escribir, por [failure]. No quedó nada guardado. */
    data class StorageFailed(val failure: StorageFailure) : RegisterSupplierReturnResult
}

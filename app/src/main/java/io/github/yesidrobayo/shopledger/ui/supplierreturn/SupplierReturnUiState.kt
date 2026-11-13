package io.github.yesidrobayo.shopledger.ui.supplierreturn

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccount
import io.github.yesidrobayo.shopledger.domain.model.returns.PurchaseReturnResolution

/**
 * Lo que muestra la devolución a proveedor. Todo sale del ViewModel: la pantalla solo dibuja.
 *
 * @param loading lo que hace falta todavía no llega. Con una compra, la pantalla dibuja un
 *   esqueleto; en blanco no hay nada que esperar a la vista.
 * @param withPurchase la devolución llegó o eligió una compra de origen: las líneas salen de ella.
 * @param supplierName el proveedor elegido, o `null` sin proveedor.
 * @param supplierLocked el proveedor es el de la compra de origen y no se cambia.
 * @param origin la compra de origen, o `null` sin compra.
 * @param lines las líneas: con compra, una por línea de compra; sin compra, las que se agregaron.
 * @param resolution cómo se resuelve: reembolso en dinero o crédito con el proveedor.
 * @param accounts las cuentas a las que puede entrar un reembolso; vacía si no se llevan cuentas.
 * @param accountId la cuenta elegida, o `null` para ninguna.
 * @param balanceAfter lo que se le deberá al proveedor con el crédito, o `null` sin proveedor.
 * @param creditNeedsSupplier se eligió crédito sin proveedor: se pide uno.
 * @param total lo que el proveedor devuelve, o `null` si todavía no se puede calcular.
 */
data class SupplierReturnUiState(
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val withPurchase: Boolean = false,
    val supplierName: String? = null,
    val supplierLocked: Boolean = false,
    val origin: ReturnOriginUi? = null,
    val lines: List<SupplierReturnLineUi> = emptyList(),
    val resolution: PurchaseReturnResolution = PurchaseReturnResolution.CASH_REFUND,
    val accounts: List<MoneyAccount> = emptyList(),
    val accountId: Long? = null,
    val balanceAfter: Money? = null,
    val creditNeedsSupplier: Boolean = false,
    val note: String = "",
    val noteTooLong: Boolean = false,
    val total: Money? = null,
    val sheet: SupplierReturnSheet? = null,
    val discardAsked: Boolean = false,
    val notice: SupplierReturnNotice? = null,
    val registering: Boolean = false,
    val registered: Money? = null,
    /** La devolución recién registrada, para abrirla desde el aviso con `Ver`. */
    val registeredId: Long? = null,
) {
    /** Si hay algo escrito que se perdería al salir. */
    val hasInput: Boolean get() = lines.any { it.quantityText.isNotBlank() } || note.isNotBlank()
}

/** La compra de origen en pantalla: su número, que es su id, y su fecha. */
data class ReturnOriginUi(val purchaseId: Long, val date: BusinessDate)

/**
 * Una línea de la devolución en pantalla.
 *
 * @param key la identidad estable de la línea en la lista.
 * @param unit la unidad base del producto, con la que se escribe la cantidad.
 * @param unitCost el costo unitario con que sale, de solo lectura, si ya se calculó.
 * @param lineTotal lo que devuelve el proveedor por la línea, si ya se calculó.
 * @param costBefore y [costAfter] el costo promedio antes y después de esta línea.
 * @param leftAfter lo que quedaría en stock, solo si quedaría negativo: avisa sin bloquear.
 * @param removable una línea sin compra se quita; una de la compra se deja en cero.
 */
data class SupplierReturnLineUi(
    val key: Long,
    val name: String,
    val unit: MeasureUnit?,
    val quantityText: String,
    val unitCost: Money? = null,
    val lineTotal: Money? = null,
    val costBefore: Money? = null,
    val costAfter: Money? = null,
    val leftAfter: QuantityMilli? = null,
    val error: SupplierReturnLineError? = null,
    val removable: Boolean = false,
)

/** Por qué una línea no se puede registrar. La pantalla elige el texto. */
sealed interface SupplierReturnLineError {
    /** Pasa de lo que queda de su línea de compra: a lo sumo [remaining]. */
    data class AboveRemaining(val remaining: QuantityMilli) : SupplierReturnLineError

    /** Lo escrito no es un número, o es cero. */
    data object NotANumber : SupplierReturnLineError

    /** La unidad no admite fracciones y la cantidad las trae. */
    data object FractionNotAllowed : SupplierReturnLineError

    /** La cantidad o sus cifras pasan de lo que la app puede guardar. */
    data object TooHigh : SupplierReturnLineError
}

/** Las hojas de la devolución. */
sealed interface SupplierReturnSheet {
    data class Suppliers(val query: String, val options: List<ReturnSupplierOption>) :
        SupplierReturnSheet

    data class Products(val query: String, val options: List<ReturnProductOption>) :
        SupplierReturnSheet

    /** Las compras activas que se pueden elegir de origen, de la más nueva a la más antigua. */
    data class Purchases(val options: List<ReturnPurchaseOption>) : SupplierReturnSheet
}

data class ReturnSupplierOption(val id: Long, val name: String)

data class ReturnProductOption(val id: Long, val name: String)

/** Una compra que se puede elegir: su número, su fecha, su proveedor de hoy y su total. */
data class ReturnPurchaseOption(
    val id: Long,
    val date: BusinessDate,
    val supplierName: String?,
    val total: Money,
)

/** Los avisos de la devolución, que no borran nada de lo elegido. */
sealed interface SupplierReturnNotice {
    /** No se pudo registrar: se reintenta con lo mismo. [outOfSpace] dice si falta espacio. */
    data class RegisterFailed(val outOfSpace: Boolean) : SupplierReturnNotice

    /** La moneda del negocio sigue sin confirmar. */
    data object CurrencyUnconfirmed : SupplierReturnNotice

    /** La cuenta elegida ya no puede recibir el reembolso: se elige otra o ninguna. */
    data object AccountUnavailable : SupplierReturnNotice

    /** No hay nada que devolver todavía. */
    data object NothingToReturn : SupplierReturnNotice
}

/** Lo que la persona hace en la devolución. */
sealed interface SupplierReturnAction {
    data object OpenSuppliers : SupplierReturnAction

    data object OpenProducts : SupplierReturnAction

    data object OpenPurchases : SupplierReturnAction

    data class QueryChange(val query: String) : SupplierReturnAction

    data object CloseSheet : SupplierReturnAction

    data class SelectSupplier(val supplierId: Long) : SupplierReturnAction

    /** Elige la compra de origen, o ninguna con `null`. Las líneas escritas se descartan. */
    data class SelectPurchase(val purchaseId: Long?) : SupplierReturnAction

    data class AddProduct(val productId: Long) : SupplierReturnAction

    data class QuantityChange(val key: Long, val text: String) : SupplierReturnAction

    data class RemoveLine(val key: Long) : SupplierReturnAction

    data class ResolutionChange(val resolution: PurchaseReturnResolution) : SupplierReturnAction

    data class AccountChange(val accountId: Long?) : SupplierReturnAction

    data class NoteChange(val text: String) : SupplierReturnAction

    data object Register : SupplierReturnAction

    data object Retry : SupplierReturnAction

    /** Ir a confirmar la moneda: lo escrito se conserva. */
    data object ConfirmCurrency : SupplierReturnAction

    data object Close : SupplierReturnAction

    data object ConfirmLeave : SupplierReturnAction

    data object DismissDialog : SupplierReturnAction

    /** El mensaje de devolución registrada ya se mostró: la pantalla termina. */
    data object RegisteredShown : SupplierReturnAction
}

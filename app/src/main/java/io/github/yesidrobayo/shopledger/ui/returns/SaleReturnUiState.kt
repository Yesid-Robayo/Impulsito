package io.github.yesidrobayo.shopledger.ui.returns

import androidx.compose.runtime.Immutable
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.money.MoneyAccount
import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnResolution
import io.github.yesidrobayo.shopledger.navigation.SaleExchange

/**
 * Lo que muestra la devolución de una venta. Todo sale del ViewModel: la pantalla solo dibuja.
 *
 * @param loading las líneas todavía no llegan: la pantalla dibuja un esqueleto si tarda.
 * @param loadFailed no se pudieron leer: se puede reintentar.
 * @param nothingLeft la venta no existe, está anulada o ya se devolvió todo: no hay qué elegir.
 * @param saleId el número de la venta, que es su id.
 * @param saleDate el día de la venta, para el subtítulo.
 * @param lines una por línea vendida, con lo que se devuelve de ella.
 * @param resolution cómo se resuelve: dinero, saldo a favor o cambio.
 * @param accounts las cuentas de las que puede salir el dinero; vacía si no se llevan cuentas.
 * @param accountId la cuenta elegida, o `null` para `Sin cuenta`.
 * @param customer el cliente del saldo a favor: el de la venta, o el elegido si la venta no tiene.
 * @param customerLocked el cliente es el de la venta y no se cambia.
 * @param creditSale la venta se cobró a crédito: el saldo a favor reduce su deuda.
 * @param needsCustomer se pidió saldo a favor sin cliente: se marca el campo.
 * @param countsOn el día en que cuenta la devolución, solo si la venta es de otro mes.
 * @param withPoints la venta ganó o canjeó puntos, que no cambian.
 * @param total lo que se devuelve, con el impuesto; [net] y [tax] lo desglosan.
 * @param showsTax la venta lleva impuesto: tocar el resumen despliega el desglose.
 * @param noneSelected se intentó registrar sin elegir nada.
 * @param registered llega una sola vez, con lo devuelto, al registrar.
 * @param exchange llega una sola vez, al continuar con el cambio: abre la venta nueva.
 */
@Immutable
data class SaleReturnUiState(
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val nothingLeft: Boolean = false,
    val saleId: Long = 0L,
    val saleDate: BusinessDate? = null,
    val lines: List<SaleReturnLineUi> = emptyList(),
    val resolution: ReturnResolution = ReturnResolution.REFUND,
    val accounts: List<MoneyAccount> = emptyList(),
    val accountId: Long? = null,
    val customer: ReturnCustomerUi? = null,
    val customerLocked: Boolean = false,
    val creditSale: Boolean = false,
    val needsCustomer: Boolean = false,
    val countsOn: BusinessDate? = null,
    val withPoints: Boolean = false,
    val note: String = "",
    val noteTooLong: Boolean = false,
    val itemCount: Int = 0,
    val total: Money = Money.ZERO,
    val net: Money = Money.ZERO,
    val tax: Money = Money.ZERO,
    val showsTax: Boolean = false,
    val taxExpanded: Boolean = false,
    val noneSelected: Boolean = false,
    val sheet: ReturnQuantitySheet? = null,
    val discardAsked: Boolean = false,
    val notice: SaleReturnNotice? = null,
    val registering: Boolean = false,
    val registered: Money? = null,
    val exchange: SaleExchange? = null,
) {
    /** Si hay algo elegido o escrito que se perdería al salir. */
    val hasInput: Boolean
        get() = lines.any { it.quantity > QuantityMilli.ZERO } || note.isNotBlank()
}

/**
 * Una línea vendida en la devolución.
 *
 * @param unit la unidad base del producto, en la que se cuenta lo devuelto.
 * @param sold lo vendido, en la unidad base.
 * @param returnedBefore lo que ya devolvieron las devoluciones activas.
 * @param remaining lo que falta por devolver: el tope de [quantity].
 * @param quantity lo que se devuelve ahora.
 * @param restock si vuelve al inventario; sin reingreso cuenta como perdida.
 * @param returnable la línea admite devolución: un combo, que hoy no se vende, no.
 */
@Immutable
data class SaleReturnLineUi(
    val lineId: Long,
    val name: String,
    val unit: MeasureUnit?,
    val sold: QuantityMilli,
    val returnedBefore: QuantityMilli,
    val remaining: QuantityMilli,
    val quantity: QuantityMilli,
    val restock: Boolean,
    val archived: Boolean,
    val returnable: Boolean = true,
) {
    val canIncrement: Boolean get() = returnable && quantity < remaining
}

/**
 * El cliente del saldo a favor: su nombre, su saldo de hoy y el que quedará. Un nombre `null` es un
 * cliente anonimizado.
 */
@Immutable
data class ReturnCustomerUi(
    val id: Long,
    val name: String?,
    val balance: Money,
    val balanceAfter: Money,
)

/** La hoja para escribir la cantidad de una línea cuya unidad admite fracciones. */
@Immutable
data class ReturnQuantitySheet(
    val lineId: Long,
    val name: String,
    val unit: MeasureUnit?,
    val text: String,
    val error: ReturnQuantityError? = null,
)

/** Por qué no se acepta lo escrito en la hoja de cantidad. */
sealed interface ReturnQuantityError {
    /** Pasa de lo que falta por devolver: a lo sumo [remaining]. */
    data class AboveRemaining(val remaining: QuantityMilli) : ReturnQuantityError

    /** Lo escrito no es un número. */
    data object NotANumber : ReturnQuantityError
}

/** Los avisos de la devolución, que no borran nada de lo elegido. */
sealed interface SaleReturnNotice {
    /** No se pudo registrar: se reintenta con lo mismo. [outOfSpace] dice si falta espacio. */
    data class RegisterFailed(val outOfSpace: Boolean) : SaleReturnNotice

    /** La cuenta elegida ya no puede devolver el dinero: se elige otra o ninguna. */
    data object AccountUnavailable : SaleReturnNotice
}

/** Lo que la persona hace en la devolución. */
sealed interface SaleReturnAction {
    data class Increment(val lineId: Long) : SaleReturnAction

    data class Decrement(val lineId: Long) : SaleReturnAction

    /** Abre la hoja de cantidad de una línea con fracciones. */
    data class EditQuantity(val lineId: Long) : SaleReturnAction

    data class QuantityTextChange(val text: String) : SaleReturnAction

    data object QuantityConfirm : SaleReturnAction

    data object CloseSheet : SaleReturnAction

    data class RestockChange(val lineId: Long, val restock: Boolean) : SaleReturnAction

    data class ResolutionChange(val resolution: ReturnResolution) : SaleReturnAction

    data class AccountChange(val accountId: Long?) : SaleReturnAction

    /** Abre la lista de clientes para elegir el del saldo a favor. */
    data object PickCustomer : SaleReturnAction

    /** La lista de clientes devolvió [customerId]. */
    data class CustomerPicked(val customerId: Long) : SaleReturnAction

    data object RemoveCustomer : SaleReturnAction

    data class NoteChange(val text: String) : SaleReturnAction

    data object ToggleTax : SaleReturnAction

    /** Registra la devolución, o continúa con el cambio. */
    data object Register : SaleReturnAction

    data object Retry : SaleReturnAction

    data object Close : SaleReturnAction

    data object ConfirmLeave : SaleReturnAction

    data object DismissDialog : SaleReturnAction

    /** El mensaje de devolución registrada ya se mostró: la pantalla termina. */
    data object RegisteredShown : SaleReturnAction

    /** La venta nueva del cambio ya se abrió. */
    data object ExchangeShown : SaleReturnAction
}

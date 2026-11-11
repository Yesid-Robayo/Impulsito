package io.github.yesidrobayo.shopledger.ui.sale

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols

/** Lo que la persona hace en la venta nueva. La navegación la resuelve la ruta. */
sealed interface NewSaleAction {
    data class QueryChange(val query: String) : NewSaleAction

    /** Tocar la tarjeta de un producto: agrega uno, o abre su hoja si hace falta. */
    data class AddProduct(val productId: Long) : NewSaleAction

    data class Increment(val productId: Long) : NewSaleAction

    data class Decrement(val productId: Long) : NewSaleAction

    /** Tocar la cantidad de un producto por peso o medida: abre la hoja para cambiarla. */
    data class EditQuantity(val productId: Long) : NewSaleAction

    data class SelectMethod(val methodId: Long) : NewSaleAction

    data object OpenPaymentMethods : NewSaleAction

    data object OpenCart : NewSaleAction

    data class OpenLine(val productId: Long) : NewSaleAction

    data object CloseSheet : NewSaleAction

    data class LineQuantityChange(val text: String) : NewSaleAction

    data class LinePriceChange(val text: String) : NewSaleAction

    data class LineDiscountModeChange(val mode: DiscountMode) : NewSaleAction

    data class LineDiscountChange(val text: String) : NewSaleAction

    data class RemoveLine(val productId: Long) : NewSaleAction

    data class QuantityTextChange(val text: String) : NewSaleAction

    data object QuantityConfirm : NewSaleAction

    /** Un atajo de la hoja de cantidad, como medio kilo: agrega y cierra. */
    data class QuantityShortcut(val quantity: QuantityMilli) : NewSaleAction

    data class DateChange(val date: BusinessDate) : NewSaleAction

    data class NoteChange(val note: String) : NewSaleAction

    data object ClearSale : NewSaleAction

    data object ConfirmClear : NewSaleAction

    /** Cerrar o volver atrás: con productos, pregunta antes de descartarlos. */
    data object Close : NewSaleAction

    data object ConfirmLeave : NewSaleAction

    data object DismissDialog : NewSaleAction

    data object Register : NewSaleAction

    /** Confirmar una venta en cero, el único caso que lo pide. */
    data object ConfirmZeroTotal : NewSaleAction

    /** Crear un producto que la búsqueda no encontró, con lo escrito como nombre. */
    data class CreateProduct(val name: String? = null) : NewSaleAction

    data class Reactivate(val productId: Long) : NewSaleAction

    /** Ir a confirmar la moneda del negocio. */
    data object ConfirmCurrency : NewSaleAction

    data object RetryRead : NewSaleAction

    data object RegisteredShown : NewSaleAction

    data object NoticeShown : NewSaleAction
}

/**
 * Cómo se leen los números de la venta: los símbolos del formato regional del negocio para montos,
 * cantidades y porcentajes, y los decimales de su moneda. La ruta los da desde sus formateadores,
 * así el ViewModel lee lo escrito igual que lo muestran los campos.
 */
data class SaleNumberFormat(
    val moneySymbols: NumberSymbols,
    val currencyDecimals: Int,
    val quantitySymbols: NumberSymbols,
    val percentSymbols: NumberSymbols,
)

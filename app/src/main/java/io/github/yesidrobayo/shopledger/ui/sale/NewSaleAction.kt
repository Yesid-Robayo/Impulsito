package io.github.yesidrobayo.shopledger.ui.sale

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.region.NumberSymbols

/** Lo que la persona hace en la venta nueva. La navegación la resuelve la ruta. */
sealed interface NewSaleAction {
    data class QueryChange(val query: String) : NewSaleAction

    /** Tocar la tarjeta de un producto: agrega uno, o abre su hoja si hace falta. */
    data class AddProduct(val productId: Long) : NewSaleAction

    /** Elige [value] en el eje [axis] del selector de variantes abierto. */
    data class PickVariantValue(val axis: Int, val value: String) : NewSaleAction

    /**
     * Un producto que trajo el escáner: agrega uno, como al tocarlo. Con [unitId], el código era el
     * de esa presentación, y una línea nueva se vende en ella.
     */
    data class AddScanned(val productId: Long, val unitId: Long?) : NewSaleAction

    /** Abrir el escáner: lo resuelve la navegación. */
    data object Scan : NewSaleAction

    /**
     * Enter en la búsqueda, como termina un lector externo de códigos: si lo escrito es el código
     * de un producto que se vende, se agrega y la búsqueda se vacía.
     */
    data object SubmitSearch : NewSaleAction

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

    /** Vender la línea en otra unidad del producto, como una caja. */
    data class LineUnitChange(val unitId: Long) : NewSaleAction

    /** Eligió el lote de la línea abierta, o `null` para que salga por vencimiento. */
    data class LineLotChange(val lotId: Long?) : NewSaleAction

    /** Pone o quita el extra [productId] en la línea abierta. */
    data class ToggleExtra(val productId: Long) : NewSaleAction

    data class RemoveLine(val productId: Long) : NewSaleAction

    data class QuantityTextChange(val text: String) : NewSaleAction

    data object QuantityConfirm : NewSaleAction

    /** Un atajo de la hoja de cantidad, como medio kilo: agrega y cierra. */
    data class QuantityShortcut(val quantity: QuantityMilli) : NewSaleAction

    data class DateChange(val date: BusinessDate) : NewSaleAction

    data class NoteChange(val note: String) : NewSaleAction

    /** Elige la lista de precios de la venta: las líneas sin precio escrito toman el de ella. */
    data class SelectPriceList(val listId: Long) : NewSaleAction

    /** Elige la ubicación de donde sale el stock de la venta. */
    data class SelectLocation(val locationId: Long) : NewSaleAction

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

    /** `Más opciones de cobro`: abre el cobro con la venta como está. */
    data object OpenCheckout : NewSaleAction

    /** `Mostrar QR`: abre a pantalla completa la imagen de cobro del método elegido. */
    data object ShowQr : NewSaleAction

    /** Abrir la hoja para poner la venta en espera, con su nombre opcional. */
    data object OpenHold : NewSaleAction

    data class HoldLabelChange(val text: String) : NewSaleAction

    data object ConfirmHold : NewSaleAction

    /** Abre la hoja de `Guardar cotización`, con las líneas de la venta. */
    data object OpenSaveQuote : NewSaleAction

    data class QuoteValidUntilChange(val date: BusinessDate?) : NewSaleAction

    data class QuoteNoteChange(val note: String) : NewSaleAction

    data object QuoteOpenCustomers : NewSaleAction

    data object QuoteCloseCustomers : NewSaleAction

    data class QuoteCustomerQuery(val query: String) : NewSaleAction

    data class QuotePickCustomer(val customerId: Long) : NewSaleAction

    data object QuoteRemoveCustomer : NewSaleAction

    /** Crear un cliente que no está: lo resuelve la ruta, que abre Clientes. */
    data class QuoteNewCustomer(val name: String) : NewSaleAction

    data object ConfirmSaveQuote : NewSaleAction

    data object QuoteSavedShown : NewSaleAction

    /** Ir a las ventas en espera. */
    data object OpenHeldSales : NewSaleAction

    /**
     * Retomar una venta en espera elegida en su lista. Si hay otra venta en edición, la persona ya
     * confirmó que pase a espera.
     */
    data class ResumeHeld(val heldSaleId: Long) : NewSaleAction

    data object HeldNoticeShown : NewSaleAction
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

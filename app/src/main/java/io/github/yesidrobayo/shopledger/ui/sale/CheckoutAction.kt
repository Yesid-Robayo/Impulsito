package io.github.yesidrobayo.shopledger.ui.sale

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate

/** Lo que la persona hace en el cobro. La navegación la resuelve la ruta. */
sealed interface CheckoutAction {
    /** Vuelve a leer el catálogo tras una falla de lectura. */
    data object Retry : CheckoutAction

    /** Lo escrito en el monto del pago número [index]. */
    data class AmountChange(val index: Int, val text: String) : CheckoutAction

    /** Lo escrito en la comisión del pago número [index]. En blanco vuelve a la del método. */
    data class FeeChange(val index: Int, val text: String) : CheckoutAction

    /** Lo recibido en efectivo en el pago número [index]. En blanco, no hay cambio que calcular. */
    data class TenderedChange(val index: Int, val text: String) : CheckoutAction

    /** `Monto exacto`: lo recibido es justo lo que cubre el pago número [index]. */
    data class ExactAmount(val index: Int) : CheckoutAction

    data class RemovePayment(val index: Int) : CheckoutAction

    /** Lo escrito en la propina. En blanco es sin propina. */
    data class TipChange(val text: String) : CheckoutAction

    /** `Sin propina`: la quita. */
    data object NoTip : CheckoutAction

    data object OpenAddPayment : CheckoutAction

    /** Agrega un pago con [methodId], con lo que falta ya escrito. */
    data class AddPayment(val methodId: Long) : CheckoutAction

    /** Completa el último pago tocado con lo que falta, o le quita lo que sobra. */
    data object UseDue : CheckoutAction

    data object OpenAdjustment : CheckoutAction

    data class AdjustmentKindChange(val kind: AdjustmentKind) : CheckoutAction

    data class AdjustmentInputChange(val input: AdjustmentInput) : CheckoutAction

    data class AdjustmentTextChange(val text: String) : CheckoutAction

    data object ApplyAdjustment : CheckoutAction

    data object RemoveAdjustment : CheckoutAction

    data object CloseSheet : CheckoutAction

    /** Muestra a pantalla completa la imagen de cobro del pago número [index]. */
    data class ShowQr(val index: Int) : CheckoutAction

    /** Cierra la imagen de cobro. Si se abrió desde la venta, vuelve a ella. */
    data object CloseQr : CheckoutAction

    data object Register : CheckoutAction

    /** Confirmar un cobro en cero, el único caso que lo pide. */
    data object ConfirmZeroTotal : CheckoutAction

    data object DismissDialog : CheckoutAction

    data object RegisteredShown : CheckoutAction

    /** Abre la lista de clientes para elegir el de la venta. */
    data object OpenCustomers : CheckoutAction

    data class CustomerQueryChange(val query: String) : CheckoutAction

    data class PickCustomer(val customerId: Long) : CheckoutAction

    /** Abre la hoja de un cliente nuevo, con [name] ya escrito si se buscó y no estaba. */
    data class NewCustomer(val name: String = "") : CheckoutAction

    /** Quita el cliente, y con él los pagos a crédito. */
    data object RemoveCustomer : CheckoutAction

    /** El vencimiento del cargo a crédito, o `null` para dejarlo sin vencimiento. */
    data class CreditDueChange(val date: BusinessDate?) : CheckoutAction

    data object CustomerNoticeShown : CheckoutAction

    /** Lo escrito en `Puntos por canjear`. No cambia el total hasta tocar `Canjear puntos`. */
    data class PointsChange(val text: String) : CheckoutAction

    /** `Canjear puntos`: aplica lo escrito como descuento de la venta. En blanco, quita el canje. */
    data object RedeemPoints : CheckoutAction
}

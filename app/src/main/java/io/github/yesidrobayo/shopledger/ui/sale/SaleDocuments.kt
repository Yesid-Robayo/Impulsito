package io.github.yesidrobayo.shopledger.ui.sale

import androidx.compose.runtime.Immutable
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.customer.Customer
import io.github.yesidrobayo.shopledger.domain.model.document.DocumentLine
import io.github.yesidrobayo.shopledger.domain.model.sale.LineDiscount
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.usecase.document.OrderDeliveryResult
import io.github.yesidrobayo.shopledger.domain.usecase.document.OrderRequest
import io.github.yesidrobayo.shopledger.domain.usecase.document.OrderView
import io.github.yesidrobayo.shopledger.domain.usecase.document.QuoteConversionResult
import io.github.yesidrobayo.shopledger.domain.usecase.document.QuoteRequest
import io.github.yesidrobayo.shopledger.domain.usecase.document.QuoteView
import io.github.yesidrobayo.shopledger.domain.usecase.document.SaveOrderResult
import io.github.yesidrobayo.shopledger.domain.usecase.document.SaveQuoteResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Lo que la venta nueva necesita de las cotizaciones y los pedidos. Sin conectar, no guarda nada.
 */
interface SaleDocumentOperations {
    suspend fun saveQuote(request: QuoteRequest): SaveQuoteResult

    suspend fun quote(quoteId: Long): QuoteView?

    suspend fun conversion(quoteId: Long): QuoteConversionResult

    /** Los clientes que se pueden elegir para una cotización, con su saldo. */
    fun customers(): Flow<List<Customer>>

    /** Guarda un pedido, nuevo o editado. Sin pedidos conectados, no guarda nada. */
    suspend fun saveOrder(request: OrderRequest): SaveOrderResult = SaveOrderResult.EmptyOrder

    /** El pedido [orderId] con sus líneas, para editarlo, o `null` si no existe. */
    suspend fun order(orderId: Long): OrderView? = null

    /** Lo que necesita la venta para entregar el pedido [orderId], sin escribir nada. */
    suspend fun delivery(orderId: Long): OrderDeliveryResult = OrderDeliveryResult.NotFound

    /** Sin cotizaciones conectadas, como en una vista previa. */
    object None : SaleDocumentOperations {
        override suspend fun saveQuote(request: QuoteRequest): SaveQuoteResult =
            SaveQuoteResult.EmptyQuote

        override suspend fun quote(quoteId: Long): QuoteView? = null

        override suspend fun conversion(quoteId: Long): QuoteConversionResult =
            QuoteConversionResult.NotFound

        override fun customers(): Flow<List<Customer>> = flowOf(emptyList())
    }
}

/**
 * Lo que la pantalla muestra de la cotización que se arma: [editingId] es su número si se edita una
 * guardada, o `null` si es nueva.
 */
@Immutable
data class QuoteEditing(val editingId: Long?)

/**
 * La hoja de `Guardar cotización`: el cliente, la vigencia y la nota, tal cual. [customerName] es el
 * nombre del elegido. [pastDate] marca una vigencia anterior a hoy, [noLines] una cotización sin
 * productos, [extras] una con extras, que no se guardan, y [failed] que no se pudo guardar: lo
 * escrito sigue aquí.
 */
@Immutable
data class QuoteSaveSheet(
    val customerId: Long? = null,
    val customerName: String? = null,
    val customerBalance: Money = Money.ZERO,
    val validUntil: BusinessDate? = null,
    val note: String = "",
    val today: BusinessDate? = null,
    val working: Boolean = false,
    val pastDate: Boolean = false,
    val noLines: Boolean = false,
    val extras: Boolean = false,
    val failed: Boolean = false,
    val choosingCustomer: Boolean = false,
    val customerQuery: String = "",
    val customerChoices: List<Customer> = emptyList(),
)

/**
 * Lo que la pantalla muestra del pedido que se arma: [editingId] es su número si se edita uno
 * guardado, o `null` si es nuevo.
 */
@Immutable
data class OrderEditing(val editingId: Long?)

/** Qué campo de texto de la hoja del pedido cambió. */
enum class OrderTextField {
    ADDRESS,
    NOTE,
    SHIPPING,
}

/**
 * La hoja de `Guardar pedido`: el cliente, la fecha y la dirección de entrega, la nota de entrega y
 * el cobro por envío, todo opcional y tal cual se escribió. [customerName] es el nombre del elegido.
 * [shippingError] marca un envío que no se puede leer o que pasa del tope, [textTooLong] una
 * dirección o una nota de más de 200 caracteres, [noLines] un pedido sin productos, [extras] uno con
 * extras, que no se guardan, y [failed] que no se pudo guardar: lo escrito sigue aquí.
 */
@Immutable
data class OrderSaveSheet(
    val customerId: Long? = null,
    val customerName: String? = null,
    val customerBalance: Money = Money.ZERO,
    val dueDate: BusinessDate? = null,
    val address: String = "",
    val deliveryNote: String = "",
    val shippingText: String = "",
    val today: BusinessDate? = null,
    val working: Boolean = false,
    val shippingError: Boolean = false,
    val textTooLong: Boolean = false,
    val noLines: Boolean = false,
    val extras: Boolean = false,
    val failed: Boolean = false,
    val choosingCustomer: Boolean = false,
    val customerQuery: String = "",
    val customerChoices: List<Customer> = emptyList(),
)

/**
 * La venta que entrega el pedido [orderId]: [shipping] entra en el ajuste de la venta y [credit] es
 * el saldo a favor del cliente, que el cobro usa a crédito. Con alguno de los dos, la venta se
 * confirma en el cobro, que los muestra; sin ninguno, se registra desde aquí.
 */
@Immutable
data class DeliveryBand(val orderId: Long, val shipping: Money, val credit: Money) {
    val needsCheckout: Boolean get() = shipping > Money.ZERO || credit > Money.ZERO
}

/** Cómo pasa una cotización guardada a la venta. */
internal object QuoteLines {
    /**
     * Las líneas de [lines] como las arma la venta: la cantidad en su unidad, el precio cotizado
     * escrito y el descuento en monto, para editarlas y guardarlas de nuevo.
     */
    fun requestsOf(lines: List<DocumentLine>): List<SaleLineRequest> = lines.map { line ->
        val content = line.content
        SaleLineRequest(
            productId = content.productId,
            quantity = content.enteredQuantity,
            unitPrice = content.unitPrice,
            discount = if (content.lineDiscount == Money.ZERO) {
                LineDiscount.None
            } else {
                LineDiscount.Amount(content.lineDiscount)
            },
            unitId = content.unitId,
        )
    }
}

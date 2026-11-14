package io.github.yesidrobayo.shopledger.ui.sale

import androidx.compose.runtime.Immutable
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.customer.Customer
import io.github.yesidrobayo.shopledger.domain.model.document.DocumentLine
import io.github.yesidrobayo.shopledger.domain.model.sale.LineDiscount
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.usecase.document.QuoteConversionResult
import io.github.yesidrobayo.shopledger.domain.usecase.document.QuoteRequest
import io.github.yesidrobayo.shopledger.domain.usecase.document.QuoteView
import io.github.yesidrobayo.shopledger.domain.usecase.document.SaveQuoteResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Lo que la venta nueva necesita de las cotizaciones. Sin conectar, no guarda nada. */
interface SaleDocumentOperations {
    suspend fun saveQuote(request: QuoteRequest): SaveQuoteResult

    suspend fun quote(quoteId: Long): QuoteView?

    suspend fun conversion(quoteId: Long): QuoteConversionResult

    /** Los clientes que se pueden elegir para una cotización, con su saldo. */
    fun customers(): Flow<List<Customer>>

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

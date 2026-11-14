package io.github.yesidrobayo.shopledger.domain.usecase.document

import io.github.yesidrobayo.shopledger.domain.model.document.DocumentLine
import io.github.yesidrobayo.shopledger.domain.model.document.Quote
import io.github.yesidrobayo.shopledger.domain.model.document.QuoteStatus
import io.github.yesidrobayo.shopledger.domain.port.CustomerRepository
import io.github.yesidrobayo.shopledger.domain.port.QuoteRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Una cotización en su lista, con el nombre de su cliente, o `null` si no tiene o si se
 * anonimizó: entonces la lista dice `Sin cliente`.
 */
data class QuoteRow(val quote: Quote, val customerName: String?)

/** Una cotización con sus líneas, en el orden en que se agregaron, y el nombre de su cliente. */
data class QuoteView(val quote: Quote, val lines: List<DocumentLine>, val customerName: String?)

/**
 * Lo que leen la lista y el detalle de las cotizaciones. Solo lee: cada lista vuelve a emitir con
 * cada transacción que cambia una cotización.
 */
class ObserveQuotesUseCase(
    private val quotes: QuoteRepository,
    private val customers: CustomerRepository,
) {
    /** Las de [status], de la más nueva a la más vieja. */
    fun list(status: QuoteStatus): Flow<List<QuoteRow>> =
        quotes.observeByStatus(status).map { rows ->
            val names = HashMap<Long, String?>()
            rows.map { quote ->
                val name = quote.customerId?.let { id -> names.getOrPut(id) { nameOf(id) } }
                QuoteRow(quote, name)
            }
        }

    /** La cotización [quoteId] con sus líneas, o `null` si no existe. */
    fun detail(quoteId: Long): Flow<QuoteView?> = quotes.observeById(quoteId).map { quote ->
        quote?.let {
            QuoteView(it, quotes.findLines(it.id), it.customerId?.let { id -> nameOf(id) })
        }
    }

    private suspend fun nameOf(customerId: Long): String? =
        customers.findById(customerId)?.contact?.name
}

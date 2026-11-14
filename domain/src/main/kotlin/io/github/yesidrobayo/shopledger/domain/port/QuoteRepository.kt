package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.document.DocumentLine
import io.github.yesidrobayo.shopledger.domain.model.document.NewQuote
import io.github.yesidrobayo.shopledger.domain.model.document.Quote
import io.github.yesidrobayo.shopledger.domain.model.document.QuoteStatus
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * Las cotizaciones y sus líneas. No son un libro: mientras están abiertas se editan, y sus líneas
 * se rehacen. Cerradas, convertidas o canceladas, ya no cambian.
 *
 * Las escrituras solo se llaman dentro de [TransactionRunner.write]. Las lecturas que devuelven
 * [Flow] vuelven a emitir con cada transacción que cambia lo leído.
 */
interface QuoteRepository {
    /** Las de [status], de la fecha de negocio más nueva a la más vieja, y luego por id. */
    fun observeByStatus(status: QuoteStatus): Flow<List<Quote>>

    /** La cotización [id], o `null` si no existe. */
    fun observeById(id: Long): Flow<Quote?>

    suspend fun findById(id: Long): Quote?

    /** Sus líneas, en el orden en que se agregaron. */
    suspend fun findLines(quoteId: Long): List<DocumentLine>

    /** Guarda una cotización abierta con sus líneas y devuelve su id. */
    suspend fun insert(quote: NewQuote): Long

    /**
     * Cambia la cotización abierta [id]: cliente, vigencia, nota, totales y todas sus líneas. Lo
     * que dice cuándo se cotizó no cambia. Devuelve `false` si ya no está abierta.
     */
    suspend fun update(id: Long, quote: NewQuote): Boolean

    /**
     * La marca convertida en la venta [saleId], que se escribe en la misma transacción. Devuelve
     * `false` si ya no estaba abierta: una cotización se convierte una sola vez.
     */
    suspend fun markConverted(id: Long, saleId: Long, at: Instant): Boolean

    /** La cancela. Devuelve `false` si ya no estaba abierta. */
    suspend fun markCancelled(id: Long, at: Instant): Boolean
}

package io.github.yesidrobayo.shopledger.domain.model.document

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import kotlin.time.Instant

/**
 * En qué va una cotización. Nace [OPEN], y pasa una sola vez a [CONVERTED], con la venta que salió
 * de ella, o a [CANCELLED], que no se reabre. Los nombres se guardan tal cual: no cambian nunca.
 */
enum class QuoteStatus {
    OPEN,
    CONVERTED,
    CANCELLED,
}

/**
 * Una cotización como se guarda, sin sus líneas: una oferta de precios para un cliente, que no
 * toca stock, dinero ni reportes.
 *
 * [validUntil] es la vigencia que eligió la persona, si eligió una. Vencida, sigue [QuoteStatus.OPEN]
 * y se puede convertir tras un aviso. [convertedSaleId] es la venta que salió de ella, solo con
 * [QuoteStatus.CONVERTED]. Su número visible es [id].
 */
data class Quote(
    val id: Long,
    val customerId: Long?,
    val status: QuoteStatus,
    val validUntil: BusinessDate?,
    val totals: DocumentTotals,
    val convertedSaleId: Long?,
    val note: String?,
    val occurredAt: Instant,
    val businessDate: BusinessDate,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * Lo que se guarda de una cotización, nueva o editada: el cliente, la vigencia, la nota y sus
 * líneas ya calculadas, con sus totales. [at] es el instante del cambio, y [occurredAt] con
 * [businessDate] cuándo se cotizó: al editarla se conservan los de la primera vez.
 */
data class NewQuote(
    val customerId: Long?,
    val validUntil: BusinessDate?,
    val note: String?,
    val lines: List<NewDocumentLine>,
    val totals: DocumentTotals,
    val occurredAt: Instant,
    val businessDate: BusinessDate,
    val at: Instant,
)

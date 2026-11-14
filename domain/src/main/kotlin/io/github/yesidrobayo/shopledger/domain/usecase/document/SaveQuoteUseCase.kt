package io.github.yesidrobayo.shopledger.domain.usecase.document

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.document.NewQuote
import io.github.yesidrobayo.shopledger.domain.model.document.QuoteStatus
import io.github.yesidrobayo.shopledger.domain.model.region.RecordTime
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.CustomerRepository
import io.github.yesidrobayo.shopledger.domain.port.PriceListRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductPriceRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductUnitRepository
import io.github.yesidrobayo.shopledger.domain.port.QuoteRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.SystemRowRepository
import io.github.yesidrobayo.shopledger.domain.port.TaxRateRepository
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.port.UnitRepository
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLock
import io.github.yesidrobayo.shopledger.domain.usecase.region.CurrencyLockResult

/**
 * Lo que la persona guarda como cotización: las líneas armadas en la venta, el cliente si eligió
 * uno, la vigencia y la nota, y la lista de precios con que se armó, nula para la inicial.
 * [quoteId] es la cotización que se está editando, o nula para una nueva.
 */
data class QuoteRequest(
    val lines: List<SaleLineRequest>,
    val customerId: Long? = null,
    val validUntil: BusinessDate? = null,
    val note: String? = null,
    val priceListId: Long? = null,
    val quoteId: Long? = null,
)

/** Resultado de [SaveQuoteUseCase]. Cualquier rechazo deja todo como estaba. */
sealed interface SaveQuoteResult {
    /** La cotización quedó guardada con [quoteId], que también es su número visible. */
    data class Saved(val quoteId: Long) : SaveQuoteResult

    /** No tiene líneas. */
    data object EmptyQuote : SaveQuoteResult

    /** Tiene más líneas que el tope de un documento. */
    data object TooManyLines : SaveQuoteResult

    /** La nota pasa de su largo máximo. */
    data object NoteTooLong : SaveQuoteResult

    /** La vigencia es anterior a hoy. */
    data object ValidUntilInPast : SaveQuoteResult

    /** El cliente ya no existe, se archivó o se anonimizó: se elige otro o ninguno. */
    data object CustomerUnavailable : SaveQuoteResult

    /** La lista de precios elegida ya no existe o se archivó. */
    data object PriceListUnavailable : SaveQuoteResult

    /** La línea [index], contada desde cero, no se puede guardar, por [reason]. */
    data class LineRejected(val index: Int, val reason: DocumentLineRejection) : SaveQuoteResult

    /** Lo que suman las líneas pasa del tope de un documento. */
    data object TotalTooLarge : SaveQuoteResult

    /** La cotización que se editaba ya no existe, o ya se convirtió o canceló. */
    data object QuoteNotOpen : SaveQuoteResult

    /** La moneda del negocio todavía no está confirmada. */
    data object CurrencyNotConfirmed : SaveQuoteResult

    /** Este teléfono no conoce la zona del negocio [storedZoneId]: no hay cómo fecharla. */
    data class UnknownTimeZone(val storedZoneId: String) : SaveQuoteResult

    /** La base no pudo escribir, por [failure]. No quedó nada guardado. */
    data class StorageFailed(val failure: StorageFailure) : SaveQuoteResult
}

/**
 * Guarda una cotización, nueva o editada, en una sola transacción.
 *
 * - **No toca stock, dinero ni reportes.** Solo escribe la cotización y sus líneas: ningún libro,
 *   saldo ni alerta. Sí cuenta como cambio sin respaldar: es un hecho del negocio que la persona
 *   espera encontrar mañana.
 * - **Congela precios e impuesto.** Cada línea se calcula como la calcularía la venta hoy, con la
 *   lista elegida y la tasa del producto, y guarda el precio, el descuento, el impuesto y el nombre.
 *   Si después cambia un precio, la cotización no cambia sola: avisa al convertirla.
 * - **La moneda se bloquea** con la primera cotización, como con cualquier registro con dinero: sus
 *   montos van en la unidad menor de esa moneda.
 * - **Editar rehace sus líneas** y conserva cuándo se cotizó. Solo se edita una abierta.
 * - **La vigencia es opcional,** y nunca anterior a hoy al guardar. Vencida después, sigue abierta.
 */
class SaveQuoteUseCase(
    private val transactions: TransactionRunner,
    private val settings: AppSettingsRepository,
    private val quotes: QuoteRepository,
    private val customers: CustomerRepository,
    private val clock: Clock,
    products: ProductRepository,
    units: UnitRepository,
    taxRates: TaxRateRepository,
    systemRows: SystemRowRepository,
    productUnits: ProductUnitRepository? = null,
    priceLists: PriceListRepository? = null,
    productPrices: ProductPriceRepository? = null,
) {
    private val currencyLock = CurrencyLock(settings)
    private val pricer = DocumentLinePricer(
        products,
        units,
        taxRates,
        systemRows,
        productUnits,
        priceLists,
        productPrices,
    )

    suspend operator fun invoke(request: QuoteRequest): SaveQuoteResult = try {
        transactions.write { save(request) }
    } catch (failure: StorageFailureException) {
        SaveQuoteResult.StorageFailed(failure.failure)
    }

    private suspend fun save(request: QuoteRequest): TxResult<SaveQuoteResult> {
        shapeRejection(request)?.let { return TxResult.Rollback(it) }
        val current = settings.get()
        val calendar = when (val result = BusinessCalendar.of(current.regional)) {
            is BusinessCalendarResult.Ready -> result.calendar

            is BusinessCalendarResult.UnknownZone ->
                return TxResult.Rollback(SaveQuoteResult.UnknownTimeZone(result.storedZoneId))
        }
        val now = clock.now()
        val today = calendar.stamp(now)
        val validUntil = request.validUntil
        if (validUntil != null && validUntil < today.businessDate) {
            return TxResult.Rollback(SaveQuoteResult.ValidUntilInPast)
        }
        request.customerId?.let { id ->
            val customer = customers.findById(id)
            if (customer == null || customer.isArchived || customer.isAnonymized) {
                return TxResult.Rollback(SaveQuoteResult.CustomerUnavailable)
            }
        }
        // Al editar, la cotización tiene que seguir abierta, y conserva cuándo se cotizó.
        val stamp = when (val id = request.quoteId) {
            null -> today

            else -> {
                val existing = quotes.findById(id)
                if (existing == null || existing.status != QuoteStatus.OPEN) {
                    return TxResult.Rollback(SaveQuoteResult.QuoteNotOpen)
                }
                RecordTime(existing.occurredAt, existing.businessDate)
            }
        }
        val priced = when (
            val result = pricer.price(request.lines, request.priceListId, current.taxes)
        ) {
            is DocumentPricing.Lines -> result

            is DocumentPricing.Rejected ->
                return TxResult.Rollback(SaveQuoteResult.LineRejected(result.index, result.reason))

            DocumentPricing.TotalTooLarge -> return TxResult.Rollback(SaveQuoteResult.TotalTooLarge)

            DocumentPricing.PriceListUnavailable ->
                return TxResult.Rollback(SaveQuoteResult.PriceListUnavailable)
        }
        when (currencyLock.lockForMoneyRecord(now)) {
            is CurrencyLockResult.Locked -> Unit

            CurrencyLockResult.Unconfirmed ->
                return TxResult.Rollback(SaveQuoteResult.CurrencyNotConfirmed)
        }
        val quote = NewQuote(
            customerId = request.customerId,
            validUntil = validUntil,
            note = request.note?.takeIf { it.isNotBlank() },
            lines = priced.lines,
            totals = priced.totals,
            occurredAt = stamp.occurredAt,
            businessDate = stamp.businessDate,
            at = now,
        )
        val id = when (val existing = request.quoteId) {
            null -> quotes.insert(quote)

            else -> if (quotes.update(existing, quote)) {
                existing
            } else {
                return TxResult.Rollback(SaveQuoteResult.QuoteNotOpen)
            }
        }
        return TxResult.Commit(SaveQuoteResult.Saved(id))
    }

    private fun shapeRejection(request: QuoteRequest): SaveQuoteResult? = when {
        request.lines.isEmpty() -> SaveQuoteResult.EmptyQuote
        request.lines.size > Limit.DOCUMENT_LINES.max -> SaveQuoteResult.TooManyLines
        (request.note?.length ?: 0) > MAX_NOTE_LENGTH -> SaveQuoteResult.NoteTooLong
        else -> null
    }

    companion object {
        /** El largo máximo de la nota, el mismo que acepta la base. */
        const val MAX_NOTE_LENGTH = 200
    }
}

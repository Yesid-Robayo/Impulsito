package io.github.yesidrobayo.shopledger.domain.usecase.document

import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.document.DocumentLine
import io.github.yesidrobayo.shopledger.domain.model.document.QuoteStatus
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.port.AppSettingsRepository
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.CustomerRepository
import io.github.yesidrobayo.shopledger.domain.port.PriceListRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductPriceRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductUnitRepository
import io.github.yesidrobayo.shopledger.domain.port.QuoteRepository
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.rule.pricing.LinePriceList
import io.github.yesidrobayo.shopledger.domain.rule.pricing.SalePriceList
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendar
import io.github.yesidrobayo.shopledger.domain.rule.region.BusinessCalendarResult
import io.github.yesidrobayo.shopledger.domain.rule.sale.SaleUnitResult
import io.github.yesidrobayo.shopledger.domain.rule.sale.SaleUnits

/**
 * Una línea de la cotización lista para la venta. [quoted] lleva el precio congelado como precio
 * escrito, la cantidad en su unidad y el descuento en monto. [currentPrice] es lo que cobraría hoy
 * esa unidad, o `null` si ya no se puede calcular.
 */
data class ConvertibleLine(val quoted: SaleLineRequest, val currentPrice: Money?) {
    /** El precio congelado en la cotización. */
    val quotedPrice: Money get() = requireNotNull(quoted.unitPrice) { "Sin precio cotizado" }

    /** Si el precio de hoy es otro que el cotizado. */
    val priceChanged: Boolean get() = currentPrice != quotedPrice

    /**
     * La misma línea con el precio de hoy: sin precio escrito, la venta toma el vigente. Sin precio
     * de hoy, conserva el cotizado.
     */
    fun atCurrentPrice(): SaleLineRequest =
        if (currentPrice == null) quoted else quoted.copy(unitPrice = null)
}

/**
 * Lo que necesita la venta para convertir la cotización [quoteId]: sus líneas, el cliente si sigue
 * vigente, con su lista de precios, y los avisos.
 *
 * - [customerId] es nulo si la cotización no tenía cliente, o si se archivó o se anonimizó:
 *   [customerDropped] lo dice, y la venta sale sin cliente.
 * - [archivedLines] cuenta las líneas de productos archivados, que quedan por fuera.
 * - [notSoldLines] cuenta las de productos que hoy son insumos, que también quedan por fuera: la
 *   cotización guardada no cambia, pero la venta no puede llevarlos.
 * - [expiredOn] es la vigencia si ya pasó: se puede convertir igual, tras avisarlo.
 */
data class QuoteConversion(
    val quoteId: Long,
    val customerId: Long?,
    val priceListId: Long?,
    val customerDropped: Boolean,
    val lines: List<ConvertibleLine>,
    val archivedLines: Int,
    val expiredOn: BusinessDate?,
    val notSoldLines: Int = 0,
) {
    /** Cuántas líneas cambiaron de precio desde que se cotizó. */
    val changedPrices: Int get() = lines.count { it.priceChanged }

    /** Las líneas para la venta, con el precio cotizado o con el de hoy. */
    fun requests(useCurrentPrices: Boolean): List<SaleLineRequest> =
        lines.map { if (useCurrentPrices) it.atCurrentPrice() else it.quoted }
}

/** Resultado de [PrepareQuoteConversionUseCase]. */
sealed interface QuoteConversionResult {
    data class Ready(val conversion: QuoteConversion) : QuoteConversionResult

    /** La cotización no existe. */
    data object NotFound : QuoteConversionResult

    /** Ya se convirtió o se canceló: una cotización se convierte una sola vez. */
    data class NotOpen(val status: QuoteStatus) : QuoteConversionResult

    /** Todas sus líneas son de productos archivados o de insumos: no queda nada que vender. */
    data object NothingToConvert : QuoteConversionResult

    /** Este teléfono no conoce la zona del negocio: no hay cómo saber si venció. */
    data class UnknownTimeZone(val storedZoneId: String) : QuoteConversionResult
}

/**
 * Prepara la conversión de una cotización en venta, sin escribir nada. La venta se registra después
 * con su `quoteId`, y es esa transacción la que la marca convertida: si la persona sale sin vender,
 * la cotización sigue abierta.
 *
 * El precio de hoy se calcula con la lista del cliente, o con la inicial sin cliente, en la unidad
 * de cada línea. La cotización no guarda con qué lista se armó: si se eligió otra a mano, el aviso
 * puede salir aunque nadie cambiara un precio, y la persona elige con qué precio vende.
 */
class PrepareQuoteConversionUseCase(
    private val transactions: TransactionRunner,
    private val settings: AppSettingsRepository,
    private val quotes: QuoteRepository,
    private val products: ProductRepository,
    private val customers: CustomerRepository,
    private val clock: Clock,
    private val productUnits: ProductUnitRepository? = null,
    private val priceLists: PriceListRepository? = null,
    private val productPrices: ProductPriceRepository? = null,
) {
    suspend operator fun invoke(quoteId: Long): QuoteConversionResult =
        transactions.read { prepare(quoteId) }

    private suspend fun prepare(quoteId: Long): QuoteConversionResult {
        val quote = quotes.findById(quoteId) ?: return QuoteConversionResult.NotFound
        if (quote.status != QuoteStatus.OPEN) return QuoteConversionResult.NotOpen(quote.status)
        val calendar = when (val result = BusinessCalendar.of(settings.get().regional)) {
            is BusinessCalendarResult.Ready -> result.calendar

            is BusinessCalendarResult.UnknownZone ->
                return QuoteConversionResult.UnknownTimeZone(result.storedZoneId)
        }
        val today = calendar.stamp(clock.now()).businessDate
        val customer = quote.customerId?.let { customers.findById(it) }
            ?.takeUnless { it.isArchived || it.isAnonymized }
        val lines = quotes.findLines(quoteId)
        val byId = products.findByIds(lines.map { it.content.productId }.toSet())
            .associateBy { it.id }
        val list = priceListOf(customer?.priceListId, lines)
        val active = lines.filter { byId[it.content.productId]?.isArchived == false }
        val kept = active.filter { byId.getValue(it.content.productId).details.usage.isSold }
        if (kept.isEmpty()) return QuoteConversionResult.NothingToConvert
        val convertible = kept.map { line ->
            val product = requireNotNull(byId[line.content.productId])
            ConvertibleLine(DocumentLines.requestOf(line), currentPriceOf(line, product, list))
        }
        return QuoteConversionResult.Ready(
            QuoteConversion(
                quoteId = quote.id,
                customerId = customer?.id,
                priceListId = list?.listId,
                customerDropped = quote.customerId != null && customer == null,
                lines = convertible,
                archivedLines = lines.size - active.size,
                expiredOn = quote.validUntil?.takeIf { it < today },
                notSoldLines = active.size - kept.size,
            ),
        )
    }

    /** Lo que cobraría hoy una unidad entera de la unidad de [line]. */
    private suspend fun currentPriceOf(
        line: DocumentLine,
        product: Product,
        list: SalePriceList?,
    ): Money? {
        val unitId = line.content.unitId
        val conversions = if (unitId == product.details.baseUnitId) {
            emptyMap()
        } else {
            productUnits?.findByProductAndUnit(product.id, unitId)
                ?.let { mapOf(unitId to it.details) }
                .orEmpty()
        }
        val request = SaleLineRequest(product.id, line.content.enteredQuantity, unitId = unitId)
        val linePrice = list?.priceOf(product) ?: LinePriceList.default(product)
        return when (val unit = SaleUnits.of(request, product, conversions, linePrice)) {
            is SaleUnitResult.Resolved -> unit.unit.listPrice
            SaleUnitResult.Unavailable -> null
        }
    }

    /** La lista del cliente, si tiene una vigente; nula es la inicial. */
    private suspend fun priceListOf(wanted: Long?, lines: List<DocumentLine>): SalePriceList? {
        val id = wanted ?: return null
        val lists = priceLists ?: return null
        val own = productPrices ?: return null
        val all = lists.findAll().associateBy { it.id }
        val chosen = all[id]?.takeUnless { it.isArchived || it.isDefault } ?: return null
        val ownPrices = lines.map { it.content.productId }.toSet().associateWith {
            own.findByProduct(it)
        }
        return SalePriceList(chosen.id, all, ownPrices)
    }
}

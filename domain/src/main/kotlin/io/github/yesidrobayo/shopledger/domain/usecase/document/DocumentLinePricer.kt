package io.github.yesidrobayo.shopledger.domain.usecase.document

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.CatalogLink
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.SystemRow
import io.github.yesidrobayo.shopledger.domain.model.document.DocumentTotals
import io.github.yesidrobayo.shopledger.domain.model.document.NewDocumentLine
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxSettings
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.port.PriceListRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductPriceRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductUnitRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.SystemRowRepository
import io.github.yesidrobayo.shopledger.domain.port.TaxRateRepository
import io.github.yesidrobayo.shopledger.domain.port.UnitRepository
import io.github.yesidrobayo.shopledger.domain.rule.TaxCalculator
import io.github.yesidrobayo.shopledger.domain.rule.UnitQuantityCheck
import io.github.yesidrobayo.shopledger.domain.rule.UnitQuantityRule
import io.github.yesidrobayo.shopledger.domain.rule.pricing.LinePriceList
import io.github.yesidrobayo.shopledger.domain.rule.pricing.SalePriceList
import io.github.yesidrobayo.shopledger.domain.rule.sale.SaleLineRules
import io.github.yesidrobayo.shopledger.domain.rule.sale.SaleUnitResult
import io.github.yesidrobayo.shopledger.domain.rule.sale.SaleUnits

/** Por qué una línea de una cotización o de un pedido no se puede guardar. */
enum class DocumentLineRejection {
    /** El producto no existe. */
    PRODUCT_NOT_FOUND,

    /** El producto está archivado: no se ofrece en documentos nuevos. */
    PRODUCT_ARCHIVED,

    /** El producto tiene variantes: no se ofrece directo, se elige una de sus variantes. */
    PRODUCT_HAS_VARIANTS,

    /** El producto no se vende en la unidad de la línea. */
    UNIT_UNAVAILABLE,

    /** La cantidad no es positiva o pasa del tope de una línea. */
    INVALID_QUANTITY,

    /** La unidad se cuenta entera, y la cantidad trae decimales. */
    FRACTION_NOT_ALLOWED,

    /** El precio es negativo, pasa del tope o no se puede calcular. */
    INVALID_PRICE,

    /** El descuento es negativo, pasa del bruto de la línea o de 100 %. */
    INVALID_DISCOUNT,

    /** El bruto o el total de la línea pasan del tope de un documento. */
    AMOUNT_TOO_LARGE,

    /**
     * La línea es un extra de otra. Una cotización o un pedido no guardan a qué línea va un extra,
     * así que no lo aceptan: se agrega como producto aparte, o se vende directo.
     */
    EXTRA_NOT_SUPPORTED,

    /**
     * La línea trae un lote elegido. Un documento no saca stock: el lote se elige al vender o al
     * entregar, y si no, sale el que vence antes.
     */
    LOT_NOT_SUPPORTED,

    /** El producto es un insumo: no se vende, así que tampoco se cotiza ni se pide. */
    PRODUCT_NOT_SOLD,
}

/** Cómo resultó calcular las líneas de un documento. */
internal sealed interface DocumentPricing {
    /** Las líneas listas para guardar, en orden, con lo que suman. */
    data class Lines(val lines: List<NewDocumentLine>, val totals: DocumentTotals) : DocumentPricing

    /** La línea [index], contada desde cero, no se puede guardar, por [reason]. */
    data class Rejected(val index: Int, val reason: DocumentLineRejection) : DocumentPricing

    /** Lo que suman las líneas pasa del tope de un documento. */
    data object TotalTooLarge : DocumentPricing

    /** La lista de precios elegida ya no existe o se archivó: se elige otra. */
    data object PriceListUnavailable : DocumentPricing
}

/**
 * Calcula las líneas de una cotización o de un pedido igual que la venta calcula las suyas, para
 * que el precio que se ofrece sea el que se cobraría hoy: la unidad y su factor, el precio de la
 * lista elegida o el escrito a mano, el descuento y el impuesto del producto con el modo del
 * negocio. Lee todo dentro de la transacción de quien lo llama.
 *
 * Un documento no mueve stock ni lleva costo, así que aquí no se lee ningún saldo. Un combo se
 * cotiza por su precio, como un producto más: sus componentes salen al venderlo. Un producto con
 * lotes también se acepta, sin lote: el lote se elige al vender. Lo que el documento no puede
 * guardar se rechaza con su motivo: un extra, que perdería su línea principal, y un lote elegido.
 */
internal class DocumentLinePricer(
    private val products: ProductRepository,
    private val units: UnitRepository,
    private val taxRates: TaxRateRepository,
    private val systemRows: SystemRowRepository,
    private val productUnits: ProductUnitRepository?,
    private val priceLists: PriceListRepository?,
    private val productPrices: ProductPriceRepository?,
) {
    /** Calcula [requests] con la lista [priceListId], nula para la inicial, y [taxes]. */
    suspend fun price(
        requests: List<SaleLineRequest>,
        priceListId: Long?,
        taxes: TaxSettings,
    ): DocumentPricing {
        // Lo que se rechaza sin leer nada: la forma de cada línea.
        for ((index, request) in requests.withIndex()) {
            shapeRejection(request)?.let { return DocumentPricing.Rejected(index, it) }
        }
        val list = when (val chosen = priceListOf(priceListId, requests)) {
            ListChoice.Unavailable -> return DocumentPricing.PriceListUnavailable
            is ListChoice.Ready -> chosen.prices
        }
        val byId = products.findByIds(requests.map { it.productId }.toSet()).associateBy { it.id }
        val fractionByUnit = HashMap<Long, Boolean>()
        val taxByRate = HashMap<Long, Pair<BasisPoints, String?>>()
        val lines = ArrayList<NewDocumentLine>(requests.size)
        var net = Money.ZERO
        var tax = Money.ZERO
        var total = Money.ZERO
        for ((index, request) in requests.withIndex()) {
            val product = byId[request.productId]
                ?: return DocumentPricing.Rejected(index, DocumentLineRejection.PRODUCT_NOT_FOUND)
            val line = when (
                val result = priceLine(request, product, list, taxes, fractionByUnit, taxByRate)
            ) {
                is LineResult.Ready -> result.line
                is LineResult.Rejected -> return DocumentPricing.Rejected(index, result.reason)
            }
            net += line.netAmount
            tax += line.taxAmount
            total += line.lineTotal
            // Cada línea cabe en el tope, así que la suma cabe en Long antes de compararla.
            if (!Limit.DOCUMENT_TOTAL.allows(total.minor)) return DocumentPricing.TotalTooLarge
            lines += line
        }
        return DocumentPricing.Lines(lines, DocumentTotals(net, tax, total))
    }

    private fun shapeRejection(request: SaleLineRequest): DocumentLineRejection? = when {
        request.parentIndex != null -> DocumentLineRejection.EXTRA_NOT_SUPPORTED
        request.lotId != null -> DocumentLineRejection.LOT_NOT_SUPPORTED
        else -> null
    }

    private suspend fun priceLine(
        request: SaleLineRequest,
        product: Product,
        list: SalePriceList?,
        taxes: TaxSettings,
        fractionByUnit: MutableMap<Long, Boolean>,
        taxByRate: MutableMap<Long, Pair<BasisPoints, String?>>,
    ): LineResult {
        if (product.isArchived) return rejected(DocumentLineRejection.PRODUCT_ARCHIVED)
        if (!product.details.usage.isSold) return rejected(DocumentLineRejection.PRODUCT_NOT_SOLD)
        // El producto principal de unas variantes no tiene stock propio: se ofrece una de ellas.
        if (CatalogLink.VARIANT_PARENT in products.catalogLinks(product.id)) {
            return rejected(DocumentLineRejection.PRODUCT_HAS_VARIANTS)
        }
        val unitId = request.unitId ?: product.details.baseUnitId
        val conversions = if (unitId == product.details.baseUnitId) {
            emptyMap()
        } else {
            productUnits?.findByProductAndUnit(product.id, unitId)
                ?.let { mapOf(unitId to it.details) }
                .orEmpty()
        }
        val linePrice = list?.priceOf(product) ?: LinePriceList.default(product)
        val unit = when (val result = SaleUnits.of(request, product, conversions, linePrice)) {
            is SaleUnitResult.Resolved -> result.unit
            SaleUnitResult.Unavailable -> return rejected(DocumentLineRejection.UNIT_UNAVAILABLE)
        }
        val allowsFraction = fractionByUnit.getOrPut(unit.unitId) {
            // La unidad de un producto o de su conversión siempre existe: su llave no deja borrarla.
            (units.findById(unit.unitId) ?: damaged()).allowsFraction
        }
        if (!Limit.LINE_QUANTITY_MILLI.allows(request.quantity.milli)) {
            return rejected(DocumentLineRejection.INVALID_QUANTITY)
        }
        when (UnitQuantityRule.check(request.quantity, allowsFraction)) {
            UnitQuantityCheck.Valid -> Unit

            UnitQuantityCheck.NotPositive -> return rejected(DocumentLineRejection.INVALID_QUANTITY)

            UnitQuantityCheck.FractionNotAllowed ->
                return rejected(DocumentLineRejection.FRACTION_NOT_ALLOWED)
        }
        val baseQuantity = SaleUnits.baseQuantity(request.quantity, unit)
            ?: return rejected(DocumentLineRejection.INVALID_QUANTITY)
        val unitPrice = request.unitPrice ?: unit.listPrice
            ?: return rejected(DocumentLineRejection.INVALID_PRICE)
        if (unitPrice < Money.ZERO || !Limit.UNIT_MONEY.allows(unitPrice.minor)) {
            return rejected(DocumentLineRejection.INVALID_PRICE)
        }
        val gross = SaleLineRules.gross(request.quantity, unitPrice).valueOrNull()
            ?: return rejected(DocumentLineRejection.AMOUNT_TOO_LARGE)
        val discount = SaleLineRules.discountOf(gross, request.discount)
            ?: return rejected(DocumentLineRejection.INVALID_DISCOUNT)
        val (rate, taxName) = appliedTax(product, taxes, taxByRate)
        val split = TaxCalculator.line(gross - discount, taxes.mode, rate).valueOrNull()
            ?: return rejected(DocumentLineRejection.AMOUNT_TOO_LARGE)
        return LineResult.Ready(
            NewDocumentLine(
                productId = product.id,
                unitId = unit.unitId,
                unitFactor = QuantityMilli(unit.factorMilli),
                enteredQuantity = request.quantity,
                quantity = baseQuantity,
                unitPrice = unitPrice,
                lineDiscount = discount,
                netAmount = split.netAmount,
                taxRate = rate,
                taxNameSnapshot = taxName,
                taxAmount = split.taxAmount,
                lineTotal = split.lineTotal,
                productNameSnapshot = product.details.name,
            ),
        )
    }

    /**
     * La tasa del producto, o la del negocio si no tiene, con su nombre, leída una vez por tasa. Sin
     * impuesto en el negocio, o sin tasa, la línea no lleva impuesto ni nombre.
     */
    private suspend fun appliedTax(
        product: Product,
        taxes: TaxSettings,
        cache: MutableMap<Long, Pair<BasisPoints, String?>>,
    ): Pair<BasisPoints, String?> {
        if (taxes.mode == TaxMode.NONE) return BasisPoints.ZERO to null
        val rateId = product.details.taxRateId ?: taxes.defaultTaxRateId
            ?: return BasisPoints.ZERO to null
        return cache.getOrPut(rateId) {
            // Su llave deja sin tasa al producto y al negocio si se elimina: nunca falta.
            val rate = taxRates.findById(rateId) ?: damaged()
            rate.details.rate to rate.details.name.take(MAX_TAX_NAME_LENGTH)
        }
    }

    /**
     * La lista de precios con que se calculan las líneas: la inicial no necesita leer nada más. Una
     * lista elegida que ya no existe o se archivó no se usa.
     */
    private suspend fun priceListOf(wanted: Long?, requests: List<SaleLineRequest>): ListChoice {
        val defaultId = systemRows.findId(SystemRow.DEFAULT_PRICE_LIST) ?: damaged()
        if (wanted == null || wanted == defaultId) return ListChoice.Ready(prices = null)
        val lists = priceLists ?: return ListChoice.Unavailable
        val own = productPrices ?: return ListChoice.Unavailable
        val all = lists.findAll().associateBy { it.id }
        if (all[wanted]?.isArchived != false) return ListChoice.Unavailable
        val ownPrices = requests.map { it.productId }.toSet().associateWith {
            own.findByProduct(it)
        }
        return ListChoice.Ready(SalePriceList(wanted, all, ownPrices))
    }

    private fun rejected(reason: DocumentLineRejection): LineResult = LineResult.Rejected(reason)

    private sealed interface ListChoice {
        class Ready(val prices: SalePriceList?) : ListChoice

        data object Unavailable : ListChoice
    }

    private sealed interface LineResult {
        class Ready(val line: NewDocumentLine) : LineResult

        class Rejected(val reason: DocumentLineRejection) : LineResult
    }

    private companion object {
        /** El largo que guarda el nombre de una tasa en la línea. */
        const val MAX_TAX_NAME_LENGTH = 40
    }
}

/** Una fila que la base siempre tiene y no está: la base está dañada. */
internal fun damaged(): Nothing = throw StorageFailureException(StorageFailure.INVARIANT_VIOLATION)

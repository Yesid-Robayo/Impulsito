package io.github.yesidrobayo.shopledger.ui.sale

import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleCatalog
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ProductSearch
import io.github.yesidrobayo.shopledger.domain.rule.pricing.SalePriceList
import io.github.yesidrobayo.shopledger.domain.rule.sale.CartPreview
import io.github.yesidrobayo.shopledger.domain.rule.sale.SaleCart
import io.github.yesidrobayo.shopledger.domain.rule.sale.SaleUnitResult
import io.github.yesidrobayo.shopledger.domain.rule.sale.SaleUnits
import io.github.yesidrobayo.shopledger.domain.rule.stock.LowStockEvaluator

/**
 * Arma lo que muestra la venta a partir del catálogo, de la venta en edición y de lo que se busca.
 * Es puro: no lee nada, así que se prueba sin base, y se calcula fuera del hilo principal.
 */
internal object SaleContentBuilder {
    /**
     * Las tarjetas: los más vendidos sin búsqueda, o lo que coincide con [query] entre todos los
     * productos vigentes, con los archivados que también coinciden.
     */
    fun content(
        catalog: SaleCatalog,
        lines: List<SaleLineRequest>,
        query: String,
        priceList: SalePriceList? = null,
    ): SaleContent {
        if (catalog.products.isEmpty() &&
            catalog.archived.isEmpty()
        ) {
            return SaleContent.EmptyCatalog
        }
        if (query.isBlank()) {
            if (catalog.products.isEmpty()) return SaleContent.EmptyCatalog
            return SaleContent.Products(
                tiles = catalog.frequent.map { tileOf(it, catalog, lines, priceList) },
                searching = false,
            )
        }
        val trimmed = query.trim()
        val archived = ProductSearch.filter(catalog.archived, query)
            .map { ArchivedMatch(it.id, it.details.name) }
        val matches = ProductSearch.filter(catalog.products, query)
        if (matches.isEmpty()) return SaleContent.NoResults(trimmed, archived)
        return SaleContent.Products(
            matches.map { tileOf(it, catalog, lines, priceList) },
            searching = true,
            archived = archived,
            query = trimmed,
        )
    }

    /** La venta con sus cuentas, línea a línea, con lo que necesita cada selector de cantidad. */
    fun cart(catalog: SaleCatalog, draft: SaleDraft, preview: CartPreview): SaleCartState {
        // Un producto archivado mientras estaba en la venta conserva su nombre en la línea.
        val byId = (catalog.archived + catalog.products).associateBy { it.id }
        val lines = preview.lines.map { line ->
            val request = line.request
            val product = byId[request.productId]
            // Un extra se muestra bajo la línea de su producto, que siempre va antes.
            val parent = request.parentIndex?.let { preview.lines[it].request.productId }
            CartLine(
                productId = request.productId,
                // Un producto eliminado mientras estaba en la venta no tiene nombre que mostrar.
                name = product?.details?.name.orEmpty(),
                unit = product?.let { catalog.units[request.unitId ?: it.details.baseUnitId] },
                quantity = request.quantity,
                unitPrice = line.unitPrice,
                discount = line.discount,
                total = line.total,
                issue = line.issue,
                removesLine = SaleCart.decrementRemoves(request.quantity),
                canIncrement = SaleCart.canIncrement(request.quantity),
                parentProductId = parent,
            )
        }
        return SaleCartState(
            lines = lines,
            itemCount = preview.itemCount,
            // Sin impuesto en el negocio no hay línea de impuesto que mostrar.
            tax = preview.tax.takeIf { catalog.taxes.mode != TaxMode.NONE },
            // Lo que se cobra: con el método en efectivo, ya redondeado.
            total = preview.amountDue,
            cashRounding = preview.cashRounding,
            date = draft.date,
            today = catalog.today,
            weekStartDay = catalog.weekStartDay,
            note = draft.note,
            full = draft.lines.size.toLong() >= Limit.DOCUMENT_LINES.max,
            // Con una sola lista no hay nada que elegir: el chip no aparece.
            priceLists = catalog.activePriceLists.takeIf { it.size > 1 }.orEmpty(),
            priceListId = catalog.salePriceList(draft.priceListId)?.listId
                ?: catalog.activePriceLists.firstOrNull { it.isDefault }?.id,
        )
    }

    /** Las unidades que admiten decimales: una línea en ellas cuenta como un producto. */
    fun fractionUnits(catalog: SaleCatalog): Set<Long> =
        catalog.units.values.filter { it.allowsFraction }.map { it.id }.toSet()

    private fun tileOf(
        product: Product,
        catalog: SaleCatalog,
        lines: List<SaleLineRequest>,
        priceList: SalePriceList?,
    ): SaleTile {
        val quantity = SaleCart.quantityOf(lines, product.id)
        val request = SaleCart.mainOf(lines, product.id)
        val line = if (request == null || quantity == QuantityMilli.ZERO) {
            null
        } else {
            // El aviso de stock va en la unidad base, la del libro: 2 cajas de 24 son 48.
            val unit = SaleUnits.of(request, product, catalog.conversions[product.id].orEmpty())
            val main = (unit as? SaleUnitResult.Resolved)?.let {
                SaleUnits.baseQuantity(quantity, it.unit)
            } ?: quantity
            // Lo que el mismo producto sale como extra de otras líneas, en su unidad base.
            val asExtra = lines.filter { it.parentIndex != null && it.productId == product.id }
                .fold(QuantityMilli.ZERO) { sum, extra -> sum + extra.quantity }
            val sold = main + asExtra
            TileLine(
                quantity = quantity,
                unit = request.unitId?.let { catalog.units[it] },
                removesLine = SaleCart.decrementRemoves(quantity),
                canIncrement = SaleCart.canIncrement(quantity),
                oversell = SaleCart.oversell(product, sold)
                    ?: comboOversell(product, catalog, sold),
            )
        }
        return SaleTile(
            productId = product.id,
            name = product.details.name,
            // El precio de la lista elegida, el mismo que tomará la línea.
            price = (if (priceList == null) product.salePrice else priceList.priceOf(product).price)
                ?.takeIf { it > Money.ZERO },
            stock = familyStockOf(product, catalog) ?: stockOf(product, catalog),
            unit = catalog.units[product.details.baseUnitId],
            line = line,
            photoMediaId = product.details.photoMediaId,
        )
    }

    /**
     * El stock de un producto con variantes: la suma de las suyas, porque el principal no tiene
     * stock propio. `null` si no tiene variantes.
     */
    private fun familyStockOf(product: Product, catalog: SaleCatalog): TileStock? {
        val family = VariantChoices.variantsOf(catalog, product.id)
            .filter { it.details.stockMode == ProductStockMode.TRACKED }
        if (VariantChoices.variantsOf(catalog, product.id).isEmpty()) return null
        if (family.isEmpty()) return TileStock.None
        val onHand = QuantityMilli(family.sumOf { it.onHand.milli })
        return when {
            onHand < QuantityMilli.ZERO -> TileStock.Negative(onHand)
            onHand == QuantityMilli.ZERO -> TileStock.Out
            else -> TileStock.Normal(onHand)
        }
    }

    /** Un combo avisa si la venta pide más de los que alcanzan sus componentes. */
    private fun comboOversell(
        product: Product,
        catalog: SaleCatalog,
        sold: QuantityMilli,
    ): QuantityMilli? {
        if (product.details.stockMode != ProductStockMode.COMPONENTS) return null
        return SaleCart.comboOversell(catalog.comboAvailability[product.id], sold)
    }

    private fun stockOf(product: Product, catalog: SaleCatalog): TileStock {
        val details = product.details
        // Un combo no tiene stock propio: muestra cuántos alcanzan con sus componentes.
        if (details.stockMode == ProductStockMode.COMPONENTS) {
            val available = catalog.comboAvailability[product.id] ?: return TileStock.None
            return if (available > 0L) {
                TileStock.Normal(QuantityMilli.ofUnits(available))
            } else {
                TileStock.Out
            }
        }
        if (details.stockMode != ProductStockMode.TRACKED) return TileStock.None
        val onHand = product.onHand
        return when {
            onHand < QuantityMilli.ZERO -> TileStock.Negative(onHand)

            onHand == QuantityMilli.ZERO -> TileStock.Out

            LowStockEvaluator.isLow(details.stockMode, false, onHand, details.minStock) ->
                TileStock.Low(onHand)

            else -> TileStock.Normal(onHand)
        }
    }
}

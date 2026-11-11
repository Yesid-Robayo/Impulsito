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
import io.github.yesidrobayo.shopledger.domain.rule.sale.CartPreview
import io.github.yesidrobayo.shopledger.domain.rule.sale.SaleCart
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
    fun content(catalog: SaleCatalog, lines: List<SaleLineRequest>, query: String): SaleContent {
        if (catalog.products.isEmpty() &&
            catalog.archived.isEmpty()
        ) {
            return SaleContent.EmptyCatalog
        }
        if (query.isBlank()) {
            if (catalog.products.isEmpty()) return SaleContent.EmptyCatalog
            return SaleContent.Products(
                tiles = catalog.frequent.map { tileOf(it, catalog, lines) },
                searching = false,
            )
        }
        val trimmed = query.trim()
        val archived = ProductSearch.filter(catalog.archived, query)
            .map { ArchivedMatch(it.id, it.details.name) }
        val matches = ProductSearch.filter(catalog.products, query)
        if (matches.isEmpty()) return SaleContent.NoResults(trimmed, archived)
        return SaleContent.Products(
            matches.map { tileOf(it, catalog, lines) },
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
            CartLine(
                productId = request.productId,
                // Un producto eliminado mientras estaba en la venta no tiene nombre que mostrar.
                name = product?.details?.name.orEmpty(),
                unit = product?.let { catalog.units[it.details.baseUnitId] },
                quantity = request.quantity,
                unitPrice = line.unitPrice,
                discount = line.discount,
                total = line.total,
                issue = line.issue,
                removesLine = SaleCart.decrementRemoves(request.quantity),
                canIncrement = SaleCart.canIncrement(request.quantity),
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
        )
    }

    /** Las unidades que admiten decimales: una línea en ellas cuenta como un producto. */
    fun fractionUnits(catalog: SaleCatalog): Set<Long> =
        catalog.units.values.filter { it.allowsFraction }.map { it.id }.toSet()

    private fun tileOf(
        product: Product,
        catalog: SaleCatalog,
        lines: List<SaleLineRequest>,
    ): SaleTile {
        val quantity = SaleCart.quantityOf(lines, product.id)
        val line = if (quantity == QuantityMilli.ZERO) {
            null
        } else {
            TileLine(
                quantity = quantity,
                removesLine = SaleCart.decrementRemoves(quantity),
                canIncrement = SaleCart.canIncrement(quantity),
                oversell = SaleCart.oversell(product, quantity),
            )
        }
        return SaleTile(
            productId = product.id,
            name = product.details.name,
            price = product.salePrice.takeIf { it > Money.ZERO },
            stock = stockOf(product),
            unit = catalog.units[product.details.baseUnitId],
            line = line,
        )
    }

    private fun stockOf(product: Product): TileStock {
        val details = product.details
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

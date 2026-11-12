package io.github.yesidrobayo.shopledger.domain.rule.catalog

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkExcludedLine
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceLine
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPricePreview
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceRequest
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceScope
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.rule.MarginCalculator

/**
 * Arma la vista previa de una actualización masiva sobre el catálogo en memoria. Es puro: se
 * calcula fuera del hilo principal en cada cambio del porcentaje o del alcance, y con 5000 productos
 * cabe en el tiempo que pide la vista previa.
 *
 * Un archivado del alcance queda fuera, con su motivo, igual que uno sin precio.
 */
object BulkPricePreviewer {
    fun preview(products: List<Product>, request: BulkPriceRequest): BulkPricePreview {
        val changes = mutableListOf<BulkPriceLine>()
        val excluded = mutableListOf<BulkExcludedLine>()
        var unchanged = 0
        var removed = 0
        for (product in products) {
            if (!inScope(product, request.scope)) continue
            val name = product.details.name
            if (product.id in request.removed) {
                removed++
                continue
            }
            if (product.isArchived) {
                excluded += BulkExcludedLine(product.id, name, BulkExclusion.ARCHIVED)
                continue
            }
            val outcome = BulkPriceRules.outcome(
                product.salePrice,
                catalogCost(product),
                request.percent,
                request.roundingStep,
            )
            when (outcome) {
                is BulkPriceOutcome.Changes -> changes += BulkPriceLine(product.id, name, outcome)

                BulkPriceOutcome.Same -> unchanged++

                is BulkPriceOutcome.Excluded ->
                    excluded += BulkExcludedLine(product.id, name, outcome.reason)
            }
        }
        // Lo que conviene revisar va primero; dentro de cada grupo, el orden del catálogo.
        val ordered = changes.sortedBy { !it.flagged }
        return BulkPricePreview(ordered, excluded, unchanged, removed)
    }

    /** Si [product] entra en [scope]. */
    fun inScope(product: Product, scope: BulkPriceScope): Boolean = when (scope) {
        BulkPriceScope.All -> true
        is BulkPriceScope.Category -> product.details.categoryId == scope.categoryId
        is BulkPriceScope.Supplier -> product.details.defaultSupplierId == scope.supplierId
        is BulkPriceScope.Picked -> product.id in scope.productIds
    }

    /**
     * El costo con que se mide el margen nuevo, o `null` si no se conoce: sin costo, el margen
     * saldría inflado contra cero. Un producto por componentes no tiene costo propio aquí.
     */
    fun catalogCost(product: Product): Money? {
        val details = product.details
        return when (details.stockMode) {
            ProductStockMode.TRACKED ->
                if (product.averageCost > Money.ZERO || product.stockValue > Money.ZERO) {
                    MarginCalculator
                        .catalogUnitCost(product.onHand, product.stockValue, product.averageCost)
                        .valueOrNull() ?: product.averageCost
                } else {
                    null
                }

            ProductStockMode.UNTRACKED -> details.fixedUnitCost.takeIf { it > Money.ZERO }

            ProductStockMode.COMPONENTS -> null
        }
    }
}

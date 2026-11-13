package io.github.yesidrobayo.shopledger.domain.rule.catalog

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkExcludedLine
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceContext
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceLine
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPricePreview
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceRequest
import io.github.yesidrobayo.shopledger.domain.model.catalog.BulkPriceScope
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceListPricing
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.rule.MarginCalculator

/**
 * Dónde escribe una actualización masiva el precio de un producto: el de venta, en la lista
 * inicial, o el propio de una lista fija. [listId] es la lista, o `null` para el de venta cuando no
 * se eligieron listas.
 */
sealed interface BulkTarget {
    val listId: Long?

    data class SalePrice(override val listId: Long?) : BulkTarget

    data class OwnPrice(override val listId: Long) : BulkTarget
}

/**
 * Arma la vista previa de una actualización masiva sobre el catálogo en memoria. Es puro: se
 * calcula fuera del hilo principal en cada cambio del cálculo o del alcance, y con 5000 productos
 * cabe en el tiempo que pide la vista previa.
 *
 * Un archivado del alcance queda fuera, con su motivo, igual que uno sin precio. Con varias listas,
 * cada producto da una fila por lista que cambia.
 */
object BulkPricePreviewer {
    fun preview(
        products: List<Product>,
        request: BulkPriceRequest,
        context: BulkPriceContext = BulkPriceContext(),
    ): BulkPricePreview {
        val targets = targetsOf(request, context)
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
            for (target in targets) {
                val subject = subjectOf(product, target, context)
                val outcome =
                    BulkPriceRules.outcomeOf(
                        subject,
                        request.adjustment,
                        request.roundingStep,
                        context.scale,
                    )
                when (outcome) {
                    is BulkPriceOutcome.Changes ->
                        changes += BulkPriceLine(product.id, name, outcome, target.listId)

                    BulkPriceOutcome.Same -> unchanged++

                    is BulkPriceOutcome.Excluded ->
                        excluded += BulkExcludedLine(product.id, name, outcome.reason)
                }
            }
        }
        // Lo que conviene revisar va primero; dentro de cada grupo, el orden del catálogo.
        val ordered = changes.sortedBy { !it.flagged }
        return BulkPricePreview(ordered, excluded, unchanged, removed)
    }

    /**
     * Dónde escribe [request]: sin listas, el precio de venta. Con listas, la inicial escribe el de
     * venta y una fija el propio; una relativa o una que no existe no se escribe.
     */
    fun targetsOf(request: BulkPriceRequest, context: BulkPriceContext): List<BulkTarget> {
        if (request.priceListIds.isEmpty()) return listOf(BulkTarget.SalePrice(null))
        return request.priceListIds.sorted().mapNotNull { id ->
            val list = context.lists[id]
            when {
                list == null || list.pricing is PriceListPricing.Relative -> null
                list.isDefault -> BulkTarget.SalePrice(id)
                else -> BulkTarget.OwnPrice(id)
            }
        }
    }

    /**
     * De qué parte [product] en [target]: el precio de ahora en esa lista, el costo del margen, la
     * base y el impuesto del margen objetivo, y su precio de referencia.
     */
    fun subjectOf(product: Product, target: BulkTarget, context: BulkPriceContext): BulkSubject {
        val price =
            when (target) {
                is BulkTarget.SalePrice -> product.salePrice

                is BulkTarget.OwnPrice -> context.ownPrices[product.id]?.get(target.listId)
                    ?: product.salePrice
            }
        val subject = BulkSubject(
            price = price,
            cost = catalogCost(product),
            referencePrice = product.details.referencePrice,
        )
        // Sin el contexto de precios, el margen objetivo parte de cero, sin impuesto dentro.
        val pricingContext = context.pricing ?: return subject
        val pricing = ProductPricing.of(product, pricingContext)
        val included = if (pricing.taxMode ==
            TaxMode.INCLUDED
        ) {
            pricing.taxRate
        } else {
            BasisPoints.ZERO
        }
        return subject.copy(suggestionBase = pricing.suggestionBase, includedTaxRate = included)
    }

    /** Si [product] entra en [scope]. */
    fun inScope(product: Product, scope: BulkPriceScope): Boolean = when (scope) {
        BulkPriceScope.All -> true
        is BulkPriceScope.Category -> product.details.categoryId == scope.categoryId
        is BulkPriceScope.Supplier -> product.details.defaultSupplierId == scope.supplierId
        is BulkPriceScope.Tag -> product.id in scope.productIds
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

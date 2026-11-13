package io.github.yesidrobayo.shopledger.domain.rule.pricing

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Margin
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.catalog.PricingContext
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceReview
import io.github.yesidrobayo.shopledger.domain.model.pricing.PriceReviewReason
import io.github.yesidrobayo.shopledger.domain.model.pricing.StaleCostCheck
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ProductPricing
import kotlin.time.Instant

/**
 * La lista `Revisar`: lo que pide una decisión de precio, sin notificar nada.
 *
 * Reúne tres motivos. `Costo nuevo sin revisar` es la marca que deja una compra que subió el
 * costo. `Margen bajo` compara el margen de su detalle, sin impuesto y contra el costo de la
 * ganancia, con el objetivo heredado: el del producto, el de su categoría o el del negocio. `Costo
 * viejo` mira la fecha del costo de reposición, o la de creación si nunca tuvo uno, y solo en
 * productos con stock propio y algún costo: un servicio no se compra y su costo no tiene fecha.
 */
object PriceReviewRules {
    /**
     * Si [margin] queda por debajo de [target]. Sin margen medido, por falta de precio o de costo,
     * no avisa: no hay con qué comparar.
     */
    fun isLowMargin(margin: Margin, target: BasisPoints): Boolean =
        margin is Margin.Defined && margin.value < target

    /**
     * Si un costo de [since] es viejo [today]: tiene más de [days] días. Con cero no se evalúa.
     * Con 90 días, un costo del 1 de agosto ya es viejo el 1 de noviembre, con 92.
     */
    fun isStale(since: BusinessDate, today: BusinessDate, days: Int): Boolean =
        days > 0 && since.plusDays(days) < today

    /**
     * El instante del costo con que se juzga si es viejo, o `null` si no se juzga: sin stock
     * propio, o sin ningún costo conocido, que no tiene nada que envejecer.
     */
    fun costInstantOf(product: Product): Instant? {
        if (product.details.stockMode != ProductStockMode.TRACKED) return null
        val known = product.replacementCost > Money.ZERO ||
            product.averageCost > Money.ZERO ||
            product.stockValue > Money.ZERO
        if (!known) return null
        return product.replacementCostAt ?: product.createdAt
    }

    /**
     * Lo que dice `Revisar` de [product], o `null` si no tiene ningún motivo. Sin [check], porque
     * no se conoce la zona del negocio, el costo viejo no se evalúa.
     */
    fun reviewOf(product: Product, context: PricingContext, check: StaleCostCheck?): PriceReview? {
        val pricing = ProductPricing.of(product, context)
        val margin = pricing.marginOf(product.salePrice)
        val oldSince = check?.let { stale ->
            costInstantOf(product)
                ?.let { BusinessDate.of(it, stale.zone) }
                ?.takeIf { isStale(it, stale.today, stale.days) }
        }
        val reasons = buildList {
            if (product.priceReviewSince != null) add(PriceReviewReason.NEW_COST)
            if (isLowMargin(margin, pricing.targetMargin)) add(PriceReviewReason.LOW_MARGIN)
            if (oldSince != null) add(PriceReviewReason.OLD_COST)
        }
        if (reasons.isEmpty()) return null
        return PriceReview(product, reasons, margin, pricing.targetMargin, oldSince)
    }

    /**
     * La lista de los [products] vigentes que tienen algún motivo. Primero los de costo nuevo, el
     * más antiguo primero, como su cola; después los demás, del margen más bajo al más alto, y al
     * final los que no tienen margen medido. A igualdad, por id.
     */
    fun reviewsOf(
        products: List<Product>,
        context: PricingContext,
        check: StaleCostCheck?,
    ): List<PriceReview> = products
        .filterNot { it.isArchived }
        .mapNotNull { reviewOf(it, context, check) }
        .sortedWith(ORDER)

    private val ORDER: Comparator<PriceReview> =
        compareBy<PriceReview> { it.product.priceReviewSince == null }
            .thenBy { it.product.priceReviewSince }
            .thenBy { review ->
                review.margin.let { if (it is Margin.Defined) it.value.bp else Long.MAX_VALUE }
            }
            .thenBy { it.product.id }
}

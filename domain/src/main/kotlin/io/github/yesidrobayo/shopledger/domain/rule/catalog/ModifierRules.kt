package io.github.yesidrobayo.shopledger.domain.rule.catalog

import io.github.yesidrobayo.shopledger.domain.model.catalog.ModifierIssue
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product

/**
 * Qué productos pueden ser extras de otro. Un extra se vende como una línea normal, así que debe
 * poder venderse solo: vigente, sin variantes y que se venda, no un insumo. Un producto no es
 * extra de sí mismo, y cada extra aparece una vez.
 */
object ModifierRules {
    /**
     * Cuántos extras ofrece como mucho un producto. Son chips en la hoja de la línea: más que esto
     * ya no se eligen de un vistazo.
     */
    const val MAX_MODIFIERS = 30

    /**
     * El primer problema de dar [wanted] como extras de [productId], o `null` si se pueden guardar.
     * [products] son los productos de [wanted] que existen, por id, y [withVariants] los que
     * tienen variantes.
     */
    fun check(
        productId: Long,
        wanted: List<Long>,
        products: Map<Long, Product>,
        withVariants: Set<Long>,
    ): ModifierIssue? {
        if (wanted.size > MAX_MODIFIERS) return ModifierIssue.TOO_MANY
        if (wanted.toSet().size != wanted.size) return ModifierIssue.DUPLICATE
        for (id in wanted) {
            when {
                id == productId -> return ModifierIssue.SAME_PRODUCT
                !offered(products[id]) -> return ModifierIssue.UNAVAILABLE
                id in withVariants -> return ModifierIssue.HAS_VARIANTS
            }
        }
        return null
    }

    /**
     * Si [candidate] se puede ofrecer como extra de [productId]: vigente, otro producto, sin
     * variantes y que se venda. [withVariants] son los productos que tienen variantes.
     */
    fun canOffer(productId: Long, candidate: Product, withVariants: Set<Long>): Boolean =
        candidate.id != productId && offered(candidate) && candidate.id !in withVariants

    /** Si [product] existe, está vigente y se vende: un insumo no se ofrece como extra. */
    private fun offered(product: Product?): Boolean =
        product != null && !product.isArchived && product.details.usage.isSold
}

package io.github.yesidrobayo.shopledger.domain.rule.catalog

import io.github.yesidrobayo.shopledger.domain.model.catalog.ModifierIssue
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product

/**
 * Qué productos pueden ser extras de otro. Un extra se vende como una línea normal, así que debe
 * poder venderse solo: vigente y sin variantes. Un producto no es extra de sí mismo, y cada extra
 * aparece una vez.
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
            val product = products[id]
            when {
                id == productId -> return ModifierIssue.SAME_PRODUCT
                product == null || product.isArchived -> return ModifierIssue.UNAVAILABLE
                id in withVariants -> return ModifierIssue.HAS_VARIANTS
            }
        }
        return null
    }

    /**
     * Si [candidate] se puede ofrecer como extra de [productId]: vigente, otro producto y sin
     * variantes. [withVariants] son los productos que tienen variantes.
     */
    fun canOffer(productId: Long, candidate: Product, withVariants: Set<Long>): Boolean =
        candidate.id != productId && !candidate.isArchived && candidate.id !in withVariants
}

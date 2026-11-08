package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.rule.catalog.CatalogName
import io.github.yesidrobayo.shopledger.domain.rule.catalog.CatalogText

/**
 * Dice, al salir del campo del nombre, si otro producto sin archivar ya lo usa, comparado sin
 * mayúsculas ni tildes: así el formulario lo marca antes de guardar. Guardar lo vuelve a comprobar
 * dentro de su transacción, porque entre tanto otro pudo tomarlo. No escribe nada.
 */
class FindProductNameClashUseCase(
    private val transactions: TransactionRunner,
    private val products: ProductRepository,
) {
    /**
     * El nombre, tal como se guardó, del producto sin archivar que ya usa [name], o `null` si
     * ninguno lo usa o [name] no es un nombre válido. [editingId] es el producto que se edita, que
     * no choca consigo mismo.
     */
    suspend operator fun invoke(name: String, editingId: Long?): String? {
        val valid = CatalogText.name(name, CatalogText.PRODUCT_NAME_MAX) as? CatalogName.Valid
            ?: return null
        return transactions.read {
            products.findActiveByNormalizedName(valid.normalizedName)
                ?.takeIf { it.id != editingId }
                ?.details
                ?.name
        }
    }
}

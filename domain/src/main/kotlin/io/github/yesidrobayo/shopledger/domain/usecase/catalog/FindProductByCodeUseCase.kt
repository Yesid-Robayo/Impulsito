package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.catalog.CodeMatch
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductUnitRepository
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.rule.catalog.BarcodeKeys
import kotlinx.coroutines.flow.first

/**
 * Busca el producto de un código leído con el escáner o escrito por un lector externo. No escribe
 * nada.
 *
 * Busca primero en el código de los productos sin archivar y luego en el de sus conversiones, y en
 * ese caso la línea usa la unidad de la conversión. Una conversión de un producto archivado, o un
 * producto archivado con ese código, se informa como archivado: no se vende hasta reactivarlo.
 * Cada código se busca también en su otra forma de UPC-A, con o sin el cero delante.
 */
class FindProductByCodeUseCase(
    private val transactions: TransactionRunner,
    private val products: ProductRepository,
    private val conversions: ProductUnitRepository,
) {
    suspend operator fun invoke(code: String): CodeMatch {
        val keys = BarcodeKeys.forLookup(code)
        if (keys.isEmpty()) return CodeMatch.Unknown(code.trim())
        val found =
            transactions.read {
                keys.firstNotNullOfOrNull { products.findActiveByBarcode(it) }
                    ?.let { return@read CodeMatch.Found(it, conversion = null) }
                val conversion =
                    keys.firstNotNullOfOrNull { conversions.findByBarcode(it) } ?: return@read null
                val owner = products.findById(conversion.productId) ?: return@read null
                if (owner.isArchived) {
                    CodeMatch.Archived(owner)
                } else {
                    CodeMatch.Found(owner, conversion)
                }
            }
        if (found != null) return found
        // Solo sin coincidencia activa: los archivados se leen enteros, y casi nunca hace falta.
        val archived =
            products.observe(ArchiveFilter.ARCHIVED).first()
                .firstOrNull { it.details.barcode in keys }
        return archived?.let { CodeMatch.Archived(it) } ?: CodeMatch.Unknown(keys.first())
    }
}

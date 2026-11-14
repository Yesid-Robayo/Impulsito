package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductUnitRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.catalog.OwnBarcode

/** Un código propio que no se guardó porque otro producto, [existingName], ya lo usa. */
data class OwnBarcodeClash(val productId: Long, val code: String, val existingName: String)

/** Cómo termina generar códigos propios. */
sealed interface GenerateOwnBarcodesResult {
    /**
     * Se guardaron los códigos de [generated], por id de producto. Los de [clashes] chocaron con
     * otro y se rechazaron. [skipped] ya tenían código, están archivados o no existen: no se
     * tocan.
     */
    data class Done(
        val generated: Map<Long, String>,
        val clashes: List<OwnBarcodeClash>,
        val skipped: List<Long>,
    ) : GenerateOwnBarcodesResult

    data class StorageFailed(val failure: StorageFailure) : GenerateOwnBarcodesResult
}

/**
 * Genera el código propio de los productos que no tienen uno, todos en una sola transacción.
 *
 * Cada código es `P` y el id con 8 cifras. Antes de guardarlo se busca entre los productos sin
 * archivar y las conversiones: si otro ya lo usa, ese producto se rechaza y los demás siguen. Un
 * producto con código, archivado o inexistente no se toca. Solo cambia el código.
 */
class GenerateOwnBarcodesUseCase(
    private val transactions: TransactionRunner,
    private val products: ProductRepository,
    private val conversions: ProductUnitRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(productIds: Collection<Long>): GenerateOwnBarcodesResult =
        storageSafe(GenerateOwnBarcodesResult::StorageFailed) {
            transactions.write { generate(productIds.distinct()) }
        }

    private suspend fun generate(ids: List<Long>): TxResult<GenerateOwnBarcodesResult> {
        val now = clock.now()
        val generated = linkedMapOf<Long, String>()
        val clashes = mutableListOf<OwnBarcodeClash>()
        val skipped = mutableListOf<Long>()
        val found = products.findByIds(ids).sortedBy { it.id }
        skipped += ids.filter { id -> found.none { it.id == id } }
        for (product in found) {
            if (product.isArchived || product.details.barcode != null) {
                skipped += product.id
                continue
            }
            val code = OwnBarcode.forProduct(product.id)
            val owner = holderOf(code)
            if (owner != null) {
                clashes += OwnBarcodeClash(product.id, code, owner)
                continue
            }
            products.updateDetails(product.id, product.details.copy(barcode = code), now)
            generated[product.id] = code
        }
        val result = GenerateOwnBarcodesResult.Done(generated, clashes, skipped.sorted())
        // Sin nada guardado no hay cambio que respaldar.
        return if (generated.isEmpty()) TxResult.Rollback(result) else TxResult.Commit(result)
    }

    /** El nombre del producto que ya usa [code], en él o en una de sus conversiones. */
    private suspend fun holderOf(code: String): String? {
        products.findActiveByBarcode(code)?.let { return it.details.name }
        val conversion = conversions.findByBarcode(code) ?: return null
        return products.findById(conversion.productId)?.details?.name.orEmpty()
    }
}

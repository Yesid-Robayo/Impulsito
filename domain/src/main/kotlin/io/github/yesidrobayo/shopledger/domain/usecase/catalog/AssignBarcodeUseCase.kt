package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductUnitRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.catalog.CatalogText

/** Cómo termina dar un código a un producto. Salvo [Assigned], no se escribió nada. */
sealed interface AssignBarcodeResult {
    /** El producto quedó con el código [barcode]. */
    data class Assigned(val barcode: String) : AssignBarcodeResult

    data object NotFound : AssignBarcodeResult

    /** El producto está archivado: no se ofrece hasta reactivarlo. */
    data object Archived : AssignBarcodeResult

    /** El producto ya tiene un código, que nunca se cambia sin que la persona lo edite. */
    data object AlreadyHasCode : AssignBarcodeResult

    /** El código está en blanco o pasa del largo máximo. */
    data object InvalidCode : AssignBarcodeResult

    /** Otro producto sin archivar, o una de sus conversiones, ya usa el código: [existingName]. */
    data class Taken(val existingName: String) : AssignBarcodeResult

    data class StorageFailed(val failure: StorageFailure) : AssignBarcodeResult
}

/**
 * Da un código a un producto que no tiene: el que leyó el escáner y nadie tenía, o uno propio que
 * genera la app.
 *
 * El código es único entre los productos sin archivar y las conversiones, así que antes de
 * escribir se busca en los dos, dentro de la misma transacción: un choque se rechaza y dice con
 * qué producto. Nunca reemplaza un código que el producto ya tiene. Solo cambia el código: el
 * precio, el costo y el stock quedan como estaban.
 */
class AssignBarcodeUseCase(
    private val transactions: TransactionRunner,
    private val products: ProductRepository,
    private val conversions: ProductUnitRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(productId: Long, code: String): AssignBarcodeResult =
        storageSafe(AssignBarcodeResult::StorageFailed) {
            transactions.write { assign(productId, code) }
        }

    private suspend fun assign(productId: Long, code: String): TxResult<AssignBarcodeResult> {
        val barcode = CatalogText.barcode(code)
        if (barcode == null || CatalogText.length(barcode) > CatalogText.BARCODE_MAX) {
            return TxResult.Rollback(AssignBarcodeResult.InvalidCode)
        }
        val product =
            products.findById(productId) ?: return TxResult.Rollback(AssignBarcodeResult.NotFound)
        if (product.isArchived) return TxResult.Rollback(AssignBarcodeResult.Archived)
        if (product.details.barcode != null) {
            return TxResult.Rollback(AssignBarcodeResult.AlreadyHasCode)
        }
        products.findActiveByBarcode(barcode)?.let {
            return TxResult.Rollback(AssignBarcodeResult.Taken(it.details.name))
        }
        conversions.findByBarcode(barcode)?.let { conversion ->
            val owner = products.findById(conversion.productId)?.details?.name.orEmpty()
            return TxResult.Rollback(AssignBarcodeResult.Taken(owner))
        }
        products.updateDetails(productId, product.details.copy(barcode = barcode), clock.now())
        return TxResult.Commit(AssignBarcodeResult.Assigned(barcode))
    }
}

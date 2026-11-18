package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.ArchiveFilter
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDraft
import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionEntry
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductConversion
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.ProductRepository
import io.github.yesidrobayo.shopledger.domain.port.ProductUnitRepository
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.port.UnitRepository
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ConversionCheck
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ConversionRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Las conversiones de los productos vigentes, con su producto, ordenadas por el nombre del producto
 * y, dentro de él, en el orden en que se crearon. Las de un producto archivado no se muestran: no se
 * vende ni se compra.
 */
class ObserveConversionsUseCase(
    private val conversions: ProductUnitRepository,
    private val products: ProductRepository,
) {
    operator fun invoke(): Flow<List<ConversionEntry>> =
        combine(conversions.observeAll(), products.observe(ArchiveFilter.ACTIVE)) { all, active ->
            val byId = active.associateBy { it.id }
            all.mapNotNull { conversion ->
                byId[conversion.productId]?.let { ConversionEntry(conversion, it) }
            }.sortedWith(
                compareBy<ConversionEntry> { it.product.details.normalizedName }
                    .thenBy { it.product.id }
                    .thenBy { it.conversion.id },
            )
        }
}

/**
 * Crea una conversión de un producto vigente, o edita una existente, en una sola transacción.
 *
 * Al editar, el producto y la unidad no cambian: se cambian el factor, el precio, el código y dónde
 * se ofrece. Cambiar el factor solo afecta a documentos nuevos, porque cada línea copia el suyo. Un
 * código no se repite entre conversiones ni con el de otro producto vigente, como en la guarda de la
 * tabla. Una edición que no cambia nada no escribe nada ni cuenta un cambio sin respaldar.
 */
class SaveConversionUseCase(
    private val transactions: TransactionRunner,
    private val products: ProductRepository,
    private val units: UnitRepository,
    private val conversions: ProductUnitRepository,
    private val clock: Clock,
) {
    /** Crea la conversión de [draft] si [editingId] es nulo, o edita esa conversión. */
    suspend operator fun invoke(editingId: Long?, draft: ConversionDraft): SaveConversionResult =
        storageSafe(SaveConversionResult::StorageFailed) {
            transactions.write { save(editingId, draft) }
        }

    /**
     * Crea la conversión de [draft] dentro de una transacción que ya abrió quien llama, como la
     * medida que se dijo en una tarjeta de `Decir`, que se guarda con la operación.
     */
    internal suspend fun saveWithin(draft: ConversionDraft): TxResult<SaveConversionResult> =
        save(null, draft)

    private suspend fun save(
        editingId: Long?,
        draft: ConversionDraft,
    ): TxResult<SaveConversionResult> {
        val current = editingId?.let {
            conversions.findById(it) ?: return reject(SaveConversionResult.NotFound)
        }
        // Al editar, valen el producto y la unidad guardados, no los que traiga el borrador.
        val request =
            if (current == null) {
                draft
            } else {
                draft.copy(productId = current.productId, unitId = current.details.unitId)
            }
        val product = products.findById(request.productId)
        if (product == null || product.isArchived) {
            return reject(SaveConversionResult.ProductUnavailable)
        }
        if (current == null) {
            val unit = units.findById(request.unitId)
            if (unit == null || unit.isArchived) return reject(SaveConversionResult.UnitUnavailable)
        }
        val details =
            when (val check = ConversionRules.check(request, product.details.baseUnitId)) {
                is ConversionCheck.Invalid -> return reject(
                    SaveConversionResult.Invalid(check.errors),
                )

                is ConversionCheck.Valid -> check.details
            }
        if (current == null &&
            conversions.findByProductAndUnit(product.id, details.unitId) != null
        ) {
            return reject(SaveConversionResult.UnitTaken)
        }
        barcodeOwner(details, editingId, product.id)?.let {
            return reject(SaveConversionResult.DuplicateBarcode(it))
        }
        if (current == null) {
            val id = conversions.insert(product.id, details, clock.now())
            return TxResult.Commit(SaveConversionResult.Saved(id))
        }
        if (current.details == details) {
            return TxResult.Rollback(SaveConversionResult.Saved(current.id))
        }
        conversions.update(current.id, details, clock.now())
        return TxResult.Commit(SaveConversionResult.Saved(current.id))
    }

    /**
     * El nombre del producto que ya usa el código de [details], o `null` si nadie más lo usa. El
     * código del propio producto no cuenta, como en la guarda de la tabla.
     */
    private suspend fun barcodeOwner(
        details: ConversionDetails,
        editingId: Long?,
        productId: Long,
    ): String? {
        val barcode = details.barcode ?: return null
        val conversion = conversions.findByBarcode(barcode)?.takeIf { it.id != editingId }
        if (conversion != null) {
            return products.findById(conversion.productId)?.details?.name.orEmpty()
        }
        return products.findActiveByBarcode(barcode)?.takeIf { it.id != productId }?.details?.name
    }

    private fun reject(result: SaveConversionResult): TxResult<SaveConversionResult> =
        TxResult.Rollback(result)
}

/** Quita una conversión. Las líneas que la usaron no cambian: cada una guarda su factor. */
class DeleteConversionUseCase(
    private val transactions: TransactionRunner,
    private val conversions: ProductUnitRepository,
) {
    suspend operator fun invoke(id: Long): DeleteConversionResult =
        storageSafe(DeleteConversionResult::StorageFailed) {
            transactions.write { delete(id) }
        }

    private suspend fun delete(id: Long): TxResult<DeleteConversionResult> {
        conversions.findById(id) ?: return TxResult.Rollback(DeleteConversionResult.NotFound)
        conversions.delete(id)
        return TxResult.Commit(DeleteConversionResult.Deleted)
    }
}

/** Las conversiones de un producto, en el orden en que se crearon. */
class ObserveProductConversionsUseCase(private val conversions: ProductUnitRepository) {
    operator fun invoke(productId: Long): Flow<List<ProductConversion>> =
        conversions.observeByProduct(productId)
}

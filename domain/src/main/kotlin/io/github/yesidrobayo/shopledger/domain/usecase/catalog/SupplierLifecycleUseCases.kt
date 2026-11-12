package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.SupplierRepository
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult

/** Cómo termina archivar, reactivar o eliminar un proveedor. Salvo [Done], no se escribió nada. */
sealed interface SupplierLifecycleResult {
    /** Se hizo lo pedido. */
    data object Done : SupplierLifecycleResult

    /** El proveedor ya no existe. */
    data object NotFound : SupplierLifecycleResult

    /** Ya estaba como se pedía: archivado al archivar, o vigente al reactivar. Nada cambió. */
    data object Unchanged : SupplierLifecycleResult

    /**
     * Tiene compras, devoluciones o filas en su libro, aunque estén anuladas. Ninguna se borra, así
     * que el proveedor tampoco: solo se archiva.
     */
    data object HasHistory : SupplierLifecycleResult

    /**
     * Otro proveedor sin archivar ya usa su nombre. Hay que renombrar uno de los dos antes de
     * reactivarlo: [existingName] es el del otro.
     */
    data class NameTaken(val existingName: String) : SupplierLifecycleResult

    data class StorageFailed(val failure: StorageFailure) : SupplierLifecycleResult
}

/**
 * Archiva un proveedor: deja de ofrecerse en compras nuevas y como proveedor habitual, pero sus
 * compras siguen ligadas a él y se puede reactivar. Es lo que se hace con uno que ya tiene historia.
 *
 * Los productos que lo tienen como habitual lo conservan: lo muestran marcado como archivado, y una
 * compra nueva no lo preselecciona.
 */
class ArchiveSupplierUseCase(
    private val transactions: TransactionRunner,
    private val suppliers: SupplierRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(supplierId: Long): SupplierLifecycleResult =
        storageSafe(SupplierLifecycleResult::StorageFailed) {
            transactions.write { archive(supplierId) }
        }

    private suspend fun archive(supplierId: Long): TxResult<SupplierLifecycleResult> {
        val supplier = suppliers.findById(supplierId)
            ?: return TxResult.Rollback(SupplierLifecycleResult.NotFound)
        if (supplier.isArchived) return TxResult.Rollback(SupplierLifecycleResult.Unchanged)
        val now = clock.now()
        suppliers.setArchivedAt(supplierId, now, now)
        return TxResult.Commit(SupplierLifecycleResult.Done)
    }
}

/**
 * Reactiva un proveedor archivado: vuelve a ofrecerse en compras nuevas.
 *
 * Un archivado no reserva su nombre, así que mientras estuvo archivado otro pudo tomarlo: entonces
 * se rechaza y se pide renombrar uno de los dos.
 */
class ReactivateSupplierUseCase(
    private val transactions: TransactionRunner,
    private val suppliers: SupplierRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(supplierId: Long): SupplierLifecycleResult =
        storageSafe(SupplierLifecycleResult::StorageFailed) {
            transactions.write { reactivate(supplierId) }
        }

    private suspend fun reactivate(supplierId: Long): TxResult<SupplierLifecycleResult> {
        val supplier = suppliers.findById(supplierId)
            ?: return TxResult.Rollback(SupplierLifecycleResult.NotFound)
        if (!supplier.isArchived) return TxResult.Rollback(SupplierLifecycleResult.Unchanged)
        suppliers.findActiveByNormalizedName(supplier.details.normalizedName)?.let {
            return TxResult.Rollback(SupplierLifecycleResult.NameTaken(it.details.name))
        }
        suppliers.setArchivedAt(supplierId, null, clock.now())
        return TxResult.Commit(SupplierLifecycleResult.Done)
    }
}

/**
 * Elimina un proveedor creado por error, sin historia. Los productos que lo tenían como habitual
 * quedan sin proveedor, en la misma transacción.
 *
 * Antes de intentarlo dice por qué no se puede, si no se puede: con historia solo se archiva. La
 * base lo rechazaría igual, pero sin decir el motivo.
 */
class DeleteSupplierUseCase(
    private val transactions: TransactionRunner,
    private val suppliers: SupplierRepository,
) {
    suspend operator fun invoke(supplierId: Long): SupplierLifecycleResult =
        storageSafe(SupplierLifecycleResult::StorageFailed) {
            transactions.write { delete(supplierId) }
        }

    private suspend fun delete(supplierId: Long): TxResult<SupplierLifecycleResult> {
        if (suppliers.findById(supplierId) == null) {
            return TxResult.Rollback(SupplierLifecycleResult.NotFound)
        }
        if (suppliers.hasHistory(supplierId)) {
            return TxResult.Rollback(SupplierLifecycleResult.HasHistory)
        }
        suppliers.delete(supplierId)
        return TxResult.Commit(SupplierLifecycleResult.Done)
    }
}

package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.catalog.MeasureUnit
import io.github.yesidrobayo.shopledger.domain.model.catalog.SystemRow
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.port.UnitCatalogRepository
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer

/** Si es la unidad de conteo inicial, que nunca se archiva ni se elimina. */
internal val MeasureUnit.isProtected: Boolean
    get() = systemKey == SystemRow.DEFAULT_UNIT.systemKey

/**
 * Archiva una unidad: deja de ofrecerse en productos y conversiones nuevos, y se puede reactivar.
 * Los productos y las líneas que la usan la conservan, con su factor. La de conteo inicial, base
 * de todo producto que no dice otra, no se archiva.
 */
class ArchiveUnitUseCase(
    private val transactions: TransactionRunner,
    private val units: UnitCatalogRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(id: Long): UnitLifecycleResult =
        storageSafe(UnitLifecycleResult::StorageFailed) { transactions.write { archive(id) } }

    private suspend fun archive(id: Long): TxResult<UnitLifecycleResult> {
        val unit = units.findById(id) ?: return TxResult.Rollback(UnitLifecycleResult.NotFound)
        val refused =
            when {
                unit.isProtected -> UnitLifecycleResult.Protected
                unit.isArchived -> UnitLifecycleResult.Unchanged
                else -> null
            }
        if (refused != null) return TxResult.Rollback(refused)
        val now = clock.now()
        units.setArchivedAt(id, archivedAt = now, updatedAt = now)
        return TxResult.Commit(UnitLifecycleResult.Done)
    }
}

/**
 * Vuelve a ofrecer una unidad archivada. Una archivada no reserva su nombre: si otra vigente lo tomó
 * mientras tanto, no se reactiva hasta que una de las dos cambie de nombre. Una inicial sin nombre
 * propio no choca con nadie.
 */
class ReactivateUnitUseCase(
    private val transactions: TransactionRunner,
    private val units: UnitCatalogRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(id: Long): UnitLifecycleResult =
        storageSafe(UnitLifecycleResult::StorageFailed) { transactions.write { reactivate(id) } }

    private suspend fun reactivate(id: Long): TxResult<UnitLifecycleResult> {
        val unit = units.findById(id) ?: return TxResult.Rollback(UnitLifecycleResult.NotFound)
        val name = unit.name
        val refused =
            when {
                !unit.isArchived -> UnitLifecycleResult.Unchanged

                name != null &&
                    units.findActiveByNormalizedName(TextNormalizer.normalize(name)) != null ->
                    UnitLifecycleResult.NameTaken

                else -> null
            }
        if (refused != null) return TxResult.Rollback(refused)
        units.setArchivedAt(id, archivedAt = null, updatedAt = clock.now())
        return TxResult.Commit(UnitLifecycleResult.Done)
    }
}

/**
 * Elimina una unidad sin uso, como una creada por error. Con uso no se elimina: se archiva, para que
 * los productos y las líneas que la usan la sigan explicando. La de conteo inicial no se elimina.
 */
class DeleteUnitUseCase(
    private val transactions: TransactionRunner,
    private val units: UnitCatalogRepository,
) {
    suspend operator fun invoke(id: Long): UnitLifecycleResult =
        storageSafe(UnitLifecycleResult::StorageFailed) { transactions.write { delete(id) } }

    private suspend fun delete(id: Long): TxResult<UnitLifecycleResult> {
        val unit = units.findById(id) ?: return TxResult.Rollback(UnitLifecycleResult.NotFound)
        val refused =
            when {
                unit.isProtected -> UnitLifecycleResult.Protected
                units.isInUse(id) -> UnitLifecycleResult.InUse
                else -> null
            }
        if (refused != null) return TxResult.Rollback(refused)
        units.delete(id)
        return TxResult.Commit(UnitLifecycleResult.Done)
    }
}

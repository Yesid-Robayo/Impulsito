package io.github.yesidrobayo.shopledger.domain.usecase.pricing

import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.PriceListRepository
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.storageSafe

/**
 * Archiva una lista: deja de ofrecerse en una venta y para un cliente, y se puede reactivar. Las
 * ventas que la usaron la conservan, y una relativa que parte de ella sigue calculándose. La inicial
 * no se archiva.
 */
class ArchivePriceListUseCase(
    private val transactions: TransactionRunner,
    private val priceLists: PriceListRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(id: Long): PriceListLifecycleResult =
        storageSafe(PriceListLifecycleResult::StorageFailed) {
            transactions.write { archive(id) }
        }

    private suspend fun archive(id: Long): TxResult<PriceListLifecycleResult> {
        val list = priceLists.findById(id)
            ?: return TxResult.Rollback(PriceListLifecycleResult.NotFound)
        val refused =
            when {
                list.isDefault -> PriceListLifecycleResult.Protected
                list.isArchived -> PriceListLifecycleResult.Unchanged
                else -> null
            }
        if (refused != null) return TxResult.Rollback(refused)
        val now = clock.now()
        priceLists.setArchivedAt(id, archivedAt = now, updatedAt = now)
        return TxResult.Commit(PriceListLifecycleResult.Done)
    }
}

/**
 * Vuelve a ofrecer una lista archivada. Una archivada no reserva su nombre: si otra vigente lo tomó
 * mientras tanto, no se reactiva hasta que una de las dos cambie de nombre.
 */
class ReactivatePriceListUseCase(
    private val transactions: TransactionRunner,
    private val priceLists: PriceListRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(id: Long): PriceListLifecycleResult =
        storageSafe(PriceListLifecycleResult::StorageFailed) {
            transactions.write { reactivate(id) }
        }

    private suspend fun reactivate(id: Long): TxResult<PriceListLifecycleResult> {
        val list = priceLists.findById(id)
            ?: return TxResult.Rollback(PriceListLifecycleResult.NotFound)
        val name = list.name
        val refused =
            when {
                !list.isArchived -> PriceListLifecycleResult.Unchanged

                name != null &&
                    priceLists.findActiveByNormalizedName(TextNormalizer.normalize(name)) != null ->
                    PriceListLifecycleResult.NameTaken

                else -> null
            }
        if (refused != null) return TxResult.Rollback(refused)
        priceLists.setArchivedAt(id, archivedAt = null, updatedAt = clock.now())
        return TxResult.Commit(PriceListLifecycleResult.Done)
    }
}

/**
 * Elimina una lista sin uso, como una creada por error. Con uso no se elimina: se archiva, para que
 * las ventas, los clientes y el historial que la nombran la sigan explicando. La inicial no se
 * elimina.
 */
class DeletePriceListUseCase(
    private val transactions: TransactionRunner,
    private val priceLists: PriceListRepository,
) {
    suspend operator fun invoke(id: Long): PriceListLifecycleResult =
        storageSafe(PriceListLifecycleResult::StorageFailed) {
            transactions.write { delete(id) }
        }

    private suspend fun delete(id: Long): TxResult<PriceListLifecycleResult> {
        val list = priceLists.findById(id)
            ?: return TxResult.Rollback(PriceListLifecycleResult.NotFound)
        val refused =
            when {
                list.isDefault -> PriceListLifecycleResult.Protected
                priceLists.isInUse(id) -> PriceListLifecycleResult.InUse
                else -> null
            }
        if (refused != null) return TxResult.Rollback(refused)
        priceLists.delete(id)
        return TxResult.Commit(PriceListLifecycleResult.Done)
    }
}

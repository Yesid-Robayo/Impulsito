package io.github.yesidrobayo.shopledger.domain.usecase.money

import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.MoneyAccountRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.storageSafe

/** Cómo termina archivar o reactivar una cuenta de dinero. Salvo [Done], no se escribió nada. */
sealed interface MoneyAccountLifecycleResult {
    /** Se hizo lo pedido. */
    data object Done : MoneyAccountLifecycleResult

    /** La cuenta ya no existe. */
    data object NotFound : MoneyAccountLifecycleResult

    /** Ya estaba como se pedía: archivada al archivar, o vigente al reactivar. */
    data object Unchanged : MoneyAccountLifecycleResult

    /** Otra cuenta vigente ya usa su nombre: hay que renombrar una de las dos antes de reactivarla. */
    data object NameTaken : MoneyAccountLifecycleResult

    /** La base no pudo escribir, por [failure]. No quedó nada guardado. */
    data class StorageFailed(val failure: StorageFailure) : MoneyAccountLifecycleResult
}

/**
 * Archiva una cuenta de dinero: deja de ofrecerse para registrar dinero, pero su libro sigue entero y
 * se puede reactivar. Una cuenta nunca se borra si tiene movimientos.
 *
 * Archivar con saldo se permite: la pantalla sugiere transferirlo antes, pero la persona decide. El
 * saldo no cambia por archivar.
 */
class ArchiveMoneyAccountUseCase(
    private val transactions: TransactionRunner,
    private val accounts: MoneyAccountRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(accountId: Long): MoneyAccountLifecycleResult =
        storageSafe(MoneyAccountLifecycleResult::StorageFailed) {
            transactions.write {
                val account = accounts.findById(accountId)
                when {
                    account == null -> TxResult.Rollback(MoneyAccountLifecycleResult.NotFound)

                    account.isArchived -> TxResult.Rollback(MoneyAccountLifecycleResult.Unchanged)

                    else -> {
                        val now = clock.now()
                        accounts.setArchivedAt(accountId, now, now)
                        TxResult.Commit(MoneyAccountLifecycleResult.Done)
                    }
                }
            }
        }
}

/**
 * Reactiva una cuenta archivada. Una archivada no reserva su nombre, así que mientras lo estuvo otra
 * pudo tomarlo: entonces se rechaza y se pide renombrar una de las dos.
 */
class ReactivateMoneyAccountUseCase(
    private val transactions: TransactionRunner,
    private val accounts: MoneyAccountRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(accountId: Long): MoneyAccountLifecycleResult =
        storageSafe(MoneyAccountLifecycleResult::StorageFailed) {
            transactions.write {
                val account = accounts.findById(accountId)
                val clash = account?.details?.normalizedName
                    ?.let { accounts.findActiveByNormalizedName(it) }
                when {
                    account == null -> TxResult.Rollback(MoneyAccountLifecycleResult.NotFound)

                    !account.isArchived -> TxResult.Rollback(MoneyAccountLifecycleResult.Unchanged)

                    clash != null -> TxResult.Rollback(MoneyAccountLifecycleResult.NameTaken)

                    else -> {
                        accounts.setArchivedAt(accountId, null, clock.now())
                        TxResult.Commit(MoneyAccountLifecycleResult.Done)
                    }
                }
            }
        }
}

package io.github.yesidrobayo.shopledger.domain.usecase.customer

import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.CustomerRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.storageSafe

/** Cómo termina archivar, reactivar o eliminar un cliente. Salvo [Done], no se escribió nada. */
sealed interface CustomerLifecycleResult {
    /** Se hizo lo pedido. */
    data object Done : CustomerLifecycleResult

    /** El cliente ya no existe. */
    data object NotFound : CustomerLifecycleResult

    /** Ya estaba como se pedía: archivado al archivar, o vigente al reactivar. Nada cambió. */
    data object Unchanged : CustomerLifecycleResult

    /**
     * Tiene ventas, pedidos, cotizaciones o filas en sus libros, aunque estén anuladas. Ninguna se
     * borra, así que el cliente tampoco: solo se archiva.
     */
    data object HasHistory : CustomerLifecycleResult

    data class StorageFailed(val failure: StorageFailure) : CustomerLifecycleResult
}

/**
 * Archiva un cliente: deja de ofrecerse al vender, pero sus ventas y sus libros siguen ligados a él,
 * su saldo sigue contando en lo que hay por cobrar y se puede reactivar. Es lo que se hace con uno
 * que ya tiene historia.
 */
class ArchiveCustomerUseCase(
    private val transactions: TransactionRunner,
    private val customers: CustomerRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(customerId: Long): CustomerLifecycleResult =
        storageSafe(CustomerLifecycleResult::StorageFailed) {
            transactions.write { archive(customerId) }
        }

    private suspend fun archive(customerId: Long): TxResult<CustomerLifecycleResult> {
        val customer = customers.findById(customerId)
            ?: return TxResult.Rollback(CustomerLifecycleResult.NotFound)
        if (customer.isArchived) return TxResult.Rollback(CustomerLifecycleResult.Unchanged)
        val now = clock.now()
        customers.setArchivedAt(customerId, now, now)
        return TxResult.Commit(CustomerLifecycleResult.Done)
    }
}

/**
 * Reactiva un cliente archivado: vuelve a ofrecerse al vender. Como el nombre no es único, nunca
 * choca con otro cliente.
 */
class ReactivateCustomerUseCase(
    private val transactions: TransactionRunner,
    private val customers: CustomerRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(customerId: Long): CustomerLifecycleResult =
        storageSafe(CustomerLifecycleResult::StorageFailed) {
            transactions.write { reactivate(customerId) }
        }

    private suspend fun reactivate(customerId: Long): TxResult<CustomerLifecycleResult> {
        val customer = customers.findById(customerId)
            ?: return TxResult.Rollback(CustomerLifecycleResult.NotFound)
        if (!customer.isArchived) return TxResult.Rollback(CustomerLifecycleResult.Unchanged)
        customers.setArchivedAt(customerId, null, clock.now())
        return TxResult.Commit(CustomerLifecycleResult.Done)
    }
}

/**
 * Elimina un cliente creado por error, sin historia. Una venta en espera que lo tenía queda sin
 * cliente, en la misma sentencia.
 *
 * Antes de intentarlo dice por qué no se puede, si no se puede: con historia solo se archiva. La
 * base lo rechazaría igual, pero sin decir el motivo. Nada se borra solo: siempre lo pide la persona.
 */
class DeleteCustomerUseCase(
    private val transactions: TransactionRunner,
    private val customers: CustomerRepository,
) {
    suspend operator fun invoke(customerId: Long): CustomerLifecycleResult =
        storageSafe(CustomerLifecycleResult::StorageFailed) {
            transactions.write { delete(customerId) }
        }

    private suspend fun delete(customerId: Long): TxResult<CustomerLifecycleResult> {
        if (customers.findById(customerId) == null) {
            return TxResult.Rollback(CustomerLifecycleResult.NotFound)
        }
        if (customers.hasHistory(customerId)) {
            return TxResult.Rollback(CustomerLifecycleResult.HasHistory)
        }
        customers.delete(customerId)
        return TxResult.Commit(CustomerLifecycleResult.Done)
    }
}

package io.github.yesidrobayo.shopledger.domain.usecase.customer

import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.CustomerRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.usecase.catalog.storageSafe

/** Cómo termina anonimizar un cliente. Salvo [Done], no se escribió nada. */
sealed interface AnonymizeCustomerResult {
    /** Sus datos personales se borraron. No se deshace. */
    data object Done : AnonymizeCustomerResult

    /** El cliente ya no existe. */
    data object NotFound : AnonymizeCustomerResult

    /** Ya estaba anonimizado: nada cambió, ni el instante en que se anonimizó. */
    data object AlreadyAnonymized : AnonymizeCustomerResult

    data class StorageFailed(val failure: StorageFailure) : AnonymizeCustomerResult
}

/**
 * Borra los datos personales de un cliente cuando la persona lo pide: nombre, teléfono, correo y
 * notas, y la dirección y la nota de entrega de sus pedidos, en una sola transacción. Queda como
 * cliente anónimo, y no se deshace.
 *
 * No toca sus libros: sus filas, sus montos y sus notas siguen intactos, y sus saldos no cambian,
 * porque un libro nunca se edita. Sus ventas siguen ligadas a él. Por eso un cliente con saldo se
 * puede anonimizar, y su deuda sigue contando: la pantalla lo avisa antes con `Customer.hasBalance`. Nada
 * de esto ocurre solo: siempre lo pide la persona, y ningún trabajo programado anonimiza ni borra.
 */
class AnonymizeCustomerUseCase(
    private val transactions: TransactionRunner,
    private val customers: CustomerRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(customerId: Long): AnonymizeCustomerResult =
        storageSafe(AnonymizeCustomerResult::StorageFailed) {
            transactions.write { anonymize(customerId) }
        }

    private suspend fun anonymize(customerId: Long): TxResult<AnonymizeCustomerResult> {
        val customer = customers.findById(customerId)
            ?: return TxResult.Rollback(AnonymizeCustomerResult.NotFound)
        if (customer.isAnonymized) {
            return TxResult.Rollback(AnonymizeCustomerResult.AlreadyAnonymized)
        }
        customers.anonymize(customerId, clock.now())
        return TxResult.Commit(AnonymizeCustomerResult.Done)
    }
}

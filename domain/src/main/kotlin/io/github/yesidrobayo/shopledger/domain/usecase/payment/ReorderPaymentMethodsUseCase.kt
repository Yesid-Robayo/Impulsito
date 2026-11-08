package io.github.yesidrobayo.shopledger.domain.usecase.payment

import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.PaymentMethodRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult

/**
 * Guarda el orden en que se ofrecen los métodos vigentes al cobrar, el mismo de los chips de la
 * venta.
 *
 * Recibe todos los vigentes, de arriba abajo, y les da los lugares 1, 2, 3 y siguientes. Solo
 * escribe los que cambian de lugar, todos en una transacción: o queda el orden entero, o ninguno.
 * Los archivados conservan el suyo.
 */
class ReorderPaymentMethodsUseCase(
    private val transactions: TransactionRunner,
    private val methods: PaymentMethodRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(orderedIds: List<Long>): ReorderPaymentMethodsResult = try {
        transactions.write { reorder(orderedIds) }
    } catch (failure: StorageFailureException) {
        ReorderPaymentMethodsResult.StorageFailed(failure.failure)
    }

    private suspend fun reorder(orderedIds: List<Long>): TxResult<ReorderPaymentMethodsResult> {
        val current = methods.findActive().associate { it.id to it.sortOrder }
        // Mismo tamaño y mismos ids: así un id repetido o uno que falta no pasan.
        if (orderedIds.size != current.size || orderedIds.toSet() != current.keys) {
            return TxResult.Rollback(ReorderPaymentMethodsResult.ListChanged)
        }
        val now = clock.now()
        var moved = false
        orderedIds.forEachIndexed { index, id ->
            val place = index + 1L
            if (current[id] != place) {
                methods.setSortOrder(id, place, now)
                moved = true
            }
        }
        return if (moved) {
            TxResult.Commit(ReorderPaymentMethodsResult.Reordered)
        } else {
            TxResult.Rollback(ReorderPaymentMethodsResult.Unchanged)
        }
    }
}

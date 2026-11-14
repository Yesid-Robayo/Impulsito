package io.github.yesidrobayo.shopledger.domain.usecase.document

import io.github.yesidrobayo.shopledger.domain.model.document.OrderStatus
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.OrderRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult

/** Resultado de cambiar el estado de un pedido sin entregarlo. */
sealed interface OrderChangeResult {
    data object Changed : OrderChangeResult

    /** Ya no existe, o ya se entregó o canceló: nada cambió. */
    data object NotOpen : OrderChangeResult

    data class StorageFailed(val failure: StorageFailure) : OrderChangeResult
}

/**
 * Marca un pedido como listo, o lo devuelve a pendiente. Los dos siguen reservando: solo cambia en
 * qué va la preparación. No toca stock ni dinero.
 */
class SetOrderPreparationUseCase(
    private val transactions: TransactionRunner,
    private val orders: OrderRepository,
    private val clock: Clock,
) {
    /** Lo pasa a listo con [ready], o a pendiente sin él. */
    suspend operator fun invoke(orderId: Long, ready: Boolean): OrderChangeResult = change {
        val status = if (ready) OrderStatus.READY else OrderStatus.PENDING
        orders.setPreparation(orderId, status, clock.now())
    }

    private suspend fun change(write: suspend () -> Boolean): OrderChangeResult = try {
        transactions.write {
            if (write()) {
                TxResult.Commit(OrderChangeResult.Changed)
            } else {
                TxResult.Rollback(OrderChangeResult.NotOpen)
            }
        }
    } catch (failure: StorageFailureException) {
        OrderChangeResult.StorageFailed(failure.failure)
    }
}

/**
 * Cancela un pedido sin entregar. Sus unidades dejan de estar reservadas y queda en el historial.
 * Un anticipo no se toca: es un pago del cliente, y sigue como saldo a favor en su libro, que se
 * puede usar en otra venta o devolverse con un ajuste.
 */
class CancelOrderUseCase(
    private val transactions: TransactionRunner,
    private val orders: OrderRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(orderId: Long): OrderChangeResult = try {
        transactions.write {
            if (orders.markCancelled(orderId, clock.now())) {
                TxResult.Commit(OrderChangeResult.Changed)
            } else {
                TxResult.Rollback(OrderChangeResult.NotOpen)
            }
        }
    } catch (failure: StorageFailureException) {
        OrderChangeResult.StorageFailed(failure.failure)
    }
}

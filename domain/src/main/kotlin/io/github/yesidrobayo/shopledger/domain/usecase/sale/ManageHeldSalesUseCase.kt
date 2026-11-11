package io.github.yesidrobayo.shopledger.domain.usecase.sale

import io.github.yesidrobayo.shopledger.domain.model.sale.HeldSale
import io.github.yesidrobayo.shopledger.domain.port.Clock
import io.github.yesidrobayo.shopledger.domain.port.HeldSaleRepository
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.rule.sale.HeldSaleRules
import kotlinx.coroutines.flow.Flow

/** Las ventas en espera, de la más antigua a la más reciente, al día con cada cambio. */
class ObserveHeldSalesUseCase(private val heldSales: HeldSaleRepository) {
    operator fun invoke(): Flow<List<HeldSale>> = heldSales.observeAll()
}

/** Resultado de renombrar o descartar una venta en espera. */
sealed interface HeldSaleChangeResult {
    data object Changed : HeldSaleChangeResult

    /** Ya no existe: alguien la confirmó o la descartó. */
    data object NotFound : HeldSaleChangeResult

    /** El nombre pasa de 40 caracteres. */
    data object LabelTooLong : HeldSaleChangeResult

    /** La base no pudo escribir, por [failure]. Nada cambió. */
    data class StorageFailed(val failure: StorageFailure) : HeldSaleChangeResult
}

/**
 * Renombra una venta en espera, como al pasar el pedido a otra mesa. En blanco queda sin nombre.
 * Se confirma sin contar como cambio sin respaldar: no es un hecho del negocio.
 */
class RenameHeldSaleUseCase(
    private val transactions: TransactionRunner,
    private val heldSales: HeldSaleRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(heldSaleId: Long, label: String?): HeldSaleChangeResult = try {
        transactions.write {
            val clean = HeldSaleRules.labelOf(label)
            when {
                !HeldSaleRules.fits(clean) -> TxResult.Rollback(HeldSaleChangeResult.LabelTooLong)

                heldSales.rename(heldSaleId, clean, clock.now()) ->
                    TxResult.CommitUncounted(HeldSaleChangeResult.Changed)

                else -> TxResult.Rollback(HeldSaleChangeResult.NotFound)
            }
        }
    } catch (failure: StorageFailureException) {
        HeldSaleChangeResult.StorageFailed(failure.failure)
    }
}

/**
 * Descarta una venta en espera: la borra con sus líneas, nada más. Una espera admite borrado libre
 * porque no es un registro, y se confirma sin contar como cambio sin respaldar.
 */
class DiscardHeldSaleUseCase(
    private val transactions: TransactionRunner,
    private val heldSales: HeldSaleRepository,
) {
    suspend operator fun invoke(heldSaleId: Long): HeldSaleChangeResult = try {
        transactions.write {
            if (heldSales.delete(heldSaleId)) {
                TxResult.CommitUncounted(HeldSaleChangeResult.Changed)
            } else {
                TxResult.Rollback(HeldSaleChangeResult.NotFound)
            }
        }
    } catch (failure: StorageFailureException) {
        HeldSaleChangeResult.StorageFailed(failure.failure)
    }
}

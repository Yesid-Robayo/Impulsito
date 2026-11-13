package io.github.yesidrobayo.shopledger.domain.usecase.returns

import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnLineRequest
import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnSettlement
import io.github.yesidrobayo.shopledger.domain.model.returns.SaleReturnRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.port.StorageFailure
import io.github.yesidrobayo.shopledger.domain.port.StorageFailureException
import io.github.yesidrobayo.shopledger.domain.port.TransactionRunner
import io.github.yesidrobayo.shopledger.domain.port.TxResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleUseCase

/**
 * Lo que la persona pide al cambiar productos de la venta [saleId]: lo que devuelve, en [lines], y
 * la venta nueva, en [replacement]. [refundAccountId] es la cuenta de la que sale el crédito de lo
 * devuelto, si la persona lleva cuentas; la venta nueva lo vuelve a cobrar con sus pagos.
 */
data class ExchangeRequest(
    val saleId: Long,
    val lines: List<ReturnLineRequest>,
    val replacement: SaleRequest,
    val refundAccountId: Long? = null,
    val note: String? = null,
)

/** Resultado de [ExchangeSaleUseCase]. Salvo [Exchanged], no quedó nada guardado. */
sealed interface ExchangeResult {
    /** Quedaron la venta nueva [replacementSaleId] y la devolución [saleReturnId] que la paga. */
    data class Exchanged(val replacementSaleId: Long, val saleReturnId: Long) : ExchangeResult

    /** La venta nueva no se puede registrar, por [rejection]. */
    data class SaleRejected(val rejection: RegisterSaleResult) : ExchangeResult

    /** Lo devuelto no se puede registrar, por [rejection]. */
    data class ReturnRejected(val rejection: RegisterSaleReturnResult) : ExchangeResult

    /** La base no pudo escribir, por [failure]. */
    data class StorageFailed(val failure: StorageFailure) : ExchangeResult
}

/**
 * Cambia productos de una venta por otros, en una sola transacción: la venta nueva y la devolución
 * de cambio que la paga, con la venta nueva en `replacement_sale_id`, o nada.
 *
 * La devolución es la de siempre: cada línea lleva su parte de lo vendido y su costo congelado, lo
 * que vuelve entra al stock y, con [ExchangeRequest.refundAccountId], el crédito sale de esa cuenta
 * con un reembolso. La venta nueva es una venta común, con sus pagos, que cubren todo su total: quien
 * arma el cambio paga con el crédito la parte que alcanza y con otro método la diferencia. Así cada
 * libro cuenta lo suyo, y en la cuenta queda solo la diferencia: lo que se cobró de más o lo que se
 * devolvió.
 *
 * La venta nueva se guarda primero, porque la devolución la nombra y nunca se edita. Si cualquiera de
 * las dos se rechaza, no queda ninguna.
 */
class ExchangeSaleUseCase(
    private val transactions: TransactionRunner,
    private val registerSale: RegisterSaleUseCase,
    private val registerReturn: RegisterSaleReturnUseCase,
) {
    suspend operator fun invoke(request: ExchangeRequest): ExchangeResult = try {
        transactions.write { exchange(request) }
    } catch (failure: StorageFailureException) {
        ExchangeResult.StorageFailed(failure.failure)
    }

    private suspend fun exchange(request: ExchangeRequest): TxResult<ExchangeResult> {
        val saleId = when (val sold = registerSale.registerWithin(request.replacement)) {
            // Una venta solo se confirma registrada: cualquier otro resultado deshace.
            is TxResult.Commit -> (sold.value as? RegisterSaleResult.Registered)?.saleId
                ?: return TxResult.Rollback(ExchangeResult.SaleRejected(sold.value))

            is TxResult.Rollback -> return TxResult.Rollback(
                ExchangeResult.SaleRejected(sold.value),
            )

            // La venta siempre cuenta como un cambio del negocio.
            is TxResult.CommitUncounted -> error("Una venta no se confirma sin contar")
        }
        val returned = registerReturn.registerWithin(
            SaleReturnRequest(
                saleId = request.saleId,
                lines = request.lines,
                settlement = ReturnSettlement.Refund(request.refundAccountId),
                note = request.note,
            ),
            replacementSaleId = saleId,
        )
        return when (returned) {
            is TxResult.Commit -> {
                val result = returned.value
                if (result is RegisterSaleReturnResult.Registered) {
                    TxResult.Commit(ExchangeResult.Exchanged(saleId, result.saleReturnId))
                } else {
                    TxResult.Rollback(ExchangeResult.ReturnRejected(result))
                }
            }

            is TxResult.Rollback -> TxResult.Rollback(ExchangeResult.ReturnRejected(returned.value))

            is TxResult.CommitUncounted -> error("Una devolución no se confirma sin contar")
        }
    }
}

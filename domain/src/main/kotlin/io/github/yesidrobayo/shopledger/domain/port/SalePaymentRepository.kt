package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.sale.NewSalePayment
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePayment
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePaymentMethod
import kotlinx.coroutines.flow.Flow

/**
 * Los pagos de las ventas, y lo que una venta lee del método con que cobra. Un pago solo se agrega:
 * no se edita ni se borra, tampoco al anular su venta.
 *
 * Las lecturas que devuelven [Flow] vuelven a emitir cada vez que se confirma una transacción que
 * cambia lo leído. [insert] solo se llama dentro de [TransactionRunner.write].
 */
interface SalePaymentRepository {
    /**
     * Lo que una venta copia del método [id], archivado o no, o `null` si no existe. Se lee dentro
     * de la transacción de la venta, para congelar el método tal como está al confirmar.
     */
    suspend fun findMethod(id: Long): SalePaymentMethod?

    /** Los pagos de una venta, en el orden en que se guardaron. */
    fun observeBySale(saleId: Long): Flow<List<SalePayment>>

    /** Los pagos de una venta, en el orden en que se guardaron. */
    suspend fun findBySale(saleId: Long): List<SalePayment>

    /** Guarda un pago de la venta [saleId] y devuelve su id. */
    suspend fun insert(saleId: Long, payment: NewSalePayment): Long
}

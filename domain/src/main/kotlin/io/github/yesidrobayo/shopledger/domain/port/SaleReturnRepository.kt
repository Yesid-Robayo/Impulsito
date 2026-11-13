package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.returns.LineAmounts
import io.github.yesidrobayo.shopledger.domain.model.returns.NewSaleReturn
import io.github.yesidrobayo.shopledger.domain.model.returns.NewSaleReturnLine
import io.github.yesidrobayo.shopledger.domain.model.returns.SaleReturn
import io.github.yesidrobayo.shopledger.domain.model.returns.SaleReturnLine
import kotlinx.coroutines.flow.Flow

/**
 * Las devoluciones de venta y sus líneas. Una devolución confirmada no se edita: solo se anula, y
 * sus líneas no cambian nunca.
 *
 * Las lecturas que devuelven [Flow] vuelven a emitir cada vez que se confirma una transacción que
 * cambia lo leído. Las funciones que escriben solo se llaman dentro de [TransactionRunner.write].
 */
interface SaleReturnRepository {
    /** Las devoluciones de la venta, activas y anuladas, en el orden en que se registraron. */
    fun observeBySale(saleId: Long): Flow<List<SaleReturn>>

    /**
     * Lo que ya devolvieron las devoluciones activas de cada línea de la venta, por id de línea.
     * Una línea sin devoluciones activas no aparece.
     */
    fun observeTakenBySale(saleId: Long): Flow<Map<Long, LineAmounts>>

    suspend fun findById(id: Long): SaleReturn?

    /** Las líneas de la devolución, en el orden en que se guardaron. */
    suspend fun findLines(saleReturnId: Long): List<SaleReturnLine>

    /** Lo mismo que [observeTakenBySale], leído una vez dentro de la transacción. */
    suspend fun takenBySale(saleId: Long): Map<Long, LineAmounts>

    /** Guarda la cabecera, activa, y devuelve su id. */
    suspend fun insert(saleReturn: NewSaleReturn): Long

    /** Guarda una línea de la devolución [saleReturnId] y devuelve su id. */
    suspend fun insertLine(saleReturnId: Long, line: NewSaleReturnLine): Long
}

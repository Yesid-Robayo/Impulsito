package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.document.FiscalMarkStatus
import io.github.yesidrobayo.shopledger.domain.model.document.FiscalSale
import io.github.yesidrobayo.shopledger.domain.model.document.SaleFiscalMark
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * Las marcas del puente fiscal, aparte de las ventas: marcar una venta nunca la cambia. Todo es
 * local: nada de esto usa la red.
 *
 * Las lecturas que devuelven [Flow] vuelven a emitir cada vez que cambia lo leído. Las funciones
 * que escriben solo se llaman dentro de [TransactionRunner.write].
 */
interface SaleFiscalMarkRepository {
    /**
     * Las ventas activas de [period] con la marca [status], de la más nueva a la más antigua. Con
     * [FiscalMarkStatus.PENDING] también trae las que no tienen marca.
     */
    fun observeSales(period: ReportPeriod, status: FiscalMarkStatus): Flow<List<FiscalSale>>

    /** La venta [saleId] con su marca, o `null` si no existe o está anulada. */
    fun observeSale(saleId: Long): Flow<FiscalSale?>

    /** Si alguna venta tiene marca, para saber si es el primer uso. */
    fun observeHasMarks(): Flow<Boolean>

    /** La marca de la venta [saleId], o `null` si no tiene. */
    fun observeMark(saleId: Long): Flow<SaleFiscalMark?>

    /** La marca de la venta [saleId], o `null` si no tiene. */
    suspend fun findMark(saleId: Long): SaleFiscalMark?

    /** El estado de la venta [saleId], o `null` si no existe. */
    suspend fun findSaleStatus(saleId: Long): DocumentStatus?

    /** Guarda la primera marca de una venta. Falla si ya tiene una. */
    suspend fun insert(mark: SaleFiscalMark, updatedAt: Instant)

    /** Cambia la marca que la venta ya tiene. Falla si no tiene ninguna. */
    suspend fun update(mark: SaleFiscalMark, updatedAt: Instant)
}

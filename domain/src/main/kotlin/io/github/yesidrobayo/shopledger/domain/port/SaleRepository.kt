package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.ReportPeriod
import io.github.yesidrobayo.shopledger.domain.model.Voiding
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSale
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSaleLine
import io.github.yesidrobayo.shopledger.domain.model.sale.Sale
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLine
import kotlinx.coroutines.flow.Flow

/**
 * Las ventas y sus líneas. Una venta confirmada no se edita: solo se anula.
 *
 * Las lecturas que devuelven [Flow] vuelven a emitir cada vez que se confirma una transacción que
 * cambia lo leído. Las funciones que escriben solo se llaman dentro de [TransactionRunner.write].
 */
interface SaleRepository {
    /** Las ventas de [period], por fecha de negocio, de la más nueva a la más antigua. */
    fun observeIn(period: ReportPeriod): Flow<List<Sale>>

    fun observeById(id: Long): Flow<Sale?>

    /** Las líneas de una venta, en el orden en que se guardaron. */
    fun observeLines(saleId: Long): Flow<List<SaleLine>>

    suspend fun findById(id: Long): Sale?

    /** Las líneas de una venta, en el orden en que se guardaron. */
    suspend fun findLines(saleId: Long): List<SaleLine>

    /** Guarda la cabecera, activa, y devuelve su id, que también es el número de la venta. */
    suspend fun insert(sale: NewSale): Long

    /**
     * Guarda una línea de la venta [saleId] y devuelve su id. Un extra se guarda después de su
     * línea principal, porque necesita su id.
     */
    suspend fun insertLine(saleId: Long, line: NewSaleLine): Long

    /** Anula una venta activa. Es lo único que cambia en ella después de confirmarse. */
    suspend fun markVoided(id: Long, voiding: Voiding)
}

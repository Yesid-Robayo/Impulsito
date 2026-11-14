package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.stock.DatedLotStock
import io.github.yesidrobayo.shopledger.domain.model.stock.LotStock
import io.github.yesidrobayo.shopledger.domain.model.stock.NewStockLot
import io.github.yesidrobayo.shopledger.domain.model.stock.StockLot
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * Los lotes de los productos que se controlan por lotes. El lote sin asignar no se crea aquí: lo
 * crea el libro de stock la primera vez que una fila lo necesita.
 *
 * Las listas salen en el orden en que entraron los lotes, por id. [insert] solo se llama dentro de
 * [TransactionRunner.write].
 */
interface StockLotRepository {
    /** Los lotes de un producto, también el sin asignar y los cerrados. */
    fun observeByProduct(productId: Long): Flow<List<StockLot>>

    /** Todos los lotes, de todos los productos. */
    fun observeAll(): Flow<List<StockLot>>

    suspend fun findById(id: Long): StockLot?

    /** El lote del producto con ese código, que es único dentro del producto. */
    suspend fun findByCode(productId: Long, code: String): StockLot?

    /**
     * Cuánto hay de cada lote del producto en la ubicación, con el sin asignar si existe. Un lote
     * sin saldo en esa ubicación sale con cero.
     */
    suspend fun findStock(productId: Long, locationId: Long): List<LotStock>

    /** Agrega un lote con código y devuelve su id. Falla si el producto ya tiene ese código. */
    suspend fun insert(lot: NewStockLot): Long

    /**
     * Anota en el lote [lotId] la línea de compra [purchaseLineId] con que entró. Se llama en la
     * misma transacción que crea los dos, después de la línea, que necesita el lote para existir.
     */
    suspend fun attachPurchaseLine(lotId: Long, purchaseLineId: Long)

    /**
     * Los lotes abiertos con vencimiento y con stock, sumado en todas las ubicaciones, con los
     * días de aviso de su producto. Vuelve a emitir con cada cambio de lotes, saldos o
     * productos. Un producto archivado no avisa.
     */
    fun observeDated(): Flow<List<DatedLotStock>>

    /** Anota que ya se avisó el vencimiento de [lotIds], para no repetirlo. */
    suspend fun markExpiryNotified(lotIds: List<Long>, notifiedAt: Instant)
}

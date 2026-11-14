package io.github.yesidrobayo.shopledger.data.local.stock

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Lecturas y escrituras de `stock_lots`. Las escrituras solo se llaman dentro de la transacción del
 * caso de uso.
 *
 * Los lotes de un producto salen en el orden en que entraron, por `id`. Sin `REPLACE`: un `INSERT
 * OR REPLACE` sobre el mismo código borraría el lote que choca, y con filas en el libro fallaría su
 * llave a mitad del caso de uso. El producto y la marca de lote sin asignar no cambian nunca: ninguna
 * escritura los toca, y la guarda lo impide.
 */
@Dao
internal interface StockLotDao {
    @Query("SELECT * FROM stock_lots WHERE product_id = :productId ORDER BY id")
    fun observeByProduct(productId: Long): Flow<List<StockLotEntity>>

    @Query("SELECT * FROM stock_lots ORDER BY id")
    fun observeAll(): Flow<List<StockLotEntity>>

    @Query("SELECT * FROM stock_lots WHERE product_id = :productId ORDER BY id")
    suspend fun findByProduct(productId: Long): List<StockLotEntity>

    @Query("SELECT * FROM stock_lots WHERE id = :id")
    suspend fun findById(id: Long): StockLotEntity?

    /** El lote del producto con ese código: el código es único dentro del producto. */
    @Query("SELECT * FROM stock_lots WHERE product_id = :productId AND lot_code = :lotCode")
    suspend fun findByCode(productId: Long, lotCode: String): StockLotEntity?

    /** El lote sin asignar del producto, que absorbe lo vendido sin lote. Hay a lo sumo uno. */
    @Query("SELECT * FROM stock_lots WHERE product_id = :productId AND is_unassigned = 1")
    suspend fun findUnassigned(productId: Long): StockLotEntity?

    /**
     * Si alguna fila del libro, alguna línea de compra o alguna línea devuelta a un proveedor lo
     * usa. Con uso ya no se borra: se cierra.
     */
    @Query(
        "SELECT EXISTS (SELECT 1 FROM stock_movements WHERE lot_id = :id) " +
            "OR EXISTS (SELECT 1 FROM purchase_lines WHERE lot_id = :id) " +
            "OR EXISTS (SELECT 1 FROM purchase_return_lines WHERE lot_id = :id)",
    )
    suspend fun isInUse(id: Long): Boolean

    /**
     * Cada lote del producto con lo que tiene en la ubicación, también el sin asignar. Un lote que
     * nunca se movió en esa ubicación sale con cero: su parte no existe todavía.
     */
    @Query(
        "SELECT l.id, l.lot_code, l.expiry_date, l.is_unassigned, " +
            "COALESCE(b.on_hand_milli, 0) AS on_hand_milli " +
            "FROM stock_lots l " +
            "LEFT JOIN stock_balances b ON b.product_id = l.product_id " +
            "AND b.location_id = :locationId AND b.lot_key = l.id " +
            "WHERE l.product_id = :productId ORDER BY l.id",
    )
    suspend fun findStock(productId: Long, locationId: Long): List<LotStockRow>

    /**
     * Los lotes abiertos con código, vencimiento y stock, sumado en todas las ubicaciones, de
     * productos sin archivar: lo que mira el aviso de vencimiento. Del que vence antes al que vence
     * después, y por id entre dos del mismo día.
     */
    @Query(
        "SELECT l.id, l.product_id, p.name AS product_name, l.lot_code, l.expiry_date, " +
            "SUM(b.on_hand_milli) AS on_hand_milli, p.base_unit_id, p.expiry_notice_days, " +
            "l.expiry_notified_at " +
            "FROM stock_lots l " +
            "JOIN products p ON p.id = l.product_id " +
            "JOIN stock_balances b ON b.product_id = l.product_id AND b.lot_key = l.id " +
            "WHERE l.expiry_date IS NOT NULL AND l.closed_at IS NULL AND l.is_unassigned = 0 " +
            "AND p.archived_at IS NULL " +
            "GROUP BY l.id HAVING SUM(b.on_hand_milli) > 0 " +
            "ORDER BY l.expiry_date, l.id",
    )
    fun observeDated(): Flow<List<DatedLotRow>>

    /**
     * Lo que tiene cada lote en cada ubicación, sin las partes en cero ni el stock sin lote, que
     * se guarda con la clave de lote 0.
     */
    @Query(
        "SELECT lot_key, location_id, on_hand_milli FROM stock_balances " +
            "WHERE lot_key <> 0 AND on_hand_milli <> 0 ORDER BY lot_key, location_id",
    )
    fun observeHoldings(): Flow<List<LotHoldingRow>>

    /** Devuelve el id nuevo. Falla si el producto ya tiene ese código o ya tiene lote sin asignar. */
    @Insert
    suspend fun insert(lot: StockLotEntity): Long

    /** Cambia el código y el vencimiento. Devuelve las filas cambiadas. */
    @Query(
        "UPDATE stock_lots SET lot_code = :lotCode, expiry_date = :expiryDate WHERE id = :id",
    )
    suspend fun updateCodeAndExpiry(id: Long, lotCode: String?, expiryDate: String?): Int

    /** Anota la línea de compra con que entró el lote. Devuelve las filas cambiadas. */
    @Query("UPDATE stock_lots SET purchase_line_id = :purchaseLineId WHERE id = :id")
    suspend fun setPurchaseLine(id: Long, purchaseLineId: Long): Int

    /** Anota el aviso de vencimiento, para no repetirlo, o lo borra con `null`. */
    @Query("UPDATE stock_lots SET expiry_notified_at = :notifiedAt WHERE id = :id")
    suspend fun setExpiryNotifiedAt(id: Long, notifiedAt: Long?): Int

    /** Lo cierra con [closedAt], o lo reabre con `null`. */
    @Query("UPDATE stock_lots SET closed_at = :closedAt WHERE id = :id")
    suspend fun setClosedAt(id: Long, closedAt: Long?): Int

    /**
     * Elimina un lote sin uso. Con uso, una llave `RESTRICT` hace fallar la sentencia y no borra
     * nada. Devuelve las filas borradas.
     */
    @Query("DELETE FROM stock_lots WHERE id = :id")
    suspend fun delete(id: Long): Int
}

/** Un lote con vencimiento y su saldo total, tal como lo lee [StockLotDao.observeDated]. */
internal data class DatedLotRow(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "product_id") val productId: Long,
    @ColumnInfo(name = "product_name") val productName: String,
    @ColumnInfo(name = "lot_code") val lotCode: String?,
    @ColumnInfo(name = "expiry_date") val expiryDate: String,
    @ColumnInfo(name = "on_hand_milli") val onHandMilli: Long,
    @ColumnInfo(name = "base_unit_id") val baseUnitId: Long,
    @ColumnInfo(name = "expiry_notice_days") val expiryNoticeDays: Long?,
    @ColumnInfo(name = "expiry_notified_at") val expiryNotifiedAt: Long?,
)

/** Un lote con su saldo en una ubicación, tal como lo lee [StockLotDao.findStock]. */
internal data class LotStockRow(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "lot_code") val lotCode: String?,
    @ColumnInfo(name = "expiry_date") val expiryDate: String?,
    @ColumnInfo(name = "is_unassigned") val isUnassigned: Long,
    @ColumnInfo(name = "on_hand_milli") val onHandMilli: Long,
)

/** Lo que tiene un lote en una ubicación, tal como lo lee [StockLotDao.observeHoldings]. */
internal data class LotHoldingRow(
    @ColumnInfo(name = "lot_key") val lotId: Long,
    @ColumnInfo(name = "location_id") val locationId: Long,
    @ColumnInfo(name = "on_hand_milli") val onHandMilli: Long,
)

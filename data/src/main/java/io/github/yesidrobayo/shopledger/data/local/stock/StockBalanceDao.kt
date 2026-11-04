package io.github.yesidrobayo.shopledger.data.local.stock

import androidx.room3.Dao
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Lecturas y la única escritura de `stock_balances`: sumar una cantidad a una parte del stock.
 *
 * Solo la llama la escritura del libro, en la misma transacción que agrega la fila: así cada saldo
 * es siempre la suma de las filas de su parte. Nada lo fija a un valor, nada lo borra, y nunca con
 * `REPLACE`, que borraría el saldo que choca y lo reemplazaría por la cantidad de una sola fila.
 *
 * Las listas van por `id`, que es el orden en que apareció cada parte.
 */
@Dao
internal interface StockBalanceDao {
    /**
     * Suma [deltaMilli] al saldo de la parte `(productId, locationId, lotKey)`, y la crea con ese
     * saldo si todavía no existe. Es una sola sentencia sobre el índice único de la parte: dentro de
     * la transacción de la escritura, dos filas de la misma parte suman las dos, sin leer el saldo
     * antes ni perder ninguna. [lotKey] sale de [StockBalanceEntity.lotKeyOf].
     *
     * Si la suma no cabe en un entero de 64 bits, SQLite la vuelve real y la guarda de la tabla la
     * rechaza: la transacción entera se deshace y el saldo no se pierde.
     */
    @Query(
        "INSERT INTO stock_balances (product_id, location_id, lot_key, on_hand_milli) " +
            "VALUES (:productId, :locationId, :lotKey, :deltaMilli) " +
            "ON CONFLICT (product_id, location_id, lot_key) " +
            "DO UPDATE SET on_hand_milli = on_hand_milli + excluded.on_hand_milli",
    )
    suspend fun add(productId: Long, locationId: Long, lotKey: Long, deltaMilli: Long)

    /** El saldo de una parte, o `null` si ninguna fila del libro cayó todavía en ella. */
    @Query(
        "SELECT * FROM stock_balances " +
            "WHERE product_id = :productId AND location_id = :locationId AND lot_key = :lotKey",
    )
    suspend fun find(productId: Long, locationId: Long, lotKey: Long): StockBalanceEntity?

    /** Los saldos de un producto, uno por ubicación y lote. */
    @Query("SELECT * FROM stock_balances WHERE product_id = :productId ORDER BY id")
    suspend fun findByProduct(productId: Long): List<StockBalanceEntity>

    @Query("SELECT * FROM stock_balances WHERE product_id = :productId ORDER BY id")
    fun observeByProduct(productId: Long): Flow<List<StockBalanceEntity>>

    /** Los saldos de una ubicación, de todos sus productos y lotes. */
    @Query("SELECT * FROM stock_balances WHERE location_id = :locationId ORDER BY id")
    suspend fun findByLocation(locationId: Long): List<StockBalanceEntity>

    /**
     * La suma de los saldos de un producto, que debe ser su `on_hand_milli`. Sin saldos, 0. Si la
     * suma no cabe en un entero de 64 bits, SQLite aborta la consulta.
     */
    @Query(
        "SELECT COALESCE(SUM(on_hand_milli), 0) FROM stock_balances WHERE product_id = :productId",
    )
    suspend fun sumByProduct(productId: Long): Long
}

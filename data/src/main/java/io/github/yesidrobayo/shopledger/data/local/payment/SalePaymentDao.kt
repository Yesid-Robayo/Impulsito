package io.github.yesidrobayo.shopledger.data.local.payment

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Los pagos de las ventas: agregar y leer, nada más.
 *
 * No hay función que edite ni borre un pago, y no debe haberla: los pagos de una venta suman lo
 * que esa venta debía cobrar, también después de anularla. La inserción no usa `REPLACE`, que
 * borraría el pago que choca. Los pagos salen en el orden en que se guardaron, por `id`.
 */
@Dao
internal interface SalePaymentDao {
    @Query("SELECT * FROM sale_payments WHERE sale_id = :saleId ORDER BY id")
    fun observeBySale(saleId: Long): Flow<List<SalePaymentEntity>>

    @Query("SELECT * FROM sale_payments WHERE sale_id = :saleId ORDER BY id")
    suspend fun findBySale(saleId: Long): List<SalePaymentEntity>

    /** Agrega el pago y devuelve su id. Solo se llama dentro de la transacción del caso de uso. */
    @Insert
    suspend fun insert(payment: SalePaymentEntity): Long
}

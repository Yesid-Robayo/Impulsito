package io.github.yesidrobayo.shopledger.data.local.report

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Lecturas y escrituras de `business_goals`, siempre por mes `YYYY-MM`. Las escrituras solo se
 * llaman dentro de la transacción del caso de uso.
 *
 * Sin `REPLACE`: un `INSERT OR REPLACE` sobre un mes que ya tiene metas borraría la fila y
 * escribiría otra. Un mes nuevo se inserta, y uno que ya existe se actualiza.
 */
@Dao
internal interface BusinessGoalDao {
    /** Las metas de [month], de nuevo cada vez que cambian. Nulo si el mes no tiene metas. */
    @Query("SELECT * FROM business_goals WHERE month = :month")
    fun observeByMonth(month: String): Flow<BusinessGoalEntity?>

    @Query("SELECT * FROM business_goals WHERE month = :month")
    suspend fun findByMonth(month: String): BusinessGoalEntity?

    /** Devuelve el id nuevo. Falla si el mes ya tiene metas. */
    @Insert
    suspend fun insert(goal: BusinessGoalEntity): Long

    /** Cambia las metas de [month]. Devuelve las filas cambiadas: 0 si el mes no tiene metas. */
    @Query(
        "UPDATE business_goals SET sales_goal = :salesGoal, profit_goal = :profitGoal, " +
            "updated_at = :updatedAt WHERE month = :month",
    )
    suspend fun update(month: String, salesGoal: Long?, profitGoal: Long?, updatedAt: Long): Int

    /** Quita las metas de [month]: nada depende de ellas. Devuelve las filas borradas. */
    @Query("DELETE FROM business_goals WHERE month = :month")
    suspend fun delete(month: String): Int
}

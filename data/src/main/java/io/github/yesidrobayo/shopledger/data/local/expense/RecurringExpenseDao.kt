package io.github.yesidrobayo.shopledger.data.local.expense

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Lecturas y escrituras de `recurring_expenses`. Las escrituras solo se llaman dentro de la
 * transacción del caso de uso.
 *
 * Las listas salen en el orden en que se crearon, por `id`, salvo las que vencen, que salen por
 * fecha de vencimiento y después por `id`. Sin `REPLACE`: un `INSERT OR REPLACE` borraría la
 * plantilla que choca, y con gastos generados fallaría su llave a mitad del caso de uso.
 */
@Dao
internal interface RecurringExpenseDao {
    /** Las vigentes: las que la app sigue proponiendo. */
    @Query("SELECT * FROM recurring_expenses WHERE archived_at IS NULL ORDER BY id")
    fun observeActive(): Flow<List<RecurringExpenseEntity>>

    /** Las archivadas: ya no se proponen, y se pueden reactivar. */
    @Query("SELECT * FROM recurring_expenses WHERE archived_at IS NOT NULL ORDER BY id")
    fun observeArchived(): Flow<List<RecurringExpenseEntity>>

    @Query("SELECT * FROM recurring_expenses WHERE id = :id")
    suspend fun findById(id: Long): RecurringExpenseEntity?

    /**
     * Las vigentes que vencen hasta [today], fecha de negocio `YYYY-MM-DD`: las que la app propone
     * confirmar. Primero la más atrasada.
     */
    @Query(
        "SELECT * FROM recurring_expenses WHERE archived_at IS NULL AND next_due_date <= :today " +
            "ORDER BY next_due_date, id",
    )
    suspend fun findDue(today: String): List<RecurringExpenseEntity>

    /** Si ya generó gastos, aunque estén anulados. Con gastos ya no se borra: solo se archiva. */
    @Query("SELECT EXISTS (SELECT 1 FROM expenses WHERE recurring_expense_id = :id)")
    suspend fun hasExpenses(id: Long): Boolean

    /** Devuelve el id nuevo. */
    @Insert
    suspend fun insert(recurringExpense: RecurringExpenseEntity): Long

    /**
     * Cambia lo que la persona escribe, sin tocar el archivo ni `created_at`. Devuelve las filas
     * cambiadas.
     */
    @Query(
        "UPDATE recurring_expenses SET expense_category_id = :expenseCategoryId, " +
            "amount = :amount, description = :description, recurrence = :recurrence, " +
            "day_of_period = :dayOfPeriod, next_due_date = :nextDueDate, " +
            "money_account_id = :moneyAccountId, reminder_enabled = :reminderEnabled, " +
            "updated_at = :updatedAt WHERE id = :id",
    )
    suspend fun update(
        id: Long,
        expenseCategoryId: Long,
        amount: Long,
        description: String?,
        recurrence: String,
        dayOfPeriod: Long,
        nextDueDate: String,
        moneyAccountId: Long?,
        reminderEnabled: Long,
        updatedAt: Long,
    ): Int

    /**
     * Pasa al vencimiento siguiente, al confirmar o saltar el actual. Devuelve las filas cambiadas.
     */
    @Query(
        "UPDATE recurring_expenses SET next_due_date = :nextDueDate, updated_at = :updatedAt " +
            "WHERE id = :id",
    )
    suspend fun updateNextDueDate(id: Long, nextDueDate: String, updatedAt: Long): Int

    /** La archiva con [archivedAt], o la reactiva con `null`. Devuelve las filas cambiadas. */
    @Query(
        "UPDATE recurring_expenses SET archived_at = :archivedAt, updated_at = :updatedAt " +
            "WHERE id = :id",
    )
    suspend fun setArchivedAt(id: Long, archivedAt: Long?, updatedAt: Long): Int

    /**
     * Elimina una plantilla sin gastos generados. Con gastos, su llave `RESTRICT` hace fallar la
     * sentencia y no borra nada. Devuelve 0 si no existe.
     */
    @Query("DELETE FROM recurring_expenses WHERE id = :id")
    suspend fun delete(id: Long): Int
}

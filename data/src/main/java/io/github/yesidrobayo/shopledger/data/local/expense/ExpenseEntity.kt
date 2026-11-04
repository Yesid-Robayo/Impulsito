package io.github.yesidrobayo.shopledger.data.local.expense

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * Un gasto. Un gasto registrado no se edita ni se borra: solo se anula, y la anulación es lo único
 * que cambia en la fila.
 *
 * - `amount` va en unidad menor de la moneda del negocio, siempre `Long` y mayor que cero.
 * - `status` nace `ACTIVE` y solo pasa a `VOIDED`, con `voided_at` y `void_reason`.
 * - La categoría usa `RESTRICT`: una categoría con gastos no se borra, se archiva.
 * - `money_account_id` y `recurring_expense_id` todavía no tienen llave foránea: sus tablas no
 *   existen aún. La llave llega con la tabla a la que apunta, dentro del mismo esquema 1.
 */
@Entity(
    tableName = "expenses",
    foreignKeys = [
        ForeignKey(
            entity = ExpenseCategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["expense_category_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["expense_category_id"]),
        Index(value = ["status", "business_date"]),
    ],
)
internal data class ExpenseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "expense_category_id") val expenseCategoryId: Long,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "amount") val amount: Long,
    @ColumnInfo(name = "description") val description: String?,
    @ColumnInfo(name = "money_account_id") val moneyAccountId: Long?,
    @ColumnInfo(name = "recurring_expense_id") val recurringExpenseId: Long?,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "business_date") val businessDate: String,
    @ColumnInfo(name = "voided_at") val voidedAt: Long?,
    @ColumnInfo(name = "void_reason") val voidReason: String?,
    @ColumnInfo(name = "void_note") val voidNote: String?,
)

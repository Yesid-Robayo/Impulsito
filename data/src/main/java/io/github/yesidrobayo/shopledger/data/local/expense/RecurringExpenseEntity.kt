package io.github.yesidrobayo.shopledger.data.local.expense

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import androidx.room3.RoomWarnings
import io.github.yesidrobayo.shopledger.data.local.money.MoneyAccountEntity

/**
 * Un gasto recurrente: la plantilla de un gasto que se repite, como un arriendo o un servicio. La
 * app lo propone cuando vence y la persona lo confirma: nunca registra un gasto sola.
 *
 * - `amount` va en unidad menor de la moneda del negocio, siempre `Long` y mayor que cero.
 * - `recurrence` es el nombre guardado de `Recurrence`. `day_of_period` es el día de la semana,
 *   del mes o del año, según la recurrencia: la guarda lo acota a 7, 31 o 366.
 * - `next_due_date` es una fecha de negocio `YYYY-MM-DD`, la del próximo vencimiento.
 * - La categoría usa `RESTRICT`: una categoría con plantillas no se borra, se archiva.
 * - La cuenta de dinero usa `RESTRICT`: una cuenta con plantillas no se borra. No lleva índice: las
 *   cuentas casi nunca se borran, y SQLite solo busca plantillas por esa columna al borrar una.
 * - Los gastos que salieron de ella la apuntan con `RESTRICT`: una plantilla con gastos generados
 *   no se borra, se archiva.
 *
 * El índice de `next_due_date` encuentra las que vencen sin recorrer todas.
 */
@Suppress(RoomWarnings.MISSING_INDEX_ON_FOREIGN_KEY_CHILD)
@Entity(
    tableName = "recurring_expenses",
    foreignKeys = [
        ForeignKey(
            entity = ExpenseCategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["expense_category_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = MoneyAccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["money_account_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["expense_category_id"]),
        Index(value = ["next_due_date"]),
    ],
)
internal data class RecurringExpenseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "expense_category_id") val expenseCategoryId: Long,
    @ColumnInfo(name = "amount") val amount: Long,
    @ColumnInfo(name = "description") val description: String?,
    @ColumnInfo(name = "recurrence") val recurrence: String,
    @ColumnInfo(name = "day_of_period") val dayOfPeriod: Long,
    @ColumnInfo(name = "next_due_date") val nextDueDate: String,
    @ColumnInfo(name = "money_account_id") val moneyAccountId: Long?,
    @ColumnInfo(name = "reminder_enabled") val reminderEnabled: Long,
    @ColumnInfo(name = "archived_at") val archivedAt: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

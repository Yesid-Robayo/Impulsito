package io.github.yesidrobayo.shopledger.data.local.expense

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * Una categoría de gasto: una de las iniciales, con su clave, o una que creó la persona.
 *
 * - `system_key` es la clave estable, en inglés, de una categoría inicial. Mientras `name` es nulo
 *   se muestra el texto traducido de esa clave, en el idioma de la interfaz; las que crea la
 *   persona no tienen clave y siempre tienen nombre. Por eso `name` y `normalized_name` admiten
 *   nulo, y siempre van juntos.
 * - `normalized_name` lo calcula `TextNormalizer` en el dominio, y es único en toda la tabla, como
 *   pide el modelo de datos: una archivada también reserva su nombre. SQLite no compara nulos, así
 *   que las iniciales sin nombre no chocan entre sí.
 * - `is_system` vale 1 solo en la de otros gastos, que nunca se archiva ni se borra: siempre hay
 *   dónde anotar un gasto. `is_fixed` marca un gasto fijo, para el punto de equilibrio.
 *
 * Booleanos en `Long`, 0 o 1, y sin valores por defecto en las columnas: los pone el código.
 */
@Entity(
    tableName = "expense_categories",
    indices = [
        Index(value = ["system_key"], unique = true),
        Index(value = ["normalized_name"], unique = true),
    ],
)
internal data class ExpenseCategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "system_key") val systemKey: String?,
    @ColumnInfo(name = "name") val name: String?,
    @ColumnInfo(name = "normalized_name") val normalizedName: String?,
    @ColumnInfo(name = "is_system") val isSystem: Long,
    @ColumnInfo(name = "is_fixed") val isFixed: Long,
    @ColumnInfo(name = "archived_at") val archivedAt: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

package io.github.yesidrobayo.shopledger.data.local.report

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * Las metas de un mes: cuánto quiere vender la persona y cuánto quiere ganar.
 *
 * - `month` es el mes de negocio `YYYY-MM`, único: un mes tiene a lo sumo una fila de metas. El
 *   formato lo exige la guarda, y la unicidad el índice.
 * - `sales_goal` y `profit_goal` van en unidad menor de la moneda del negocio. Cada una es opcional,
 *   y la de ganancia admite negativos: un mes puede planearse con pérdida.
 */
@Entity(
    tableName = "business_goals",
    indices = [Index(value = ["month"], unique = true)],
)
internal data class BusinessGoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "month") val month: String,
    @ColumnInfo(name = "sales_goal") val salesGoal: Long?,
    @ColumnInfo(name = "profit_goal") val profitGoal: Long?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

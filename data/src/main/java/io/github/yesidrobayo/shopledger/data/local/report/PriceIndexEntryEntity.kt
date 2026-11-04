package io.github.yesidrobayo.shopledger.data.local.report

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * La variación de precios de un mes, que escribe la persona o importa de un CSV, para comparar
 * ventas en términos reales. La app nunca descarga un índice ni trae series de un país.
 *
 * - `month` es el mes `YYYY-MM`, único: un mes tiene a lo sumo una variación. El formato lo exige
 *   la guarda, y la unicidad el índice.
 * - `change_bp` es la variación del mes, con signo, en puntos básicos.
 * - `source_note` dice de dónde la sacó la persona, con sus palabras.
 */
@Entity(
    tableName = "price_index_entries",
    indices = [Index(value = ["month"], unique = true)],
)
internal data class PriceIndexEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "month") val month: String,
    @ColumnInfo(name = "change_bp") val changeBp: Long,
    @ColumnInfo(name = "source_note") val sourceNote: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

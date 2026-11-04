package io.github.yesidrobayo.shopledger.data.local.tax

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * Una tasa de impuesto que escribió la persona, con el nombre que ella quiera.
 *
 * La base nace sin ninguna: la app no trae tasas de ningún país, ni las descarga. Por eso no hay
 * clave inicial ni nombre traducible, y `name` siempre tiene valor.
 *
 * - `rate_bp` es la tasa en puntos básicos, de 0 a 10.000: el 21,00 % se guarda como 2.100.
 * - `normalized_name` lo calcula `TextNormalizer` en el dominio. Es único solo entre las vigentes,
 *   como en productos y proveedores: una archivada no reserva su nombre. Por eso su índice no es
 *   único, y la unicidad la impone la guarda de la tabla.
 * - Una tasa usada en ventas o compras se archiva, y esas líneas conservan su copia de la tasa y
 *   del nombre. Si se borra, los productos que la usaban quedan sin tasa.
 *
 * Solo `Long` y `String`: el repositorio convierte a los tipos del dominio, y la entidad nunca sale
 * de esta capa. Sin valores por defecto en las columnas: los pone el código.
 */
@Entity(
    tableName = "tax_rates",
    indices = [Index(value = ["normalized_name"])],
)
internal data class TaxRateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "normalized_name") val normalizedName: String,
    @ColumnInfo(name = "rate_bp") val rateBp: Long,
    @ColumnInfo(name = "archived_at") val archivedAt: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

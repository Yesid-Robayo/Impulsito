package io.github.yesidrobayo.shopledger.data.local.catalog

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * Un proveedor.
 *
 * Con compras o con filas en su libro de cuenta ya no se borra: se archiva con `archived_at`. Su
 * nombre normalizado no se repite entre proveedores sin archivar, pero un archivado no reserva el
 * suyo. Un índice único no sabe de esa excepción, así que el índice solo ordena y busca, y la
 * unicidad entre activos la impone la guarda de la tabla.
 *
 * `balance` es lo que se le debe, en unidad menor: positivo es deuda del negocio. Es un saldo
 * derivado de su libro y solo lo mueve la escritura de ese libro, nunca la edición del proveedor.
 */
@Entity(
    tableName = "suppliers",
    indices = [Index(value = ["normalized_name"])],
)
internal data class SupplierEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "normalized_name") val normalizedName: String,
    @ColumnInfo(name = "phone") val phone: String?,
    @ColumnInfo(name = "email") val email: String?,
    @ColumnInfo(name = "notes") val notes: String?,
    @ColumnInfo(name = "balance") val balance: Long,
    @ColumnInfo(name = "payment_terms_days") val paymentTermsDays: Long?,
    @ColumnInfo(name = "archived_at") val archivedAt: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

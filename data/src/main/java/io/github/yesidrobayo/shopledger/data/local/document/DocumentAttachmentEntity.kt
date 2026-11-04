package io.github.yesidrobayo.shopledger.data.local.document

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import io.github.yesidrobayo.shopledger.data.local.catalog.MediaAssetEntity
import io.github.yesidrobayo.shopledger.data.local.expense.ExpenseEntity
import io.github.yesidrobayo.shopledger.data.local.purchase.PurchaseEntity

/**
 * La foto de un comprobante, unida a un gasto o a una compra. La foto no vive aquí:
 * `media_asset_id` apunta a su registro, y el archivo está fuera de la base.
 *
 * - Exactamente un documento: o `expense_id` o `purchase_id`, nunca los dos ni ninguno. Lo exige la
 *   guarda, al crearla y al cambiarla.
 * - El gasto y la compra usan `RESTRICT`, aunque nunca se borran: solo se anulan.
 * - La foto también usa `RESTRICT`: la limpieza de fotos sin referencias no borra la de un
 *   comprobante. Su índice sirve a esa búsqueda.
 * - Solo se borra porque la persona lo decide.
 */
@Entity(
    tableName = "document_attachments",
    foreignKeys = [
        ForeignKey(
            entity = MediaAssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["media_asset_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = ExpenseEntity::class,
            parentColumns = ["id"],
            childColumns = ["expense_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = PurchaseEntity::class,
            parentColumns = ["id"],
            childColumns = ["purchase_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["media_asset_id"]),
        Index(value = ["expense_id"]),
        Index(value = ["purchase_id"]),
    ],
)
internal data class DocumentAttachmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "media_asset_id") val mediaAssetId: Long,
    @ColumnInfo(name = "expense_id") val expenseId: Long?,
    @ColumnInfo(name = "purchase_id") val purchaseId: Long?,
    @ColumnInfo(name = "caption") val caption: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

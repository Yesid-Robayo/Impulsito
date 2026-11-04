package io.github.yesidrobayo.shopledger.data.local.catalog

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * El vínculo entre un producto y una etiqueta. El par es único, y se va en cascada con cualquiera
 * de los dos. El índice de `tag_id` sirve a la cascada y al filtro de productos por etiqueta.
 */
@Entity(
    tableName = "product_tags",
    foreignKeys = [
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["product_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tag_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["product_id", "tag_id"], unique = true),
        Index(value = ["tag_id"]),
    ],
)
internal data class ProductTagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "product_id") val productId: Long,
    @ColumnInfo(name = "tag_id") val tagId: Long,
)

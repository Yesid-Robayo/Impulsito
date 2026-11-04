package io.github.yesidrobayo.shopledger.data.local.payment

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import io.github.yesidrobayo.shopledger.data.local.catalog.MediaAssetEntity
import io.github.yesidrobayo.shopledger.data.local.money.MoneyAccountEntity

/**
 * Un método de pago: uno de los seis iniciales, con su clave, o uno que creó la persona.
 *
 * Son datos que la persona edita, no una lista de marcas: el código no conoce ninguna billetera,
 * banco ni red. Lo que decide el comportamiento es `kind`, el nombre guardado de
 * `PaymentMethodKind`.
 *
 * - `system_key` es la clave estable, en inglés, de un método inicial. Mientras `name` es nulo se
 *   muestra el texto traducido de esa clave; los que crea la persona no tienen clave y siempre
 *   tienen nombre. Por eso `name` y `normalized_name` admiten nulo, y siempre van juntos.
 * - `normalized_name` lo calcula `TextNormalizer` en el dominio. Es único solo entre los
 *   vigentes, como en productos y proveedores: un archivado no reserva su nombre. Por eso su
 *   índice no es único, y la unicidad la impone la guarda de la tabla. Los iniciales sin nombre no
 *   chocan entre sí.
 * - `fee_bp` y `fee_fixed` son la comisión que escribe la persona, nunca una tarifa de la app.
 *   `price_adjustment_bp` lleva signo: un recargo o un descuento por pagar con este método.
 * - `sort_order` es el orden en que se ofrecen al cobrar. Las listas lo desempatan por `id`.
 * - `instructions_media_id` es la imagen con los datos de cobro, como un código QR. Al borrar la
 *   imagen, el método queda sin ella.
 * - `money_account_id` es la cuenta a la que entra lo cobrado con este método. Con métodos, la
 *   cuenta no se borra: se archiva.
 * - Con ventas, un método no se borra: se archiva, y sus ventas conservan su nombre y su tipo.
 *
 * Solo `Long` y `String`, y sin valores por defecto en las columnas: los pone el código.
 */
@Entity(
    tableName = "payment_methods",
    foreignKeys = [
        ForeignKey(
            entity = MoneyAccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["money_account_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = MediaAssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["instructions_media_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["system_key"], unique = true),
        Index(value = ["normalized_name"]),
        Index(value = ["money_account_id"]),
        Index(value = ["instructions_media_id"]),
    ],
)
internal data class PaymentMethodEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "system_key") val systemKey: String?,
    @ColumnInfo(name = "name") val name: String?,
    @ColumnInfo(name = "normalized_name") val normalizedName: String?,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "fee_bp") val feeBp: Long,
    @ColumnInfo(name = "fee_fixed") val feeFixed: Long,
    @ColumnInfo(name = "settlement_days") val settlementDays: Long,
    @ColumnInfo(name = "price_adjustment_bp") val priceAdjustmentBp: Long,
    @ColumnInfo(name = "money_account_id") val moneyAccountId: Long?,
    @ColumnInfo(name = "instructions_text") val instructionsText: String?,
    @ColumnInfo(name = "instructions_media_id") val instructionsMediaId: Long?,
    @ColumnInfo(name = "sort_order") val sortOrder: Long,
    @ColumnInfo(name = "archived_at") val archivedAt: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

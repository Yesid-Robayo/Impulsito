package io.github.yesidrobayo.shopledger.data.local.payment

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import androidx.room3.RoomWarnings
import io.github.yesidrobayo.shopledger.data.local.money.MoneyAccountEntity
import io.github.yesidrobayo.shopledger.data.local.sale.SaleEntity

/**
 * Un pago de una venta: una fila por método, al menos una, también cuando el total es 0. Nunca se
 * edita ni se borra, ni siquiera al anular la venta: los pagos explican lo que se cobró, y la suma
 * de los de una venta es lo que esa venta debía cobrar.
 *
 * - El nombre y el tipo del método se copian al cobrar: renombrar o archivar el método no cambia
 *   las ventas pasadas. Un tipo desconocido que llegue en una copia restaurada se lee como otro
 *   medio, y la fila no se toca.
 * - `amount` es lo que cubre este pago, y `fee_amount` su comisión, en la moneda del negocio.
 *   `settlement_date` es la fecha de negocio en que se acredita.
 * - `tendered_amount` y `change_amount` van juntos, y solo en efectivo: lo recibido menos el cambio
 *   es `amount`.
 * - Las cuatro columnas `tender_*` y `fx_rate_micro` van juntas: el pago se recibió en otra
 *   moneda, que nunca se mezcla con la del negocio.
 * - Venta, método y cuenta usan `RESTRICT`: con pagos, ninguno de los tres se borra. El método y
 *   la cuenta no llevan índice, porque solo se busca por ellos al intentar borrarlos. Sin cuenta,
 *   el pago no mueve dinero: quien no usa cuentas no paga ningún costo.
 *
 * Solo `Long` y `String`, y sin valores por defecto en las columnas: los pone el código.
 */
@Suppress(RoomWarnings.MISSING_INDEX_ON_FOREIGN_KEY_CHILD)
@Entity(
    tableName = "sale_payments",
    foreignKeys = [
        ForeignKey(
            entity = SaleEntity::class,
            parentColumns = ["id"],
            childColumns = ["sale_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = PaymentMethodEntity::class,
            parentColumns = ["id"],
            childColumns = ["payment_method_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = MoneyAccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["money_account_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index(value = ["sale_id"])],
)
internal data class SalePaymentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "sale_id") val saleId: Long,
    @ColumnInfo(name = "payment_method_id") val paymentMethodId: Long,
    @ColumnInfo(name = "method_name_snapshot") val methodNameSnapshot: String,
    @ColumnInfo(name = "method_kind_snapshot") val methodKindSnapshot: String,
    @ColumnInfo(name = "amount") val amount: Long,
    @ColumnInfo(name = "fee_amount") val feeAmount: Long,
    @ColumnInfo(name = "settlement_date") val settlementDate: String,
    @ColumnInfo(name = "money_account_id") val moneyAccountId: Long?,
    @ColumnInfo(name = "tendered_amount") val tenderedAmount: Long?,
    @ColumnInfo(name = "change_amount") val changeAmount: Long?,
    @ColumnInfo(name = "tender_currency_code") val tenderCurrencyCode: String?,
    @ColumnInfo(name = "tender_currency_decimals") val tenderCurrencyDecimals: Long?,
    @ColumnInfo(name = "tender_amount") val tenderAmount: Long?,
    @ColumnInfo(name = "fx_rate_micro") val fxRateMicro: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

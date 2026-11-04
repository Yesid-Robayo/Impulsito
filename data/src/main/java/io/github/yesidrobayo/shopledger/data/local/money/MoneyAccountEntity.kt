package io.github.yesidrobayo.shopledger.data.local.money

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * Una cuenta de dinero: la caja, un banco o una billetera. Cada una lleva su moneda y su saldo.
 *
 * - `system_key` solo lo tiene la cuenta que nace con la base, `CASH`, con su clave estable en
 *   inglés. Su `name` queda nulo hasta que la persona lo edite, y entonces el texto visible sale
 *   de los recursos traducibles de esa clave.
 * - `normalized_name` es único solo entre las cuentas vigentes, y lo impone su guarda: una
 *   archivada no reserva su nombre. Un índice único no sabe de esa excepción, así que el índice
 *   solo busca. Una cuenta sin nombre propio no choca con nadie.
 * - `currency_code` y `currency_decimals` son la moneda de la cuenta, que puede no ser la del
 *   negocio. Con movimientos ya no cambian, por su guarda: sus montos quedarían en otra moneda.
 * - `balance` es el saldo en la unidad menor de esa moneda. Es un saldo derivado de su libro: lo
 *   escribe solo la transacción que agrega el movimiento, con su `balance_after`.
 *
 * Con movimientos ya no se borra: su llave `RESTRICT` lo impide, y se archiva.
 */
@Entity(
    tableName = "money_accounts",
    indices = [
        Index(value = ["system_key"], unique = true),
        Index(value = ["normalized_name"]),
    ],
)
internal data class MoneyAccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "system_key") val systemKey: String?,
    @ColumnInfo(name = "name") val name: String?,
    @ColumnInfo(name = "normalized_name") val normalizedName: String?,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "currency_code") val currencyCode: String,
    @ColumnInfo(name = "currency_decimals") val currencyDecimals: Long,
    @ColumnInfo(name = "balance") val balance: Long,
    @ColumnInfo(name = "archived_at") val archivedAt: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

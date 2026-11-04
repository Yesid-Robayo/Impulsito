package io.github.yesidrobayo.shopledger.data.local.cash

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import io.github.yesidrobayo.shopledger.data.local.money.MoneyAccountEntity

/**
 * Una sesión de caja sobre una cuenta de dinero: se abre, se cuenta y se cierra. Es opcional: sin
 * sesión abierta, una venta nunca se bloquea.
 *
 * - Hay como máximo una sesión `OPEN` por cuenta, por su guarda. Una sesión cerrada ya no cambia,
 *   así que tampoco se reabre.
 * - `opening_amount` es lo que había al abrirla, y no cambia. Los montos van en la unidad menor de
 *   la moneda de la cuenta.
 * - Al cerrar se escriben juntos `expected_amount`, `counted_amount`, `difference_amount` y
 *   `closed_at`: la diferencia es lo contado menos lo esperado, y si no es 0 va un movimiento de
 *   diferencia de caja en el libro de dinero.
 * - La cuenta usa `RESTRICT`: una cuenta con sesiones no se borra. Una sesión tampoco: la impiden
 *   los movimientos y las ventas que apuntan a ella.
 *
 * El índice de `(money_account_id, status)` sirve a la llave y encuentra la sesión abierta.
 */
@Entity(
    tableName = "cash_sessions",
    foreignKeys = [
        ForeignKey(
            entity = MoneyAccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["money_account_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index(value = ["money_account_id", "status"])],
)
internal data class CashSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "money_account_id") val moneyAccountId: Long,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "opening_amount") val openingAmount: Long,
    @ColumnInfo(name = "expected_amount") val expectedAmount: Long?,
    @ColumnInfo(name = "counted_amount") val countedAmount: Long?,
    @ColumnInfo(name = "difference_amount") val differenceAmount: Long?,
    @ColumnInfo(name = "opened_at") val openedAt: Long,
    @ColumnInfo(name = "closed_at") val closedAt: Long?,
    @ColumnInfo(name = "business_date") val businessDate: String,
    @ColumnInfo(name = "note") val note: String?,
)

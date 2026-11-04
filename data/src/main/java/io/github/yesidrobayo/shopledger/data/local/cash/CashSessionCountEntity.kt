package io.github.yesidrobayo.shopledger.data.local.cash

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * Cuántas piezas de una denominación se contaron al abrir o al cerrar una sesión. No cambia ni se
 * borra: un conteo es la constancia de lo que había.
 *
 * - `phase` es `OPENING` o `CLOSING`. Hay una fila por sesión, fase y denominación: el índice
 *   único lo asegura.
 * - `count` es la cantidad de piezas, un entero.
 * - Sesión y denominación usan `RESTRICT`. El índice de `denomination_id` sirve a su llave: una
 *   denominación sin conteos se puede borrar sin recorrer la tabla.
 *
 * Lo contado es la suma de `value × count`. No se calcula en SQL: con los topes de las dos
 * columnas el producto puede no caber en un entero, y SQLite lo convertiría en real. Lo calcula el
 * dominio, con multiplicación exacta.
 */
@Entity(
    tableName = "cash_session_counts",
    foreignKeys = [
        ForeignKey(
            entity = CashSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["cash_session_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = CashDenominationEntity::class,
            parentColumns = ["id"],
            childColumns = ["denomination_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["cash_session_id", "phase", "denomination_id"], unique = true),
        Index(value = ["denomination_id"]),
    ],
)
internal data class CashSessionCountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "cash_session_id") val cashSessionId: Long,
    @ColumnInfo(name = "phase") val phase: String,
    @ColumnInfo(name = "denomination_id") val denominationId: Long,
    @ColumnInfo(name = "count") val count: Long,
)

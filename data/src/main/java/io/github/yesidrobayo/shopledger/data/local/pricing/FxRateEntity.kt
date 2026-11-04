package io.github.yesidrobayo.shopledger.data.local.pricing

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * Una cotización que escribió la persona para su moneda de referencia. Es un libro: nunca se edita
 * ni se borra, y una cotización nueva es otra fila. La app nunca la descarga ni la recalcula sola,
 * y guardarla no cambia ningún precio.
 *
 * - `currency_code` es el código ISO 4217 de la otra moneda, en tres mayúsculas.
 * - `rate_micro` son unidades enteras de la moneda del negocio por una unidad entera de la otra,
 *   multiplicadas por 1.000.000: siempre mayor que cero.
 * - `occurred_at` y `business_date` dicen cuándo vale, y la vigente es la de `occurred_at` más
 *   reciente, con el `id` de desempate. Por eso el índice es `(currency_code, occurred_at)`.
 *
 * Solo `Long` y `String`, y sin valores por defecto en las columnas: los pone el código.
 */
@Entity(
    tableName = "fx_rates",
    indices = [Index(value = ["currency_code", "occurred_at"])],
)
internal data class FxRateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "currency_code") val currencyCode: String,
    @ColumnInfo(name = "rate_micro") val rateMicro: Long,
    @ColumnInfo(name = "note") val note: String?,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    @ColumnInfo(name = "business_date") val businessDate: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

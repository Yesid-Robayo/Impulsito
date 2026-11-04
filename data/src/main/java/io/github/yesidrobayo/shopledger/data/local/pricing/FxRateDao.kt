package io.github.yesidrobayo.shopledger.data.local.pricing

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Las cotizaciones manuales: agregar y leer, nada más.
 *
 * No hay función que edite ni borre una cotización, y no debe haberla: el historial dice con qué
 * cotización se repreció cada vez. La inserción no usa `REPLACE`, que borraría la fila que choca.
 */
@Dao
internal interface FxRateDao {
    /** La vigente de esa moneda: la de instante más reciente y, a igual instante, la última. */
    @Query(LATEST_SQL)
    suspend fun findLatest(currencyCode: String): FxRateEntity?

    /** La vigente de esa moneda, que vuelve a emitir cuando la persona escribe otra. */
    @Query(LATEST_SQL)
    fun observeLatest(currencyCode: String): Flow<FxRateEntity?>

    /** El historial de esa moneda, de la más reciente a la más vieja. */
    @Query(
        "SELECT * FROM fx_rates WHERE currency_code = :currencyCode " +
            "ORDER BY occurred_at DESC, id DESC",
    )
    fun observeByCurrency(currencyCode: String): Flow<List<FxRateEntity>>

    /** Agrega la cotización y devuelve su id. */
    @Insert
    suspend fun insert(rate: FxRateEntity): Long
}

/** La consulta de la última cotización del modelo de datos, con la fila entera. */
private const val LATEST_SQL =
    "SELECT * FROM fx_rates WHERE currency_code = :currencyCode " +
        "ORDER BY occurred_at DESC, id DESC LIMIT 1"

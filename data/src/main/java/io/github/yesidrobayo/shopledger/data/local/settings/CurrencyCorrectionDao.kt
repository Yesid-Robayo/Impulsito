package io.github.yesidrobayo.shopledger.data.local.settings

import androidx.room3.Dao
import androidx.room3.Query

/**
 * Lo que lee y escribe la corrección de la moneda del negocio: cambiar su etiqueta, y la de las
 * cuentas que la seguían, sin tocar ningún monto.
 *
 * Va aparte de [AppSettingsDao] porque toca dos tablas, y porque solo la usa un caso de uso. Las
 * guardas de `app_settings` y `money_accounts` aceptan este cambio solo con los mismos decimales.
 */
@Dao
internal interface CurrencyCorrectionDao {
    /** Cuántas cuentas de dinero, vigentes o archivadas, llevan [code]. */
    @Query("SELECT COUNT(*) FROM money_accounts WHERE currency_code = :code")
    suspend fun accountsIn(code: String): Long

    /**
     * Si algún cobro, compra o cotización manual guardó [code] como otra moneda, distinta de la
     * del negocio.
     */
    @Query(
        "SELECT EXISTS (SELECT 1 FROM sale_payments WHERE tender_currency_code = :code) " +
            "OR EXISTS (SELECT 1 FROM purchases WHERE fx_currency_code = :code) " +
            "OR EXISTS (SELECT 1 FROM fx_rates WHERE currency_code = :code)",
    )
    suspend fun usedAsOtherCurrency(code: String): Boolean

    /**
     * Cambia la moneda del negocio de [oldCode] a [newCode], solo si sigue en [oldCode] con
     * [decimals] y ya está bloqueada. No toca los decimales, el bloqueo ni el símbolo. Devuelve 1
     * si la cambió.
     */
    @Query(
        "UPDATE app_settings SET currency_code = :newCode, updated_at = :updatedAt " +
            "WHERE id = 1 AND currency_code = :oldCode AND currency_decimals = :decimals " +
            "AND currency_locked_at IS NOT NULL",
    )
    suspend fun relabelBusiness(
        oldCode: String,
        newCode: String,
        decimals: Long,
        updatedAt: Long,
    ): Int

    /**
     * Lleva a [newCode] las cuentas que estaban en [oldCode] con [decimals], vigentes o
     * archivadas, sin tocar su saldo. Devuelve cuántas cambió.
     */
    @Query(
        "UPDATE money_accounts SET currency_code = :newCode, updated_at = :updatedAt " +
            "WHERE currency_code = :oldCode AND currency_decimals = :decimals",
    )
    suspend fun relabelAccounts(
        oldCode: String,
        newCode: String,
        decimals: Long,
        updatedAt: Long,
    ): Int
}

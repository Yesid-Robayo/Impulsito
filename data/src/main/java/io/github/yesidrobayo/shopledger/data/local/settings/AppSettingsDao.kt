package io.github.yesidrobayo.shopledger.data.local.settings

import androidx.room3.Dao
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Lecturas y escrituras de la única fila de `app_settings`.
 *
 * No hay inserción ni borrado: la fila nace con la base y dura lo que ella. Cada escritura cambia
 * solo sus columnas y `updated_at`, con un `UPDATE`: si dos pantallas cambian dos ajustes a la
 * vez, ninguna pisa la otra con un valor viejo, como pasaría al reescribir la fila entera. Todas
 * devuelven las filas cambiadas: 0 significa que falta la fila.
 *
 * El perfil del negocio cuenta como un cambio sin respaldar y se escribe dentro de la transacción
 * del caso de uso. Las preferencias y el fin del primer uso no cuentan, y se escriben fuera de
 * ella.
 */
@Dao
internal interface AppSettingsDao {
    /** Los ajustes, de nuevo cada vez que cambian. Nulo solo si falta la fila. */
    @Query("SELECT * FROM app_settings WHERE id = 1")
    fun observe(): Flow<AppSettingsEntity?>

    @Query("SELECT * FROM app_settings WHERE id = 1")
    suspend fun get(): AppSettingsEntity?

    /** Cambia el nombre del negocio y su margen objetivo. */
    @Query(
        "UPDATE app_settings SET business_name = :businessName, " +
            "default_target_margin_bp = :defaultTargetMarginBp, updated_at = :updatedAt " +
            "WHERE id = 1",
    )
    suspend fun updateProfile(
        businessName: String?,
        defaultTargetMarginBp: Long,
        updatedAt: Long,
    ): Int

    @Query("UPDATE app_settings SET theme_mode = :themeMode, updated_at = :updatedAt WHERE id = 1")
    suspend fun updateThemeMode(themeMode: String, updatedAt: Long): Int

    @Query(
        "UPDATE app_settings SET color_palette = :colorPalette, updated_at = :updatedAt WHERE id = 1",
    )
    suspend fun updateColorPalette(colorPalette: String, updatedAt: Long): Int

    @Query(
        "UPDATE app_settings SET low_stock_notifications_enabled = :enabled, " +
            "updated_at = :updatedAt WHERE id = 1",
    )
    suspend fun updateLowStockNotificationsEnabled(enabled: Long, updatedAt: Long): Int

    @Query(
        "UPDATE app_settings SET app_lock_enabled = :enabled, updated_at = :updatedAt WHERE id = 1",
    )
    suspend fun updateAppLockEnabled(enabled: Long, updatedAt: Long): Int

    @Query(
        "UPDATE app_settings SET app_lock_timeout_seconds = :seconds, updated_at = :updatedAt " +
            "WHERE id = 1",
    )
    suspend fun updateAppLockTimeoutSeconds(seconds: Long, updatedAt: Long): Int

    @Query(
        "UPDATE app_settings SET metered_backup_policy = :policy, updated_at = :updatedAt " +
            "WHERE id = 1",
    )
    suspend fun updateMeteredBackupPolicy(policy: String, updatedAt: Long): Int

    @Query(
        "UPDATE app_settings SET discreet_mode_enabled = :enabled, updated_at = :updatedAt " +
            "WHERE id = 1",
    )
    suspend fun updateDiscreetModeEnabled(enabled: Long, updatedAt: Long): Int

    /**
     * Cambia la moneda del negocio y sus decimales. Solo antes del bloqueo: después, la guarda lo
     * rechaza, porque cambiaría el valor de cada monto guardado. Va en la misma transacción que
     * escribe la misma moneda en la cuenta inicial `CASH`.
     */
    @Query(
        "UPDATE app_settings SET currency_code = :currencyCode, " +
            "currency_decimals = :currencyDecimals, updated_at = :updatedAt WHERE id = 1",
    )
    suspend fun updateCurrency(currencyCode: String, currencyDecimals: Long, updatedAt: Long): Int

    /**
     * Bloquea la moneda en [lockedAt], con el primer registro con dinero y en su misma
     * transacción. Solo la primera vez: con la moneda ya bloqueada no cambia nada y devuelve 0,
     * así que llamarla en cada registro es seguro. Sin moneda confirmada, la guarda lo rechaza.
     */
    @Query(
        "UPDATE app_settings SET currency_locked_at = :lockedAt, updated_at = :lockedAt " +
            "WHERE id = 1 AND currency_locked_at IS NULL",
    )
    suspend fun lockCurrency(lockedAt: Long): Int

    /**
     * Cambia la zona del negocio a [zoneId], un identificador IANA. Es un dato del negocio, así que
     * va dentro de la transacción del caso de uso y cuenta como cambio.
     *
     * Solo cambia esta columna: ninguna fecha de negocio ya escrita se recalcula, porque cada
     * registro guardó la suya con la zona vigente al escribirlo. Solo los registros nuevos usan la
     * zona nueva.
     */
    @Query(
        "UPDATE app_settings SET business_time_zone = :zoneId, updated_at = :updatedAt " +
            "WHERE id = 1",
    )
    suspend fun updateBusinessTimeZone(zoneId: String, updatedAt: Long): Int

    /**
     * Cambia el primer día de las semanas de los reportes, de 1, lunes, a 7, domingo. Como la zona,
     * es un dato del negocio y va dentro de la transacción del caso de uso. Solo cambia cómo se
     * agrupan los reportes: ninguna fila guarda su semana.
     */
    @Query(
        "UPDATE app_settings SET week_start_day = :weekStartDay, updated_at = :updatedAt " +
            "WHERE id = 1",
    )
    suspend fun updateWeekStartDay(weekStartDay: Long, updatedAt: Long): Int

    /**
     * Enciende o apaga las propinas del cobro. Es un dato del negocio: va dentro de la transacción
     * del caso de uso y cuenta como cambio.
     */
    @Query("UPDATE app_settings SET tips_enabled = :enabled, updated_at = :updatedAt WHERE id = 1")
    suspend fun updateTipsEnabled(enabled: Long, updatedAt: Long): Int

    /**
     * Cambia cómo se cobra el impuesto, la tasa por defecto y si el impuesto de compra es
     * recuperable. Es un dato del negocio: va dentro de la transacción del caso de uso y cuenta
     * como cambio. La llave de la tasa por defecto rechaza una que no exista.
     */
    @Query(
        "UPDATE app_settings SET tax_mode = :taxMode, default_tax_rate_id = :defaultTaxRateId, " +
            "purchase_tax_recoverable = :purchaseTaxRecoverable, updated_at = :updatedAt " +
            "WHERE id = 1",
    )
    suspend fun updateTaxSettings(
        taxMode: String,
        defaultTaxRateId: Long?,
        purchaseTaxRecoverable: Long,
        updatedAt: Long,
    ): Int

    /**
     * Cambia toda la configuración regional que confirmó la persona. Es un dato del negocio, así
     * que va dentro de la transacción del caso de uso y cuenta como cambio.
     *
     * Con la moneda bloqueada, la guarda rechaza otro código u otros decimales, y deja pasar los
     * mismos: así el resto de la configuración se guarda con una sola sentencia. No toca el
     * bloqueo ni la moneda de referencia, y ninguna fecha de negocio ya escrita se recalcula.
     */
    @Query(
        "UPDATE app_settings SET currency_code = :currencyCode, " +
            "currency_decimals = :currencyDecimals, currency_symbol = :currencySymbol, " +
            "cash_rounding = :cashRounding, format_locale = :formatLocale, " +
            "business_time_zone = :zoneId, week_start_day = :weekStartDay, " +
            "csv_separator = :csvSeparator, updated_at = :updatedAt WHERE id = 1",
    )
    suspend fun updateRegional(
        currencyCode: String,
        currencyDecimals: Long,
        currencySymbol: String?,
        cashRounding: Long,
        formatLocale: String,
        zoneId: String,
        weekStartDay: Long,
        csvSeparator: String,
        updatedAt: Long,
    ): Int

    /** Marca el fin del primer uso en [completedAt], que también queda como último cambio. */
    @Query(
        "UPDATE app_settings SET onboarding_completed_at = :completedAt, " +
            "updated_at = :completedAt WHERE id = 1",
    )
    suspend fun markOnboardingCompleted(completedAt: Long): Int
}

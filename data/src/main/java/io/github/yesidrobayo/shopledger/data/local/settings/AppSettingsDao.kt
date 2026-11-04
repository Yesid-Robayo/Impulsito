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

    /** Marca el fin del primer uso en [completedAt], que también queda como último cambio. */
    @Query(
        "UPDATE app_settings SET onboarding_completed_at = :completedAt, " +
            "updated_at = :completedAt WHERE id = 1",
    )
    suspend fun markOnboardingCompleted(completedAt: Long): Int
}

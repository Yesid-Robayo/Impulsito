package io.github.yesidrobayo.shopledger.data.local.settings

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * Los ajustes del negocio y de la app, en su única fila, de `id` 1, que la base crea al nacer.
 *
 * Viajan en la copia, porque son datos del negocio, y ninguno entra en la cabecera ni en los
 * metadatos de la copia. Ninguna columna supone un país: moneda, decimales, formato, zona y semana
 * llegan con la configuración regional, en sus propias columnas.
 *
 * - `default_target_margin_bp` es el margen objetivo del negocio, en puntos básicos.
 * - `theme_mode` y `metered_backup_policy` son los nombres guardados de `ThemeMode` y
 *   `MeteredBackupPolicy`, y `app_lock_timeout_seconds` los segundos de `AppLockTimeout`.
 * - `onboarding_completed_at` es nulo hasta que termina el primer uso.
 *
 * Booleanos en `Long`, 0 o 1. El `id` no se genera: siempre es 1. Sin valores por defecto en las
 * columnas: los iniciales los escribe el código al crear la base.
 */
@Entity(tableName = "app_settings")
internal data class AppSettingsEntity(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "business_name") val businessName: String?,
    @ColumnInfo(name = "default_target_margin_bp") val defaultTargetMarginBp: Long,
    @ColumnInfo(name = "low_stock_notifications_enabled") val lowStockNotificationsEnabled: Long,
    @ColumnInfo(name = "theme_mode") val themeMode: String,
    @ColumnInfo(name = "app_lock_enabled") val appLockEnabled: Long,
    @ColumnInfo(name = "app_lock_timeout_seconds") val appLockTimeoutSeconds: Long,
    @ColumnInfo(name = "metered_backup_policy") val meteredBackupPolicy: String,
    @ColumnInfo(name = "onboarding_completed_at") val onboardingCompletedAt: Long?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

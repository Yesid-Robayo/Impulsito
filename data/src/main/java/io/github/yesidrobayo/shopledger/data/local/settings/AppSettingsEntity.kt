package io.github.yesidrobayo.shopledger.data.local.settings

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.PrimaryKey
import androidx.room3.RoomWarnings
import io.github.yesidrobayo.shopledger.data.local.catalog.MediaAssetEntity
import io.github.yesidrobayo.shopledger.data.local.tax.TaxRateEntity

/**
 * Los ajustes del negocio y de la app, en su única fila, de `id` 1, que la base crea al nacer.
 *
 * Viajan en la copia, porque son datos del negocio, y ninguno entra en la cabecera ni en los
 * metadatos de la copia. Ninguna columna supone un país: moneda, decimales, formato, zona y semana
 * son datos que la persona confirma, y nada del código los fija.
 *
 * - `default_target_margin_bp` es el margen objetivo del negocio, en puntos básicos.
 * - `theme_mode`, `color_palette` y `metered_backup_policy` son los nombres guardados de
 *   `ThemeMode`, `ColorPalette` y `MeteredBackupPolicy`, y `app_lock_timeout_seconds` los segundos
 *   de `AppLockTimeout`. La paleta solo cambia colores, y no depende del tema.
 * - `onboarding_completed_at` es nulo hasta que termina el primer uso.
 * - `currency_code` es `XXX` hasta que la persona elige la moneda, y entonces nada con dinero se
 *   guarda. `currency_locked_at` se fija con el primer registro con dinero: desde ahí, ni el código
 *   ni `currency_decimals` cambian, y la guarda lo impide.
 * - Todo el dinero va en la unidad menor de `currency_code`: `cash_rounding`,
 *   `default_rounding_step`, los dos de puntos y `revenue_limit_amount`.
 * - `format_locale` es una etiqueta BCP 47 y `business_time_zone` un identificador IANA. Nacen con
 *   la región y la zona del sistema, nunca con el idioma de la app.
 * - `week_start_day` va de 1, lunes, a 7, domingo, como en ISO 8601, y `csv_separator` y `tax_mode`
 *   son los nombres guardados de `CsvSeparator` y `TaxMode`.
 * - `daily_summary_time` es `HH:mm`, en la zona del negocio.
 * - Al borrar la tasa por defecto o la imagen del logo, el ajuste queda sin ellas. Sin índices: es
 *   una sola fila, y SQLite la encuentra igual al borrar una tasa o una imagen.
 *
 * Booleanos en `Long`, 0 o 1. El `id` no se genera: siempre es 1. Sin valores por defecto en las
 * columnas: los iniciales los escribe el código al crear la base.
 */
@Suppress(RoomWarnings.MISSING_INDEX_ON_FOREIGN_KEY_CHILD)
@Entity(
    tableName = "app_settings",
    foreignKeys = [
        ForeignKey(
            entity = TaxRateEntity::class,
            parentColumns = ["id"],
            childColumns = ["default_tax_rate_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = MediaAssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["receipt_logo_media_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
)
internal data class AppSettingsEntity(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "business_name") val businessName: String?,
    @ColumnInfo(name = "default_target_margin_bp") val defaultTargetMarginBp: Long,
    @ColumnInfo(name = "low_stock_notifications_enabled") val lowStockNotificationsEnabled: Long,
    @ColumnInfo(name = "theme_mode") val themeMode: String,
    @ColumnInfo(name = "color_palette") val colorPalette: String,
    @ColumnInfo(name = "app_lock_enabled") val appLockEnabled: Long,
    @ColumnInfo(name = "app_lock_timeout_seconds") val appLockTimeoutSeconds: Long,
    @ColumnInfo(name = "metered_backup_policy") val meteredBackupPolicy: String,
    @ColumnInfo(name = "onboarding_completed_at") val onboardingCompletedAt: Long?,
    @ColumnInfo(name = "currency_code") val currencyCode: String,
    @ColumnInfo(name = "currency_decimals") val currencyDecimals: Long,
    @ColumnInfo(name = "currency_symbol") val currencySymbol: String?,
    @ColumnInfo(name = "currency_locked_at") val currencyLockedAt: Long?,
    @ColumnInfo(name = "cash_rounding") val cashRounding: Long,
    @ColumnInfo(name = "format_locale") val formatLocale: String,
    @ColumnInfo(name = "business_time_zone") val businessTimeZone: String,
    @ColumnInfo(name = "week_start_day") val weekStartDay: Long,
    @ColumnInfo(name = "csv_separator") val csvSeparator: String,
    @ColumnInfo(name = "tax_mode") val taxMode: String,
    @ColumnInfo(name = "default_tax_rate_id") val defaultTaxRateId: Long?,
    @ColumnInfo(name = "purchase_tax_recoverable") val purchaseTaxRecoverable: Long,
    @ColumnInfo(name = "default_rounding_step") val defaultRoundingStep: Long,
    @ColumnInfo(name = "tips_enabled") val tipsEnabled: Long,
    @ColumnInfo(name = "reference_currency_code") val referenceCurrencyCode: String?,
    @ColumnInfo(name = "reference_currency_decimals") val referenceCurrencyDecimals: Long?,
    @ColumnInfo(name = "cost_stale_days") val costStaleDays: Long,
    @ColumnInfo(name = "expiry_notice_days") val expiryNoticeDays: Long,
    @ColumnInfo(name = "loyalty_enabled") val loyaltyEnabled: Long,
    @ColumnInfo(name = "loyalty_amount_per_point") val loyaltyAmountPerPoint: Long?,
    @ColumnInfo(name = "loyalty_point_value") val loyaltyPointValue: Long?,
    @ColumnInfo(name = "revenue_limit_amount") val revenueLimitAmount: Long?,
    @ColumnInfo(name = "revenue_limit_months") val revenueLimitMonths: Long?,
    @ColumnInfo(name = "daily_summary_time") val dailySummaryTime: String?,
    @ColumnInfo(name = "receipt_logo_media_id") val receiptLogoMediaId: Long?,
    @ColumnInfo(name = "receipt_business_details") val receiptBusinessDetails: String?,
    @ColumnInfo(name = "receipt_footer_text") val receiptFooterText: String?,
    @ColumnInfo(name = "fiscal_portal_url") val fiscalPortalUrl: String?,
    @ColumnInfo(name = "discreet_mode_enabled") val discreetModeEnabled: Long,
    @ColumnInfo(name = "media_on_metered") val mediaOnMetered: Long,
    @ColumnInfo(name = "update_check_enabled") val updateCheckEnabled: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

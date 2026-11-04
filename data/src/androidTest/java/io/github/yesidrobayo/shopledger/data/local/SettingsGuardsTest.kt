package io.github.yesidrobayo.shopledger.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yesidrobayo.shopledger.data.local.catalog.LATER
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las sondas de las guardas de las dos filas únicas: los ajustes y el estado del respaldo. Cada
 * guarda rechaza lo que debe, con su mensaje, y deja pasar lo válido.
 *
 * Para probar la inserción, cada prueba guarda aparte la fila que la base escribió al nacer,
 * retira la guarda de borrado, la borra y la vuelve a insertar: esa fila pasa por su guarda, y la
 * misma fila con una sola columna rota no pasa.
 */
@RunWith(AndroidJUnit4::class)
internal class SettingsGuardsTest : GuardProbeTest() {
    @Test
    fun theSettingsRowIsTheOnlyOneAndEnteredThroughItsGuard() = runBlocking {
        rejected(SETTINGS, "INSERT INTO app_settings SELECT 2, $SETTINGS_COLUMNS FROM app_settings")
        setAside("app_settings")

        for (broken in listOf(
            "id = 2",
            "color_palette = 'NEON'",
            "theme_mode = NULL",
            "default_target_margin_bp = 3000.5",
            "app_lock_timeout_seconds = 30",
            "business_name = ''",
            "updated_at = 'ayer'",
            "currency_code = 'xts'",
            "currency_code = 'XÉS'",
            "currency_decimals = 5",
            "currency_symbol = 'SSSSSSSSS'",
            "format_locale = ''",
            "business_time_zone = ''",
            "week_start_day = 0",
            "csv_separator = 'PIPE'",
            "tax_mode = 'VAT'",
            "cash_rounding = -1",
            "daily_summary_time = '24:00'",
            "daily_summary_time = '7:30'",
            "update_check_enabled = 2",
            // Lo que va en pareja no entra a medias.
            "loyalty_enabled = 1",
            "reference_currency_code = 'XTT'",
            "revenue_limit_months = 12",
        )) {
            restoreAside("app_settings")
            execute("UPDATE aside SET $broken")
            rejected(SETTINGS, "INSERT INTO app_settings SELECT * FROM aside")
        }

        restoreAside("app_settings")
        execute("INSERT INTO app_settings SELECT * FROM aside")
        assertEquals(1L, count("SELECT COUNT(*) FROM app_settings"))
    }

    @Test
    fun settingsChangeOnlyWithinTheirValues() = runBlocking {
        for (broken in listOf(
            "color_palette" to "NEON",
            "color_palette" to null,
            "theme_mode" to "SEPIA",
            "low_stock_notifications_enabled" to 2L,
            "default_target_margin_bp" to 9_901L,
            "default_target_margin_bp" to 3_000.5,
            "metered_backup_policy" to "ALWAYS",
            "business_name" to "N".repeat(81),
            "onboarding_completed_at" to "hoy",
            "id" to 2L,
            "format_locale" to "L".repeat(36),
            "business_time_zone" to "Z".repeat(65),
            "week_start_day" to 8L,
            "tax_mode" to null,
            "purchase_tax_recoverable" to 2L,
            "default_rounding_step" to 1_000_000_000_001L,
            "tips_enabled" to -1L,
            "cost_stale_days" to 36_501L,
            "expiry_notice_days" to -1L,
            "receipt_business_details" to "D".repeat(301),
            "receipt_footer_text" to "",
            "fiscal_portal_url" to "U".repeat(301),
            "discreet_mode_enabled" to 2L,
            "media_on_metered" to 2L,
            "daily_summary_time" to "23:60",
        )) {
            rejected(SETTINGS, update("app_settings", 1, broken))
        }
        for (broken in listOf(
            arrayOf("reference_currency_code" to "xtt", "reference_currency_decimals" to 2L),
            arrayOf("reference_currency_code" to "XTT", "reference_currency_decimals" to 5L),
            arrayOf("reference_currency_code" to "XTT", "reference_currency_decimals" to null),
            arrayOf("loyalty_enabled" to 1L, "loyalty_amount_per_point" to 1_000L),
            arrayOf(
                "loyalty_enabled" to 1L,
                "loyalty_amount_per_point" to 0L,
                "loyalty_point_value" to 10L,
            ),
            arrayOf("revenue_limit_amount" to 5_000_000L, "revenue_limit_months" to 121L),
            arrayOf("revenue_limit_amount" to 0L, "revenue_limit_months" to 12L),
        )) {
            rejected(SETTINGS, update("app_settings", 1, *broken))
        }

        assertEquals(1, database.appSettingsDao().updateColorPalette("VIVID_RED_CREAM", LATER))
        execute(update("app_settings", 1, "business_name" to "N".repeat(80)))
        assertEquals("VIVID_RED_CREAM", database.appSettingsDao().get()?.colorPalette)
        // Los mismos ajustes, dentro de sus valores, sí entran.
        execute(
            update(
                "app_settings",
                1,
                "week_start_day" to 7L,
                "csv_separator" to "TAB",
                "tax_mode" to "ADDED",
                "reference_currency_code" to "XTT",
                "reference_currency_decimals" to 4L,
                "loyalty_enabled" to 1L,
                "loyalty_amount_per_point" to 1_000L,
                "loyalty_point_value" to 10L,
                "revenue_limit_amount" to 5_000_000L,
                "revenue_limit_months" to 12L,
                "daily_summary_time" to "23:59",
                "fiscal_portal_url" to "U".repeat(300),
            ),
        )
        assertEquals("ADDED", database.appSettingsDao().get()?.taxMode)
    }

    @Test
    fun theSettingsRowIsNeverDeleted() = runBlocking {
        rejected("guard:app_settings_no_delete", "DELETE FROM app_settings")

        assertEquals(1L, count("SELECT COUNT(*) FROM app_settings"))
    }

    @Test
    fun theBackupStateRowIsTheOnlyOneAndEnteredThroughItsGuard() = runBlocking {
        setAside("backup_state")

        for (broken in listOf(
            "id = 2",
            "verified_seq = 1",
            "change_seq = -1",
            "link_state = 'ONLINE'",
            "failure_code = 'DISK_ON_FIRE'",
            "head_generation = 0.5",
            "destination_kind = 'FTP'",
        )) {
            restoreAside("backup_state")
            execute("UPDATE aside SET $broken")
            rejected(BACKUP, "INSERT INTO backup_state SELECT * FROM aside")
        }

        restoreAside("backup_state")
        execute("INSERT INTO backup_state SELECT * FROM aside")
        assertEquals(1L, count("SELECT COUNT(*) FROM backup_state"))
    }

    @Test
    fun changeSeqNeverGoesDownAndVerifiedNeverPassesIt() = runBlocking {
        assertEquals(1, database.backupStateDao().markChanged(LATER))
        assertEquals(1, database.backupStateDao().markChanged(LATER))

        for (broken in listOf(
            arrayOf("change_seq" to 1L),
            arrayOf("verified_seq" to 3L),
            arrayOf("change_seq" to null),
            arrayOf("pending_sha256" to "a".repeat(65)),
            arrayOf("conflict_kind" to "LOST"),
            arrayOf("last_drill_result" to "MAYBE"),
            arrayOf("consecutive_failures" to -1L),
        )) {
            rejected(BACKUP, update("backup_state", 1, *broken))
        }

        // Verificar todo lo cambiado sí pasa, y el contador sigue subiendo.
        execute(update("backup_state", 1, "verified_seq" to 2L))
        assertEquals(1, database.backupStateDao().markChanged(LATER))
        assertEquals(3L, database.backupStateDao().get()?.changeSeq)
    }

    @Test
    fun theBackupStateRowIsNeverDeleted() = runBlocking {
        rejected("guard:backup_state_no_delete", "DELETE FROM backup_state")

        assertEquals(1L, count("SELECT COUNT(*) FROM backup_state"))
    }

    /** Guarda la fila única de [table] en `aside`, retira su guarda de borrado y la borra. */
    private suspend fun setAside(table: String) {
        execute("CREATE TABLE aside_original AS SELECT * FROM $table")
        execute("DROP TRIGGER guard_${table}_no_delete")
        execute("DELETE FROM $table")
    }

    /** Deja en `aside` la fila original, sin los cambios de la sonda anterior. */
    private suspend fun restoreAside(table: String) {
        execute("DROP TABLE IF EXISTS aside")
        execute("CREATE TABLE aside AS SELECT * FROM aside_original")
        assertEquals(0L, count("SELECT COUNT(*) FROM $table"))
    }

    private companion object {
        const val SETTINGS = "guard:app_settings"
        const val BACKUP = "guard:backup_state"

        /** Las columnas de los ajustes después de `id`, en su orden. */
        const val SETTINGS_COLUMNS =
            "business_name, default_target_margin_bp, low_stock_notifications_enabled, " +
                "theme_mode, color_palette, app_lock_enabled, app_lock_timeout_seconds, " +
                "metered_backup_policy, onboarding_completed_at, currency_code, " +
                "currency_decimals, currency_symbol, currency_locked_at, cash_rounding, " +
                "format_locale, business_time_zone, week_start_day, csv_separator, tax_mode, " +
                "default_tax_rate_id, purchase_tax_recoverable, default_rounding_step, " +
                "tips_enabled, reference_currency_code, reference_currency_decimals, " +
                "cost_stale_days, expiry_notice_days, loyalty_enabled, " +
                "loyalty_amount_per_point, loyalty_point_value, revenue_limit_amount, " +
                "revenue_limit_months, daily_summary_time, receipt_logo_media_id, " +
                "receipt_business_details, receipt_footer_text, fiscal_portal_url, " +
                "discreet_mode_enabled, media_on_metered, update_check_enabled, updated_at"
    }
}

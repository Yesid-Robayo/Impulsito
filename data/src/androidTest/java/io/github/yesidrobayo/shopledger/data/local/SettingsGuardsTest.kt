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
        )) {
            rejected(SETTINGS, update("app_settings", 1, broken))
        }

        assertEquals(1, database.appSettingsDao().updateColorPalette("VIVID_RED_CREAM", LATER))
        execute(update("app_settings", 1, "business_name" to "N".repeat(80)))
        assertEquals("VIVID_RED_CREAM", database.appSettingsDao().get()?.colorPalette)
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
                "metered_backup_policy, onboarding_completed_at, updated_at"
    }
}

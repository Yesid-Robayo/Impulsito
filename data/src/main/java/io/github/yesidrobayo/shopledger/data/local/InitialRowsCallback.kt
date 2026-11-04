package io.github.yesidrobayo.shopledger.data.local

import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteConnection
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.settings.AppLockTimeout
import io.github.yesidrobayo.shopledger.domain.model.settings.MeteredBackupPolicy
import io.github.yesidrobayo.shopledger.domain.model.settings.ThemeMode
import io.github.yesidrobayo.shopledger.domain.port.Clock

/**
 * Crea, al nacer la base, las filas que la app necesita desde la primera pantalla: la única de
 * `app_settings` y la única de `backup_state`, las dos con `id` 1.
 *
 * Room llama a [onCreate] solo cuando crea la base, dentro de la misma transacción exclusiva en
 * que crea las tablas y antes de fijar la versión: si una inserción falla, no queda ni la base a
 * medias ni una versión que diga que está completa, y la próxima apertura la crea de nuevo. Una
 * base que ya existía, como una restaurada o una migrada, nunca pasa por aquí.
 *
 * Los valores iniciales son los del modelo de datos, sin nada de un país ni de un idioma: la
 * configuración regional llega en sus propias columnas, y las categorías y unidades iniciales, con
 * sus textos traducibles, llegan con sus tablas. [clock] da el instante de `updated_at`.
 */
internal class InitialRowsCallback(private val clock: Clock) : RoomDatabase.Callback() {
    override suspend fun onCreate(connection: SQLiteConnection) {
        connection.prepare(INSERT_APP_SETTINGS_SQL).use { statement ->
            statement.bindLong(1, clock.nowEpochMillis())
            statement.step()
        }
        connection.prepare(INSERT_BACKUP_STATE_SQL).use { it.step() }
    }
}

/**
 * La fila inicial de `app_settings`: sin nombre del negocio, con el margen objetivo inicial, avisos
 * de stock encendidos, tema del sistema, sin bloqueo pero con un minuto de espera para cuando se
 * active, una copia como mucho cada hora con datos móviles y el primer uso sin terminar.
 */
private val INSERT_APP_SETTINGS_SQL =
    "INSERT INTO app_settings (id, business_name, default_target_margin_bp, " +
        "low_stock_notifications_enabled, theme_mode, app_lock_enabled, " +
        "app_lock_timeout_seconds, metered_backup_policy, onboarding_completed_at, updated_at) " +
        "VALUES (1, NULL, " +
        "${BasisPoints.DEFAULT_TARGET_MARGIN.bp}, 1, '${ThemeMode.SYSTEM.name}', 0, " +
        "${AppLockTimeout.ONE_MINUTE.seconds}, '${MeteredBackupPolicy.EVERY_60_MIN.name}', NULL, ?)"

/**
 * La fila inicial de `backup_state`: ningún cambio todavía, ningún destino elegido, sin fallos y
 * sin cabeza conocida. Lo demás queda nulo hasta que el respaldo lo escriba.
 */
private const val INSERT_BACKUP_STATE_SQL =
    "INSERT INTO backup_state (id, change_seq, verified_seq, link_state, consecutive_failures, " +
        "invariant_failures, head_generation) VALUES (1, 0, 0, 'NOT_CONNECTED', 0, 0, 0)"

package io.github.yesidrobayo.shopledger.data.local.guard

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Las guardas del esquema: los triggers `guard_*` que hacen cumplir en la base las reglas que Room
 * no sabe declarar, como los rangos, los enums, la unicidad entre vigentes y el libro que solo
 * crece. Cada una aborta con `RAISE(ABORT, 'guard:<regla>')`, un mensaje sin datos del negocio.
 *
 * Son la última línea de defensa: la validación que ve la persona vive en el dominio. Un error de
 * programación que intente editar el libro, guardar un real o anular sin motivo se detiene aquí.
 *
 * Este archivo es el catálogo: el texto de cada guarda es el del modelo de datos, letra por letra,
 * porque la copia y la restauración comparan las guardas por su texto exacto. Una tabla nueva suma
 * su bloque y una línea en [all]. Las guardas que esperan una tabla o una columna que todavía no
 * existe conservan aquí su texto completo, y declaran con [Awaiting] la parte que se instala
 * distinta mientras tanto: quien crea esa tabla o columna borra esa línea, y nada más.
 *
 * [sync] corre al crear la base y en cada apertura: deja en la base exactamente estas guardas.
 */
internal object SchemaGuards {
    /** Todas las guardas de esta versión del esquema, en el orden del modelo de datos. */
    val all: List<Guard> =
        listOf(
            APP_SETTINGS_GUARDS,
            BACKUP_STATE_GUARDS,
            CATEGORIES_GUARDS,
            SUPPLIERS_GUARDS,
            PRODUCTS_GUARDS,
            PRODUCT_PRICE_HISTORY_GUARDS,
            PRODUCT_COMPONENTS_GUARDS,
            CUSTOM_FIELD_DEFINITIONS_GUARDS,
            PRODUCT_CUSTOM_VALUES_GUARDS,
            TAGS_GUARDS,
            PRODUCT_TAGS_GUARDS,
            MEDIA_ASSETS_GUARDS,
            UNITS_GUARDS,
            PRODUCT_UNITS_GUARDS,
            STOCK_MOVEMENTS_GUARDS,
            LOW_STOCK_ALERTS_GUARDS,
            PURCHASES_GUARDS,
            PURCHASE_LINES_GUARDS,
            SUPPLIER_LEDGER_ENTRIES_GUARDS,
            SALES_GUARDS,
            SALE_LINES_GUARDS,
            SALE_LINE_COMPONENTS_GUARDS,
            TAX_RATES_GUARDS,
            CUSTOMERS_GUARDS,
            CUSTOMER_LEDGER_ENTRIES_GUARDS,
            LOYALTY_LEDGER_ENTRIES_GUARDS,
            MESSAGE_TEMPLATES_GUARDS,
            MONEY_ACCOUNTS_GUARDS,
            MONEY_MOVEMENTS_GUARDS,
            CASH_SESSIONS_GUARDS,
            CASH_DENOMINATIONS_GUARDS,
            CASH_SESSION_COUNTS_GUARDS,
            EXPENSE_CATEGORIES_GUARDS,
            EXPENSES_GUARDS,
            PRICE_LISTS_GUARDS,
        ).flatten()

    private val byName: Map<String, Guard> = all.associateBy { it.name }

    init {
        require(byName.size == all.size) { "Hay dos guardas con el mismo nombre" }
    }

    /** La guarda de ese nombre. Falla si no existe: un nombre mal escrito es un error de código. */
    fun named(name: String): Guard = requireNotNull(byName[name]) { "No existe la guarda $name" }

    /**
     * Deja en la base exactamente las guardas de [guards]: crea las que faltan, recrea con el texto
     * del catálogo las que tienen otro texto y borra las `guard_*` que sobran. Los triggers que no
     * son guardas no se tocan.
     *
     * Es idempotente: con la base ya alineada solo lee `sqlite_master`, no escribe nada y el
     * esquema no cambia de versión.
     *
     * Todo ocurre en un solo punto de guardado: si una sentencia falla a mitad, se deshace entero y
     * la base queda con las guardas que tenía, sin ninguna retirada. El error sigue su camino, y
     * Room no entrega una base cuya apertura falló, así que nada escribe sin guardas. El punto de
     * guardado sirve dentro de la transacción en que Room crea la base, y fuera de ella, al abrir.
     *
     * Debe correr en la conexión de escritura. [guards] solo cambia en las pruebas.
     */
    fun sync(connection: SQLiteConnection, guards: List<Guard> = all): GuardSync {
        connection.execSQL("SAVEPOINT $SAVEPOINT")
        try {
            val result = alignGuards(connection, guards)
            connection.execSQL("RELEASE $SAVEPOINT")
            return result
        } catch (failure: Throwable) {
            // Primero se deshace y después se cierra el punto de guardado: así no queda nada a
            // medias, ni una transacción abierta si el punto de guardado la había empezado. Si
            // deshacer también falla, manda el error original.
            try {
                connection.execSQL("ROLLBACK TO $SAVEPOINT")
                connection.execSQL("RELEASE $SAVEPOINT")
            } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    /** Las guardas que hay en la base, con el texto que guardó SQLite, por nombre. */
    fun installed(connection: SQLiteConnection): Map<String, String> =
        connection.prepare(INSTALLED_GUARDS_SQL).use { statement ->
            buildMap {
                while (statement.step()) put(statement.getText(0), statement.getText(1))
            }
        }

    /** Crea [guard] con el texto del catálogo. Falla si ya existe una con su nombre. */
    fun create(connection: SQLiteConnection, guard: Guard) {
        connection.execSQL(guard.sql)
    }

    /** Retira la guarda [name]. Falla si no existe. */
    fun drop(connection: SQLiteConnection, name: String) {
        connection.execSQL("DROP TRIGGER ${quoted(name)}")
    }

    private fun alignGuards(connection: SQLiteConnection, guards: List<Guard>): GuardSync {
        val present = installed(connection)
        val wanted = guards.associateBy { it.name }
        val dropped = present.keys.filter { it !in wanted }
        val replaced =
            guards.filter { guard -> present[guard.name]?.let { !guard.matches(it) } ?: false }
        val created = guards.filter { it.name !in present }
        for (name in dropped) drop(connection, name)
        for (guard in replaced) {
            drop(connection, guard.name)
            create(connection, guard)
        }
        for (guard in created) create(connection, guard)
        return GuardSync(
            created = created.map { it.name },
            replaced = replaced.map { it.name },
            dropped = dropped,
        )
    }

    /** Un nombre leído de la base va entre comillas dobles, con las suyas duplicadas. */
    private fun quoted(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

    private const val SAVEPOINT = "schema_guards_sync"

    // GLOB distingue mayúsculas y toma el guion bajo como letra, a diferencia de LIKE.
    private const val INSTALLED_GUARDS_SQL =
        "SELECT name, sql FROM sqlite_master WHERE type = 'trigger' AND name GLOB 'guard_*'"
}

/**
 * Una guarda del catálogo.
 *
 * [catalogSql] es su texto en el modelo de datos, y [sql] el que se instala: son iguales salvo
 * mientras la guarda espera una tabla o una columna que todavía no existe, como dice [awaiting].
 */
internal class Guard(
    val name: String,
    val table: String,
    val catalogSql: String,
    val sql: String,
    val awaiting: List<Awaiting> = emptyList(),
) {
    /**
     * Si [stored], el texto que guardó SQLite, es el de esta guarda. SQLite guarda la sentencia sin
     * el punto y coma final.
     */
    fun matches(stored: String): Boolean = stored == sql.removeSuffix(";")

    override fun toString(): String = name
}

/** Lo que cambió [SchemaGuards.sync], por nombre de guarda. Todo vacío: la base ya estaba bien. */
internal data class GuardSync(
    val created: List<String>,
    val replaced: List<String>,
    val dropped: List<String>,
) {
    val changedNothing: Boolean get() = created.isEmpty() && replaced.isEmpty() && dropped.isEmpty()
}

/**
 * La parte de una guarda que espera una tabla o una columna que esta versión todavía no tiene. Sin
 * ella, la guarda fallaría con cada escritura de su tabla, porque SQLite resuelve los nombres al
 * correrla, no al crearla.
 *
 * [waitsFor] nombra lo que falta, como `tabla` o `tabla.columna`: una prueba exige que siga
 * faltando, para que nadie cree la tabla o la columna y olvide devolver a la guarda su texto.
 */
internal sealed interface Awaiting {
    /** El nombre de la guarda que cambia. */
    val guard: String

    val waitsFor: List<String>

    fun applyTo(sql: String): String

    /**
     * [text], que aparece una sola vez en la guarda, se instala como [replacement]. Sin reemplazo,
     * se quita.
     */
    class Part(
        override val guard: String,
        val text: String,
        override val waitsFor: List<String>,
        val replacement: String = "",
    ) : Awaiting {
        override fun applyTo(sql: String): String {
            require(sql.split(text).size == 2) { "$guard no tiene una vez la parte que espera" }
            return sql.replace(text, replacement)
        }
    }

    /**
     * La única línea de la guarda que contiene [marker] se instala como [replacement]. Sin
     * reemplazo, se quita.
     */
    class Line(
        override val guard: String,
        val marker: String,
        override val waitsFor: List<String>,
        val replacement: String? = null,
    ) : Awaiting {
        override fun applyTo(sql: String): String {
            val lines = sql.lines()
            val index = lines.indexOfFirst { marker in it }
            require(index >= 0 && lines.count { marker in it } == 1) {
                "$guard no tiene una sola línea con la parte que espera"
            }
            val kept = lines.toMutableList()
            if (replacement == null) kept.removeAt(index) else kept[index] = replacement
            return kept.joinToString("\n")
        }
    }

    /**
     * Se quitan las líneas de la guarda que nombran alguna de [columns] de [table], como
     * `NEW.<columna>` u `OLD.<columna>`. Cada columna debe aparecer: si no, la lista ya no es la de
     * la guarda.
     */
    class Columns(override val guard: String, val table: String, val columns: List<String>) :
        Awaiting {
        override val waitsFor: List<String> get() = columns.map { "$table.$it" }

        override fun applyTo(sql: String): String {
            val lines = sql.lines()
            for (column in columns) {
                require(lines.any { mentions(it, column) }) { "$guard no nombra $column" }
            }
            return lines.filterNot { line -> columns.any { mentions(line, it) } }.joinToString("\n")
        }

        private fun mentions(line: String, column: String): Boolean =
            Regex("""\b(NEW|OLD)\.$column\b""").containsMatchIn(line)
    }
}

/**
 * Las guardas de una tabla, con el texto del modelo de datos tal cual: cada sentencia
 * `CREATE TRIGGER`, separada de la siguiente por una línea vacía. [awaiting] dice qué parte se
 * instala distinta mientras falta lo que nombra.
 */
private fun table(sql: String, vararg awaiting: Awaiting): List<Guard> {
    val statements = sql.trimIndent().split("\n\n")
    val guards =
        statements.map { statement ->
            val header =
                requireNotNull(GUARD_HEADER.find(statement)) { "Sentencia que no es una guarda" }
            val name = header.groupValues[1]
            require(statement.endsWith("END;")) { "La guarda $name no termina" }
            val parts = awaiting.filter { it.guard == name }
            Guard(
                name,
                table = header.groupValues[2],
                catalogSql = statement,
                sql = parts.fold(statement) { text, part -> part.applyTo(text) },
                awaiting = parts,
            )
        }
    val names = guards.map { it.name }.toSet()
    for (part in awaiting) require(part.guard in names) { "No existe la guarda ${part.guard}" }
    return guards
}

/** La primera línea de una guarda: su nombre, y la tabla que protege. */
private val GUARD_HEADER =
    Regex("^CREATE TRIGGER (guard_[a-z0-9_]+) BEFORE (?:INSERT|UPDATE|DELETE) ON ([a-z_]+)\n")

/**
 * La condición de la moneda confirmada antes del primer registro con dinero, que espera la columna
 * que bloquea la moneda.
 */
private fun awaitingCurrencyLock(guard: String): Awaiting = Awaiting.Line(
    guard,
    "SELECT currency_locked_at FROM app_settings",
    listOf("app_settings.currency_locked_at"),
)

/** Las columnas de `app_settings` que sus guardas nombran y que todavía no existen. */
private val SETTINGS_COLUMNS_TO_COME =
    listOf(
        "currency_code",
        "currency_decimals",
        "currency_symbol",
        "currency_locked_at",
        "cash_rounding",
        "format_locale",
        "business_time_zone",
        "week_start_day",
        "csv_separator",
        "tax_mode",
        "purchase_tax_recoverable",
        "default_rounding_step",
        "tips_enabled",
        "reference_currency_code",
        "reference_currency_decimals",
        "cost_stale_days",
        "expiry_notice_days",
        "loyalty_enabled",
        "loyalty_amount_per_point",
        "loyalty_point_value",
        "revenue_limit_amount",
        "revenue_limit_months",
        "daily_summary_time",
        "receipt_business_details",
        "receipt_footer_text",
        "fiscal_portal_url",
        "discreet_mode_enabled",
        "media_on_metered",
        "update_check_enabled",
    )

/** Los ajustes: fila única, que nunca se borra.
 *
 * Sus columnas de moneda, formato regional, impuestos, propinas, fidelidad, comprobante y avisos
 * llegan juntas con la configuración regional: hasta entonces se instalan sin las líneas que las
 * nombran. */
private val APP_SETTINGS_GUARDS =
    table(
        """
        CREATE TRIGGER guard_app_settings_insert BEFORE INSERT ON app_settings
        WHEN NOT IFNULL((
          (NEW.business_name IS NULL OR (typeof(NEW.business_name) = 'text' AND length(NEW.business_name) BETWEEN 1 AND 80))
          AND typeof(NEW.default_target_margin_bp) = 'integer' AND NEW.default_target_margin_bp BETWEEN 0 AND 9900
          AND NEW.low_stock_notifications_enabled IN (0, 1)
          AND NEW.theme_mode IN ('SYSTEM', 'LIGHT', 'DARK')
          AND NEW.color_palette IN ('INDIGO_MUSTARD', 'BERRY_HONEY', 'RASPBERRY_GOLD', 'WINE_CHAMPAGNE', 'PLUM_CORAL', 'FOREST_HONEY', 'CARMINE_GOLD', 'GARNET_HONEY', 'VIVID_RED_CREAM')
          AND NEW.app_lock_enabled IN (0, 1)
          AND NEW.app_lock_timeout_seconds IN (0, 60, 300)
          AND NEW.metered_backup_policy IN ('EVERY_60_MIN', 'EVERY_30_MIN', 'WIFI_ONLY')
          AND (NEW.onboarding_completed_at IS NULL OR (typeof(NEW.onboarding_completed_at) = 'integer'))
          AND length(NEW.currency_code) = 3 AND NEW.currency_code = upper(NEW.currency_code) AND NEW.currency_code NOT GLOB '*[^A-Z]*'
          AND typeof(NEW.currency_decimals) = 'integer' AND NEW.currency_decimals BETWEEN 0 AND 4
          AND (NEW.currency_symbol IS NULL OR (typeof(NEW.currency_symbol) = 'text' AND length(NEW.currency_symbol) BETWEEN 1 AND 8))
          AND (NEW.currency_locked_at IS NULL OR (typeof(NEW.currency_locked_at) = 'integer'))
          AND typeof(NEW.cash_rounding) = 'integer' AND NEW.cash_rounding BETWEEN 0 AND 1000000000000
          AND typeof(NEW.format_locale) = 'text' AND length(NEW.format_locale) BETWEEN 1 AND 35
          AND typeof(NEW.business_time_zone) = 'text' AND length(NEW.business_time_zone) BETWEEN 1 AND 64
          AND typeof(NEW.week_start_day) = 'integer' AND NEW.week_start_day BETWEEN 1 AND 7
          AND NEW.csv_separator IN ('SEMICOLON', 'COMMA', 'TAB')
          AND NEW.tax_mode IN ('NONE', 'INCLUDED', 'ADDED')
          AND NEW.purchase_tax_recoverable IN (0, 1)
          AND typeof(NEW.default_rounding_step) = 'integer' AND NEW.default_rounding_step BETWEEN 0 AND 1000000000000
          AND NEW.tips_enabled IN (0, 1)
          AND (NEW.reference_currency_code IS NULL OR (length(NEW.reference_currency_code) = 3 AND NEW.reference_currency_code = upper(NEW.reference_currency_code) AND NEW.reference_currency_code NOT GLOB '*[^A-Z]*'))
          AND (NEW.reference_currency_decimals IS NULL OR (typeof(NEW.reference_currency_decimals) = 'integer' AND NEW.reference_currency_decimals BETWEEN 0 AND 4))
          AND typeof(NEW.cost_stale_days) = 'integer' AND NEW.cost_stale_days BETWEEN 0 AND 36500
          AND typeof(NEW.expiry_notice_days) = 'integer' AND NEW.expiry_notice_days BETWEEN 0 AND 36500
          AND NEW.loyalty_enabled IN (0, 1)
          AND (NEW.loyalty_amount_per_point IS NULL OR (typeof(NEW.loyalty_amount_per_point) = 'integer' AND NEW.loyalty_amount_per_point BETWEEN 1 AND 1000000000000))
          AND (NEW.loyalty_point_value IS NULL OR (typeof(NEW.loyalty_point_value) = 'integer' AND NEW.loyalty_point_value BETWEEN 1 AND 1000000000000))
          AND (NEW.revenue_limit_amount IS NULL OR (typeof(NEW.revenue_limit_amount) = 'integer' AND NEW.revenue_limit_amount BETWEEN 1 AND 9000000000000000))
          AND (NEW.revenue_limit_months IS NULL OR (typeof(NEW.revenue_limit_months) = 'integer' AND NEW.revenue_limit_months BETWEEN 1 AND 120))
          AND (NEW.daily_summary_time IS NULL OR (length(NEW.daily_summary_time) = 5 AND NEW.daily_summary_time GLOB '[0-2][0-9]:[0-5][0-9]' AND substr(NEW.daily_summary_time, 1, 2) <= '23'))
          AND (NEW.receipt_business_details IS NULL OR (typeof(NEW.receipt_business_details) = 'text' AND length(NEW.receipt_business_details) BETWEEN 1 AND 300))
          AND (NEW.receipt_footer_text IS NULL OR (typeof(NEW.receipt_footer_text) = 'text' AND length(NEW.receipt_footer_text) BETWEEN 1 AND 300))
          AND (NEW.fiscal_portal_url IS NULL OR (typeof(NEW.fiscal_portal_url) = 'text' AND length(NEW.fiscal_portal_url) BETWEEN 1 AND 300))
          AND NEW.discreet_mode_enabled IN (0, 1)
          AND NEW.media_on_metered IN (0, 1)
          AND NEW.update_check_enabled IN (0, 1)
          AND typeof(NEW.updated_at) = 'integer'
          AND NEW.id = 1
          AND (NEW.reference_currency_code IS NULL) = (NEW.reference_currency_decimals IS NULL)
          AND (NEW.loyalty_enabled = 0 OR (NEW.loyalty_amount_per_point IS NOT NULL AND NEW.loyalty_point_value IS NOT NULL))
          AND (NEW.revenue_limit_amount IS NULL) = (NEW.revenue_limit_months IS NULL)
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:app_settings'); END;

        CREATE TRIGGER guard_app_settings_update BEFORE UPDATE ON app_settings
        WHEN NOT IFNULL((
          (NEW.business_name IS NULL OR (typeof(NEW.business_name) = 'text' AND length(NEW.business_name) BETWEEN 1 AND 80))
          AND typeof(NEW.default_target_margin_bp) = 'integer' AND NEW.default_target_margin_bp BETWEEN 0 AND 9900
          AND NEW.low_stock_notifications_enabled IN (0, 1)
          AND NEW.theme_mode IN ('SYSTEM', 'LIGHT', 'DARK')
          AND NEW.color_palette IN ('INDIGO_MUSTARD', 'BERRY_HONEY', 'RASPBERRY_GOLD', 'WINE_CHAMPAGNE', 'PLUM_CORAL', 'FOREST_HONEY', 'CARMINE_GOLD', 'GARNET_HONEY', 'VIVID_RED_CREAM')
          AND NEW.app_lock_enabled IN (0, 1)
          AND NEW.app_lock_timeout_seconds IN (0, 60, 300)
          AND NEW.metered_backup_policy IN ('EVERY_60_MIN', 'EVERY_30_MIN', 'WIFI_ONLY')
          AND (NEW.onboarding_completed_at IS NULL OR (typeof(NEW.onboarding_completed_at) = 'integer'))
          AND length(NEW.currency_code) = 3 AND NEW.currency_code = upper(NEW.currency_code) AND NEW.currency_code NOT GLOB '*[^A-Z]*'
          AND typeof(NEW.currency_decimals) = 'integer' AND NEW.currency_decimals BETWEEN 0 AND 4
          AND (NEW.currency_symbol IS NULL OR (typeof(NEW.currency_symbol) = 'text' AND length(NEW.currency_symbol) BETWEEN 1 AND 8))
          AND (NEW.currency_locked_at IS NULL OR (typeof(NEW.currency_locked_at) = 'integer'))
          AND typeof(NEW.cash_rounding) = 'integer' AND NEW.cash_rounding BETWEEN 0 AND 1000000000000
          AND typeof(NEW.format_locale) = 'text' AND length(NEW.format_locale) BETWEEN 1 AND 35
          AND typeof(NEW.business_time_zone) = 'text' AND length(NEW.business_time_zone) BETWEEN 1 AND 64
          AND typeof(NEW.week_start_day) = 'integer' AND NEW.week_start_day BETWEEN 1 AND 7
          AND NEW.csv_separator IN ('SEMICOLON', 'COMMA', 'TAB')
          AND NEW.tax_mode IN ('NONE', 'INCLUDED', 'ADDED')
          AND NEW.purchase_tax_recoverable IN (0, 1)
          AND typeof(NEW.default_rounding_step) = 'integer' AND NEW.default_rounding_step BETWEEN 0 AND 1000000000000
          AND NEW.tips_enabled IN (0, 1)
          AND (NEW.reference_currency_code IS NULL OR (length(NEW.reference_currency_code) = 3 AND NEW.reference_currency_code = upper(NEW.reference_currency_code) AND NEW.reference_currency_code NOT GLOB '*[^A-Z]*'))
          AND (NEW.reference_currency_decimals IS NULL OR (typeof(NEW.reference_currency_decimals) = 'integer' AND NEW.reference_currency_decimals BETWEEN 0 AND 4))
          AND typeof(NEW.cost_stale_days) = 'integer' AND NEW.cost_stale_days BETWEEN 0 AND 36500
          AND typeof(NEW.expiry_notice_days) = 'integer' AND NEW.expiry_notice_days BETWEEN 0 AND 36500
          AND NEW.loyalty_enabled IN (0, 1)
          AND (NEW.loyalty_amount_per_point IS NULL OR (typeof(NEW.loyalty_amount_per_point) = 'integer' AND NEW.loyalty_amount_per_point BETWEEN 1 AND 1000000000000))
          AND (NEW.loyalty_point_value IS NULL OR (typeof(NEW.loyalty_point_value) = 'integer' AND NEW.loyalty_point_value BETWEEN 1 AND 1000000000000))
          AND (NEW.revenue_limit_amount IS NULL OR (typeof(NEW.revenue_limit_amount) = 'integer' AND NEW.revenue_limit_amount BETWEEN 1 AND 9000000000000000))
          AND (NEW.revenue_limit_months IS NULL OR (typeof(NEW.revenue_limit_months) = 'integer' AND NEW.revenue_limit_months BETWEEN 1 AND 120))
          AND (NEW.daily_summary_time IS NULL OR (length(NEW.daily_summary_time) = 5 AND NEW.daily_summary_time GLOB '[0-2][0-9]:[0-5][0-9]' AND substr(NEW.daily_summary_time, 1, 2) <= '23'))
          AND (NEW.receipt_business_details IS NULL OR (typeof(NEW.receipt_business_details) = 'text' AND length(NEW.receipt_business_details) BETWEEN 1 AND 300))
          AND (NEW.receipt_footer_text IS NULL OR (typeof(NEW.receipt_footer_text) = 'text' AND length(NEW.receipt_footer_text) BETWEEN 1 AND 300))
          AND (NEW.fiscal_portal_url IS NULL OR (typeof(NEW.fiscal_portal_url) = 'text' AND length(NEW.fiscal_portal_url) BETWEEN 1 AND 300))
          AND NEW.discreet_mode_enabled IN (0, 1)
          AND NEW.media_on_metered IN (0, 1)
          AND NEW.update_check_enabled IN (0, 1)
          AND typeof(NEW.updated_at) = 'integer'
          AND NEW.id = 1
          AND (NEW.reference_currency_code IS NULL) = (NEW.reference_currency_decimals IS NULL)
          AND (NEW.loyalty_enabled = 0 OR (NEW.loyalty_amount_per_point IS NOT NULL AND NEW.loyalty_point_value IS NOT NULL))
          AND (NEW.revenue_limit_amount IS NULL) = (NEW.revenue_limit_months IS NULL)
          AND (OLD.currency_locked_at IS NULL OR (NEW.currency_code = OLD.currency_code AND NEW.currency_decimals = OLD.currency_decimals AND NEW.currency_locked_at IS OLD.currency_locked_at))
          AND (NEW.currency_locked_at IS NULL OR NEW.currency_code <> 'XXX')
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:app_settings'); END;

        CREATE TRIGGER guard_app_settings_no_delete BEFORE DELETE ON app_settings
        BEGIN SELECT RAISE(ABORT, 'guard:app_settings_no_delete'); END;
        """,
        Awaiting.Columns("guard_app_settings_insert", "app_settings", SETTINGS_COLUMNS_TO_COME),
        Awaiting.Columns("guard_app_settings_update", "app_settings", SETTINGS_COLUMNS_TO_COME),
    )

/** El estado del respaldo: fila única, que nunca se borra, y `change_seq` nunca baja. */
private val BACKUP_STATE_GUARDS =
    table(
        """
        CREATE TRIGGER guard_backup_state_insert BEFORE INSERT ON backup_state
        WHEN NOT IFNULL((
          typeof(NEW.change_seq) = 'integer' AND NEW.change_seq >= 0
          AND typeof(NEW.verified_seq) = 'integer' AND NEW.verified_seq >= 0
          AND (NEW.first_unsynced_at IS NULL OR (typeof(NEW.first_unsynced_at) = 'integer'))
          AND NEW.link_state IN ('NOT_CONNECTED', 'CONNECTED', 'PERMISSION_LOST', 'CONFLICT')
          AND (NEW.failure_code IS NULL OR (NEW.failure_code IN ('QUOTA_EXCEEDED', 'LOCAL_CORRUPT', 'LOCAL_STORAGE_LOW', 'LOCAL_INVARIANT', 'LOCAL_INVARIANT_FATAL', 'VERIFY_MISMATCH', 'BACKUP_TOO_LARGE', 'CLOUD_SCHEMA_NEWER', 'DESTINATION_UNSUPPORTED', 'TRANSPORT_PERSISTENT', 'ENCRYPTION_KEY_UNAVAILABLE', 'UNEXPECTED')))
          AND typeof(NEW.consecutive_failures) = 'integer' AND NEW.consecutive_failures >= 0
          AND (NEW.last_failure_kind IS NULL OR (NEW.last_failure_kind IN ('NETWORK', 'TLS', 'HTTP_5XX', 'RATE_LIMIT')))
          AND typeof(NEW.invariant_failures) = 'integer' AND NEW.invariant_failures >= 0
          AND (NEW.last_attempt_at IS NULL OR (typeof(NEW.last_attempt_at) = 'integer'))
          AND (NEW.last_success_at IS NULL OR (typeof(NEW.last_success_at) = 'integer'))
          AND (NEW.last_upload_finished_at IS NULL OR (typeof(NEW.last_upload_finished_at) = 'integer'))
          AND (NEW.last_stale_notice_at IS NULL OR (typeof(NEW.last_stale_notice_at) = 'integer'))
          AND (NEW.last_drill_at IS NULL OR (typeof(NEW.last_drill_at) = 'integer'))
          AND (NEW.last_drill_result IS NULL OR (NEW.last_drill_result IN ('PASSED', 'FAILED', 'SKIPPED')))
          AND (NEW.last_full_verify_at IS NULL OR (typeof(NEW.last_full_verify_at) = 'integer'))
          AND (NEW.last_human_check_at IS NULL OR (typeof(NEW.last_human_check_at) = 'integer'))
          AND (NEW.human_check_generation IS NULL OR (typeof(NEW.human_check_generation) = 'integer' AND NEW.human_check_generation >= 0))
          AND (NEW.destination_kind IS NULL OR (NEW.destination_kind IN ('WEBDAV', 'DOCUMENT_SLOTS')))
          AND (NEW.destination_account IS NULL OR (typeof(NEW.destination_account) = 'text' AND length(NEW.destination_account) BETWEEN 1 AND 254))
          AND (NEW.destination_id IS NULL OR (typeof(NEW.destination_id) = 'text' AND length(NEW.destination_id) BETWEEN 1 AND 64))
          AND (NEW.destination_folder IS NULL OR (typeof(NEW.destination_folder) = 'text' AND length(NEW.destination_folder) BETWEEN 1 AND 1024))
          AND typeof(NEW.head_generation) = 'integer' AND NEW.head_generation >= 0
          AND (NEW.head_device_id IS NULL OR (typeof(NEW.head_device_id) = 'text' AND length(NEW.head_device_id) BETWEEN 1 AND 64))
          AND (NEW.head_file_ref IS NULL OR (typeof(NEW.head_file_ref) = 'text' AND length(NEW.head_file_ref) BETWEEN 1 AND 1024))
          AND (NEW.shrink_accepted_generation IS NULL OR (typeof(NEW.shrink_accepted_generation) = 'integer'))
          AND (NEW.pending_generation IS NULL OR (typeof(NEW.pending_generation) = 'integer'))
          AND (NEW.pending_seq IS NULL OR (typeof(NEW.pending_seq) = 'integer'))
          AND (NEW.pending_sha256 IS NULL OR (typeof(NEW.pending_sha256) = 'text' AND length(NEW.pending_sha256) BETWEEN 1 AND 64))
          AND (NEW.pending_size_bytes IS NULL OR (typeof(NEW.pending_size_bytes) = 'integer'))
          AND (NEW.conflict_kind IS NULL OR (NEW.conflict_kind IN ('CLOUD_AHEAD', 'OTHER_DEVICE', 'LOCAL_SHRUNK')))
          AND (NEW.conflict_generation IS NULL OR (typeof(NEW.conflict_generation) = 'integer'))
          AND (NEW.conflict_device_id IS NULL OR (typeof(NEW.conflict_device_id) = 'text' AND length(NEW.conflict_device_id) BETWEEN 1 AND 64))
          AND (NEW.conflict_file_ref IS NULL OR (typeof(NEW.conflict_file_ref) = 'text' AND length(NEW.conflict_file_ref) BETWEEN 1 AND 1024))
          AND (NEW.copy_generation IS NULL OR (typeof(NEW.copy_generation) = 'integer'))
          AND (NEW.copy_device_id IS NULL OR (typeof(NEW.copy_device_id) = 'text' AND length(NEW.copy_device_id) BETWEEN 1 AND 64))
          AND (NEW.copy_created_at IS NULL OR (typeof(NEW.copy_created_at) = 'integer'))
          AND NEW.id = 1
          AND NEW.verified_seq <= NEW.change_seq
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:backup_state'); END;

        CREATE TRIGGER guard_backup_state_update BEFORE UPDATE ON backup_state
        WHEN NOT IFNULL((
          typeof(NEW.change_seq) = 'integer' AND NEW.change_seq >= 0
          AND typeof(NEW.verified_seq) = 'integer' AND NEW.verified_seq >= 0
          AND (NEW.first_unsynced_at IS NULL OR (typeof(NEW.first_unsynced_at) = 'integer'))
          AND NEW.link_state IN ('NOT_CONNECTED', 'CONNECTED', 'PERMISSION_LOST', 'CONFLICT')
          AND (NEW.failure_code IS NULL OR (NEW.failure_code IN ('QUOTA_EXCEEDED', 'LOCAL_CORRUPT', 'LOCAL_STORAGE_LOW', 'LOCAL_INVARIANT', 'LOCAL_INVARIANT_FATAL', 'VERIFY_MISMATCH', 'BACKUP_TOO_LARGE', 'CLOUD_SCHEMA_NEWER', 'DESTINATION_UNSUPPORTED', 'TRANSPORT_PERSISTENT', 'ENCRYPTION_KEY_UNAVAILABLE', 'UNEXPECTED')))
          AND typeof(NEW.consecutive_failures) = 'integer' AND NEW.consecutive_failures >= 0
          AND (NEW.last_failure_kind IS NULL OR (NEW.last_failure_kind IN ('NETWORK', 'TLS', 'HTTP_5XX', 'RATE_LIMIT')))
          AND typeof(NEW.invariant_failures) = 'integer' AND NEW.invariant_failures >= 0
          AND (NEW.last_attempt_at IS NULL OR (typeof(NEW.last_attempt_at) = 'integer'))
          AND (NEW.last_success_at IS NULL OR (typeof(NEW.last_success_at) = 'integer'))
          AND (NEW.last_upload_finished_at IS NULL OR (typeof(NEW.last_upload_finished_at) = 'integer'))
          AND (NEW.last_stale_notice_at IS NULL OR (typeof(NEW.last_stale_notice_at) = 'integer'))
          AND (NEW.last_drill_at IS NULL OR (typeof(NEW.last_drill_at) = 'integer'))
          AND (NEW.last_drill_result IS NULL OR (NEW.last_drill_result IN ('PASSED', 'FAILED', 'SKIPPED')))
          AND (NEW.last_full_verify_at IS NULL OR (typeof(NEW.last_full_verify_at) = 'integer'))
          AND (NEW.last_human_check_at IS NULL OR (typeof(NEW.last_human_check_at) = 'integer'))
          AND (NEW.human_check_generation IS NULL OR (typeof(NEW.human_check_generation) = 'integer' AND NEW.human_check_generation >= 0))
          AND (NEW.destination_kind IS NULL OR (NEW.destination_kind IN ('WEBDAV', 'DOCUMENT_SLOTS')))
          AND (NEW.destination_account IS NULL OR (typeof(NEW.destination_account) = 'text' AND length(NEW.destination_account) BETWEEN 1 AND 254))
          AND (NEW.destination_id IS NULL OR (typeof(NEW.destination_id) = 'text' AND length(NEW.destination_id) BETWEEN 1 AND 64))
          AND (NEW.destination_folder IS NULL OR (typeof(NEW.destination_folder) = 'text' AND length(NEW.destination_folder) BETWEEN 1 AND 1024))
          AND typeof(NEW.head_generation) = 'integer' AND NEW.head_generation >= 0
          AND (NEW.head_device_id IS NULL OR (typeof(NEW.head_device_id) = 'text' AND length(NEW.head_device_id) BETWEEN 1 AND 64))
          AND (NEW.head_file_ref IS NULL OR (typeof(NEW.head_file_ref) = 'text' AND length(NEW.head_file_ref) BETWEEN 1 AND 1024))
          AND (NEW.shrink_accepted_generation IS NULL OR (typeof(NEW.shrink_accepted_generation) = 'integer'))
          AND (NEW.pending_generation IS NULL OR (typeof(NEW.pending_generation) = 'integer'))
          AND (NEW.pending_seq IS NULL OR (typeof(NEW.pending_seq) = 'integer'))
          AND (NEW.pending_sha256 IS NULL OR (typeof(NEW.pending_sha256) = 'text' AND length(NEW.pending_sha256) BETWEEN 1 AND 64))
          AND (NEW.pending_size_bytes IS NULL OR (typeof(NEW.pending_size_bytes) = 'integer'))
          AND (NEW.conflict_kind IS NULL OR (NEW.conflict_kind IN ('CLOUD_AHEAD', 'OTHER_DEVICE', 'LOCAL_SHRUNK')))
          AND (NEW.conflict_generation IS NULL OR (typeof(NEW.conflict_generation) = 'integer'))
          AND (NEW.conflict_device_id IS NULL OR (typeof(NEW.conflict_device_id) = 'text' AND length(NEW.conflict_device_id) BETWEEN 1 AND 64))
          AND (NEW.conflict_file_ref IS NULL OR (typeof(NEW.conflict_file_ref) = 'text' AND length(NEW.conflict_file_ref) BETWEEN 1 AND 1024))
          AND (NEW.copy_generation IS NULL OR (typeof(NEW.copy_generation) = 'integer'))
          AND (NEW.copy_device_id IS NULL OR (typeof(NEW.copy_device_id) = 'text' AND length(NEW.copy_device_id) BETWEEN 1 AND 64))
          AND (NEW.copy_created_at IS NULL OR (typeof(NEW.copy_created_at) = 'integer'))
          AND NEW.id = 1
          AND NEW.verified_seq <= NEW.change_seq
          AND NEW.change_seq >= OLD.change_seq
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:backup_state'); END;

        CREATE TRIGGER guard_backup_state_no_delete BEFORE DELETE ON backup_state
        BEGIN SELECT RAISE(ABORT, 'guard:backup_state_no_delete'); END;
        """,
    )

/** Las categorías de productos. */
private val CATEGORIES_GUARDS =
    table(
        """
        CREATE TRIGGER guard_categories_insert BEFORE INSERT ON categories
        WHEN NOT IFNULL((
          typeof(NEW.name) = 'text' AND length(NEW.name) BETWEEN 1 AND 40
          AND typeof(NEW.normalized_name) = 'text' AND length(NEW.normalized_name) BETWEEN 1 AND 40
          AND (NEW.target_margin_bp IS NULL OR (typeof(NEW.target_margin_bp) = 'integer' AND NEW.target_margin_bp BETWEEN 0 AND 9900))
          AND typeof(NEW.created_at) = 'integer'
          AND typeof(NEW.updated_at) = 'integer'
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:categories'); END;

        CREATE TRIGGER guard_categories_update BEFORE UPDATE ON categories
        WHEN NOT IFNULL((
          typeof(NEW.name) = 'text' AND length(NEW.name) BETWEEN 1 AND 40
          AND typeof(NEW.normalized_name) = 'text' AND length(NEW.normalized_name) BETWEEN 1 AND 40
          AND (NEW.target_margin_bp IS NULL OR (typeof(NEW.target_margin_bp) = 'integer' AND NEW.target_margin_bp BETWEEN 0 AND 9900))
          AND typeof(NEW.created_at) = 'integer'
          AND typeof(NEW.updated_at) = 'integer'
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:categories'); END;
        """,
    )

/** Los proveedores: el nombre es único entre los vigentes. */
private val SUPPLIERS_GUARDS =
    table(
        """
        CREATE TRIGGER guard_suppliers_insert BEFORE INSERT ON suppliers
        WHEN NOT IFNULL((
          typeof(NEW.name) = 'text' AND length(NEW.name) BETWEEN 1 AND 80
          AND typeof(NEW.normalized_name) = 'text' AND length(NEW.normalized_name) BETWEEN 1 AND 80
          AND (NEW.phone IS NULL OR (typeof(NEW.phone) = 'text' AND length(NEW.phone) BETWEEN 1 AND 30))
          AND (NEW.email IS NULL OR (typeof(NEW.email) = 'text' AND length(NEW.email) BETWEEN 1 AND 120))
          AND (NEW.notes IS NULL OR (typeof(NEW.notes) = 'text' AND length(NEW.notes) BETWEEN 1 AND 500))
          AND typeof(NEW.balance) = 'integer'
          AND (NEW.payment_terms_days IS NULL OR (typeof(NEW.payment_terms_days) = 'integer' AND NEW.payment_terms_days BETWEEN 0 AND 3650))
          AND (NEW.archived_at IS NULL OR (typeof(NEW.archived_at) = 'integer'))
          AND typeof(NEW.created_at) = 'integer'
          AND typeof(NEW.updated_at) = 'integer'
          AND (NEW.archived_at IS NOT NULL OR NOT EXISTS (SELECT 1 FROM suppliers s WHERE s.normalized_name = NEW.normalized_name AND s.archived_at IS NULL))
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:suppliers'); END;

        CREATE TRIGGER guard_suppliers_update BEFORE UPDATE ON suppliers
        WHEN NOT IFNULL((
          typeof(NEW.name) = 'text' AND length(NEW.name) BETWEEN 1 AND 80
          AND typeof(NEW.normalized_name) = 'text' AND length(NEW.normalized_name) BETWEEN 1 AND 80
          AND (NEW.phone IS NULL OR (typeof(NEW.phone) = 'text' AND length(NEW.phone) BETWEEN 1 AND 30))
          AND (NEW.email IS NULL OR (typeof(NEW.email) = 'text' AND length(NEW.email) BETWEEN 1 AND 120))
          AND (NEW.notes IS NULL OR (typeof(NEW.notes) = 'text' AND length(NEW.notes) BETWEEN 1 AND 500))
          AND typeof(NEW.balance) = 'integer'
          AND (NEW.payment_terms_days IS NULL OR (typeof(NEW.payment_terms_days) = 'integer' AND NEW.payment_terms_days BETWEEN 0 AND 3650))
          AND (NEW.archived_at IS NULL OR (typeof(NEW.archived_at) = 'integer'))
          AND typeof(NEW.created_at) = 'integer'
          AND typeof(NEW.updated_at) = 'integer'
          AND (NEW.archived_at IS NOT NULL OR NOT EXISTS (SELECT 1 FROM suppliers s WHERE s.normalized_name = NEW.normalized_name AND s.archived_at IS NULL AND s.id <> NEW.id))
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:suppliers'); END;
        """,
    )

/** Los productos: nombre y código únicos entre los vigentes, y el modo de stock, la unidad
 * base y los lotes fijos una vez que hay historial.
 *
 * Mientras no exista la columna que bloquea la moneda, que llega con la configuración
 * regional, no se exige la moneda confirmada antes del primer registro con dinero. Es la única
 * parte de estas guardas que hoy protege menos que su texto completo. */
private val PRODUCTS_GUARDS =
    table(
        """
        CREATE TRIGGER guard_products_insert BEFORE INSERT ON products
        WHEN NOT IFNULL((
          typeof(NEW.name) = 'text' AND length(NEW.name) BETWEEN 1 AND 80
          AND typeof(NEW.normalized_name) = 'text' AND length(NEW.normalized_name) BETWEEN 1 AND 80
          AND NEW.stock_mode IN ('TRACKED', 'UNTRACKED', 'COMPONENTS')
          AND typeof(NEW.sale_price) = 'integer' AND NEW.sale_price BETWEEN 0 AND 1000000000000
          AND typeof(NEW.average_cost) = 'integer' AND NEW.average_cost BETWEEN 0 AND 1000000000000
          AND typeof(NEW.initial_cost) = 'integer' AND NEW.initial_cost BETWEEN 0 AND 1000000000000
          AND typeof(NEW.fixed_unit_cost) = 'integer' AND NEW.fixed_unit_cost BETWEEN 0 AND 1000000000000
          AND typeof(NEW.stock_value) = 'integer' AND NEW.stock_value BETWEEN 0 AND 9000000000000000
          AND typeof(NEW.on_hand_milli) = 'integer'
          AND typeof(NEW.min_stock_milli) = 'integer' AND NEW.min_stock_milli BETWEEN 0 AND 8000000000
          AND (NEW.barcode IS NULL OR (typeof(NEW.barcode) = 'text' AND length(NEW.barcode) BETWEEN 1 AND 64))
          AND (NEW.variant_axis_1 IS NULL OR (typeof(NEW.variant_axis_1) = 'text' AND length(NEW.variant_axis_1) BETWEEN 1 AND 40))
          AND (NEW.variant_axis_2 IS NULL OR (typeof(NEW.variant_axis_2) = 'text' AND length(NEW.variant_axis_2) BETWEEN 1 AND 40))
          AND (NEW.variant_value_1 IS NULL OR (typeof(NEW.variant_value_1) = 'text' AND length(NEW.variant_value_1) BETWEEN 1 AND 40))
          AND (NEW.variant_value_2 IS NULL OR (typeof(NEW.variant_value_2) = 'text' AND length(NEW.variant_value_2) BETWEEN 1 AND 40))
          AND typeof(NEW.replacement_cost) = 'integer' AND NEW.replacement_cost BETWEEN 0 AND 1000000000000
          AND (NEW.replacement_cost_at IS NULL OR (typeof(NEW.replacement_cost_at) = 'integer'))
          AND (NEW.target_margin_bp IS NULL OR (typeof(NEW.target_margin_bp) = 'integer' AND NEW.target_margin_bp BETWEEN 0 AND 9900))
          AND (NEW.reference_cost IS NULL OR (typeof(NEW.reference_cost) = 'integer' AND NEW.reference_cost BETWEEN 0 AND 1000000000000))
          AND (NEW.reference_price IS NULL OR (typeof(NEW.reference_price) = 'integer' AND NEW.reference_price BETWEEN 0 AND 1000000000000))
          AND (NEW.price_review_since IS NULL OR (typeof(NEW.price_review_since) = 'integer'))
          AND NEW.tracks_lots IN (0, 1)
          AND (NEW.expiry_notice_days IS NULL OR (typeof(NEW.expiry_notice_days) = 'integer' AND NEW.expiry_notice_days BETWEEN 0 AND 36500))
          AND (NEW.archived_at IS NULL OR (typeof(NEW.archived_at) = 'integer'))
          AND typeof(NEW.created_at) = 'integer'
          AND typeof(NEW.updated_at) = 'integer'
          AND (NEW.stock_mode = 'TRACKED' OR (NEW.on_hand_milli = 0 AND NEW.stock_value = 0))
          AND (NEW.stock_mode = 'UNTRACKED' OR NEW.fixed_unit_cost = 0)
          AND (NEW.tracks_lots = 0 OR NEW.stock_mode = 'TRACKED')
          AND (NEW.sale_price = 0 AND NEW.initial_cost = 0 AND NEW.fixed_unit_cost = 0 AND NEW.replacement_cost = 0 OR (SELECT currency_locked_at FROM app_settings WHERE id = 1) IS NOT NULL)
          AND (NEW.parent_product_id IS NULL OR EXISTS (SELECT 1 FROM products p WHERE p.id = NEW.parent_product_id AND p.parent_product_id IS NULL AND p.id <> NEW.id))
          AND (NEW.barcode IS NULL OR NEW.archived_at IS NOT NULL OR NOT EXISTS (SELECT 1 FROM product_units u WHERE u.barcode = NEW.barcode AND u.product_id <> NEW.id))
          AND (NEW.archived_at IS NOT NULL OR NOT EXISTS (SELECT 1 FROM products p WHERE p.normalized_name = NEW.normalized_name AND p.archived_at IS NULL))
          AND (NEW.barcode IS NULL OR NEW.archived_at IS NOT NULL OR NOT EXISTS (SELECT 1 FROM products p WHERE p.barcode = NEW.barcode AND p.archived_at IS NULL))
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:products'); END;

        CREATE TRIGGER guard_products_update BEFORE UPDATE ON products
        WHEN NOT IFNULL((
          typeof(NEW.name) = 'text' AND length(NEW.name) BETWEEN 1 AND 80
          AND typeof(NEW.normalized_name) = 'text' AND length(NEW.normalized_name) BETWEEN 1 AND 80
          AND NEW.stock_mode IN ('TRACKED', 'UNTRACKED', 'COMPONENTS')
          AND typeof(NEW.sale_price) = 'integer' AND NEW.sale_price BETWEEN 0 AND 1000000000000
          AND typeof(NEW.average_cost) = 'integer' AND NEW.average_cost BETWEEN 0 AND 1000000000000
          AND typeof(NEW.initial_cost) = 'integer' AND NEW.initial_cost BETWEEN 0 AND 1000000000000
          AND typeof(NEW.fixed_unit_cost) = 'integer' AND NEW.fixed_unit_cost BETWEEN 0 AND 1000000000000
          AND typeof(NEW.stock_value) = 'integer' AND NEW.stock_value BETWEEN 0 AND 9000000000000000
          AND typeof(NEW.on_hand_milli) = 'integer'
          AND typeof(NEW.min_stock_milli) = 'integer' AND NEW.min_stock_milli BETWEEN 0 AND 8000000000
          AND (NEW.barcode IS NULL OR (typeof(NEW.barcode) = 'text' AND length(NEW.barcode) BETWEEN 1 AND 64))
          AND (NEW.variant_axis_1 IS NULL OR (typeof(NEW.variant_axis_1) = 'text' AND length(NEW.variant_axis_1) BETWEEN 1 AND 40))
          AND (NEW.variant_axis_2 IS NULL OR (typeof(NEW.variant_axis_2) = 'text' AND length(NEW.variant_axis_2) BETWEEN 1 AND 40))
          AND (NEW.variant_value_1 IS NULL OR (typeof(NEW.variant_value_1) = 'text' AND length(NEW.variant_value_1) BETWEEN 1 AND 40))
          AND (NEW.variant_value_2 IS NULL OR (typeof(NEW.variant_value_2) = 'text' AND length(NEW.variant_value_2) BETWEEN 1 AND 40))
          AND typeof(NEW.replacement_cost) = 'integer' AND NEW.replacement_cost BETWEEN 0 AND 1000000000000
          AND (NEW.replacement_cost_at IS NULL OR (typeof(NEW.replacement_cost_at) = 'integer'))
          AND (NEW.target_margin_bp IS NULL OR (typeof(NEW.target_margin_bp) = 'integer' AND NEW.target_margin_bp BETWEEN 0 AND 9900))
          AND (NEW.reference_cost IS NULL OR (typeof(NEW.reference_cost) = 'integer' AND NEW.reference_cost BETWEEN 0 AND 1000000000000))
          AND (NEW.reference_price IS NULL OR (typeof(NEW.reference_price) = 'integer' AND NEW.reference_price BETWEEN 0 AND 1000000000000))
          AND (NEW.price_review_since IS NULL OR (typeof(NEW.price_review_since) = 'integer'))
          AND NEW.tracks_lots IN (0, 1)
          AND (NEW.expiry_notice_days IS NULL OR (typeof(NEW.expiry_notice_days) = 'integer' AND NEW.expiry_notice_days BETWEEN 0 AND 36500))
          AND (NEW.archived_at IS NULL OR (typeof(NEW.archived_at) = 'integer'))
          AND typeof(NEW.created_at) = 'integer'
          AND typeof(NEW.updated_at) = 'integer'
          AND (NEW.stock_mode = 'TRACKED' OR (NEW.on_hand_milli = 0 AND NEW.stock_value = 0))
          AND (NEW.stock_mode = 'UNTRACKED' OR NEW.fixed_unit_cost = 0)
          AND (NEW.tracks_lots = 0 OR NEW.stock_mode = 'TRACKED')
          AND (NEW.sale_price = 0 AND NEW.initial_cost = 0 AND NEW.fixed_unit_cost = 0 AND NEW.replacement_cost = 0 OR (SELECT currency_locked_at FROM app_settings WHERE id = 1) IS NOT NULL)
          AND (NEW.parent_product_id IS NULL OR EXISTS (SELECT 1 FROM products p WHERE p.id = NEW.parent_product_id AND p.parent_product_id IS NULL AND p.id <> NEW.id))
          AND (NEW.barcode IS NULL OR NEW.archived_at IS NOT NULL OR NOT EXISTS (SELECT 1 FROM product_units u WHERE u.barcode = NEW.barcode AND u.product_id <> NEW.id))
          AND (NEW.archived_at IS NOT NULL OR NOT EXISTS (SELECT 1 FROM products p WHERE p.normalized_name = NEW.normalized_name AND p.archived_at IS NULL AND p.id <> NEW.id))
          AND (NEW.barcode IS NULL OR NEW.archived_at IS NOT NULL OR NOT EXISTS (SELECT 1 FROM products p WHERE p.barcode = NEW.barcode AND p.archived_at IS NULL AND p.id <> NEW.id))
          AND ((NEW.base_unit_id = OLD.base_unit_id AND NEW.stock_mode = OLD.stock_mode AND NEW.tracks_lots = OLD.tracks_lots) OR (NOT EXISTS (SELECT 1 FROM stock_movements m WHERE m.product_id = OLD.id) AND NOT EXISTS (SELECT 1 FROM sale_lines l WHERE l.product_id = OLD.id) AND NOT EXISTS (SELECT 1 FROM sale_line_components c WHERE c.component_product_id = OLD.id)))
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:products'); END;
        """,
        awaitingCurrencyLock("guard_products_insert"),
        awaitingCurrencyLock("guard_products_update"),
    )

/** El historial de precios: solo crece, y se borra solo en cascada con su producto. */
private val PRODUCT_PRICE_HISTORY_GUARDS =
    table(
        """
        CREATE TRIGGER guard_product_price_history_insert BEFORE INSERT ON product_price_history
        WHEN NOT IFNULL((
          (NEW.old_price IS NULL OR (typeof(NEW.old_price) = 'integer' AND NEW.old_price BETWEEN 0 AND 1000000000000))
          AND typeof(NEW.new_price) = 'integer' AND NEW.new_price BETWEEN 0 AND 1000000000000
          AND NEW.source IN ('INITIAL', 'MANUAL', 'BULK', 'BULK_UNDO', 'IMPORT')
          AND (NEW.batch_id IS NULL OR (typeof(NEW.batch_id) = 'text' AND length(NEW.batch_id) BETWEEN 36 AND 36))
          AND (NEW.bulk_mode IS NULL OR (NEW.bulk_mode IN ('PERCENT', 'AMOUNT', 'TARGET_MARGIN', 'REFERENCE_RATE')))
          AND (NEW.percent_bp IS NULL OR (typeof(NEW.percent_bp) = 'integer' AND NEW.percent_bp BETWEEN -9999 AND 99999))
          AND (NEW.amount_delta IS NULL OR (typeof(NEW.amount_delta) = 'integer' AND NEW.amount_delta BETWEEN -1000000000000 AND 1000000000000))
          AND (NEW.target_margin_bp IS NULL OR (typeof(NEW.target_margin_bp) = 'integer' AND NEW.target_margin_bp BETWEEN 0 AND 9900))
          AND (NEW.fx_rate_micro IS NULL OR (typeof(NEW.fx_rate_micro) = 'integer' AND NEW.fx_rate_micro BETWEEN 1 AND 1000000000000000))
          AND (NEW.rounding_step IS NULL OR (typeof(NEW.rounding_step) = 'integer' AND NEW.rounding_step BETWEEN 0 AND 1000000000000))
          AND typeof(NEW.occurred_at) = 'integer'
          AND date(NEW.business_date) = NEW.business_date
          AND (NEW.source = 'INITIAL') = (NEW.old_price IS NULL)
          AND (NEW.source IN ('BULK', 'BULK_UNDO')) = (NEW.batch_id IS NOT NULL)
          AND (NEW.source = 'BULK') = (NEW.bulk_mode IS NOT NULL)
          AND (NEW.source = 'BULK') = (NEW.rounding_step IS NOT NULL)
          AND (NEW.source = 'BULK' AND NEW.bulk_mode = 'PERCENT') = (NEW.percent_bp IS NOT NULL)
          AND (NEW.source = 'BULK' AND NEW.bulk_mode = 'AMOUNT') = (NEW.amount_delta IS NOT NULL)
          AND (NEW.source = 'BULK' AND NEW.bulk_mode = 'TARGET_MARGIN') = (NEW.target_margin_bp IS NOT NULL)
          AND (NEW.source = 'BULK' AND NEW.bulk_mode = 'REFERENCE_RATE') = (NEW.fx_rate_micro IS NOT NULL)
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:product_price_history'); END;

        CREATE TRIGGER guard_product_price_history_no_update BEFORE UPDATE ON product_price_history
        BEGIN SELECT RAISE(ABORT, 'guard:product_price_history_append_only'); END;

        CREATE TRIGGER guard_product_price_history_no_delete BEFORE DELETE ON product_price_history
        WHEN EXISTS (SELECT 1 FROM products p WHERE p.id = OLD.product_id)
        BEGIN SELECT RAISE(ABORT, 'guard:product_price_history_append_only'); END;
        """,
    )

/** Las recetas de los combos: el padre es un combo, nunca su propio componente, y la receta
 * tiene a lo sumo dos niveles, sin ciclos. Sin consultas recursivas, que SQLite no admite en un
 * trigger: se comprueban los dos niveles por separado. */
private val PRODUCT_COMPONENTS_GUARDS =
    table(
        """
        CREATE TRIGGER guard_product_components_insert BEFORE INSERT ON product_components
        WHEN NOT IFNULL((
          typeof(NEW.quantity_milli) = 'integer' AND NEW.quantity_milli BETWEEN 1 AND 1000000000
          AND typeof(NEW.created_at) = 'integer'
          AND typeof(NEW.updated_at) = 'integer'
          AND NEW.parent_product_id <> NEW.component_product_id
          AND EXISTS (SELECT 1 FROM products p WHERE p.id = NEW.parent_product_id AND p.stock_mode = 'COMPONENTS')
          AND NOT EXISTS (SELECT 1 FROM product_components a JOIN product_components b ON b.parent_product_id = a.component_product_id WHERE a.parent_product_id = NEW.component_product_id)
          AND NOT (EXISTS (SELECT 1 FROM product_components a WHERE a.component_product_id = NEW.parent_product_id) AND EXISTS (SELECT 1 FROM product_components b WHERE b.parent_product_id = NEW.component_product_id))
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:product_components'); END;

        CREATE TRIGGER guard_product_components_update BEFORE UPDATE ON product_components
        WHEN NOT IFNULL((
          typeof(NEW.quantity_milli) = 'integer' AND NEW.quantity_milli BETWEEN 1 AND 1000000000
          AND typeof(NEW.created_at) = 'integer'
          AND typeof(NEW.updated_at) = 'integer'
          AND NEW.parent_product_id <> NEW.component_product_id
          AND EXISTS (SELECT 1 FROM products p WHERE p.id = NEW.parent_product_id AND p.stock_mode = 'COMPONENTS')
          AND NOT EXISTS (SELECT 1 FROM product_components a JOIN product_components b ON b.parent_product_id = a.component_product_id WHERE a.parent_product_id = NEW.component_product_id)
          AND NOT (EXISTS (SELECT 1 FROM product_components a WHERE a.component_product_id = NEW.parent_product_id) AND EXISTS (SELECT 1 FROM product_components b WHERE b.parent_product_id = NEW.component_product_id))
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:product_components'); END;
        """,
    )

/** Los campos propios: nombre con su largo y un tipo conocido. El nombre es único entre los
 * vigentes: uno archivado no reserva el suyo. */
private val CUSTOM_FIELD_DEFINITIONS_GUARDS =
    table(
        """
        CREATE TRIGGER guard_custom_field_definitions_insert BEFORE INSERT ON custom_field_definitions
        WHEN NOT IFNULL((
          typeof(NEW.name) = 'text' AND length(NEW.name) BETWEEN 1 AND 40
          AND typeof(NEW.normalized_name) = 'text' AND length(NEW.normalized_name) BETWEEN 1 AND 40
          AND NEW.field_type IN ('TEXT', 'NUMBER', 'DATE', 'BOOLEAN')
          AND typeof(NEW.sort_order) = 'integer'
          AND (NEW.archived_at IS NULL OR (typeof(NEW.archived_at) = 'integer'))
          AND typeof(NEW.created_at) = 'integer'
          AND typeof(NEW.updated_at) = 'integer'
          AND (NEW.archived_at IS NOT NULL OR NOT EXISTS (SELECT 1 FROM custom_field_definitions f WHERE f.normalized_name = NEW.normalized_name AND f.archived_at IS NULL))
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:custom_field_definitions'); END;

        CREATE TRIGGER guard_custom_field_definitions_update BEFORE UPDATE ON custom_field_definitions
        WHEN NOT IFNULL((
          typeof(NEW.name) = 'text' AND length(NEW.name) BETWEEN 1 AND 40
          AND typeof(NEW.normalized_name) = 'text' AND length(NEW.normalized_name) BETWEEN 1 AND 40
          AND NEW.field_type IN ('TEXT', 'NUMBER', 'DATE', 'BOOLEAN')
          AND typeof(NEW.sort_order) = 'integer'
          AND (NEW.archived_at IS NULL OR (typeof(NEW.archived_at) = 'integer'))
          AND typeof(NEW.created_at) = 'integer'
          AND typeof(NEW.updated_at) = 'integer'
          AND (NEW.archived_at IS NOT NULL OR NOT EXISTS (SELECT 1 FROM custom_field_definitions f WHERE f.normalized_name = NEW.normalized_name AND f.archived_at IS NULL AND f.id <> NEW.id))
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:custom_field_definitions'); END;
        """,
    )

/** Los valores de los campos propios: texto neutro de 1 a 200 caracteres. */
private val PRODUCT_CUSTOM_VALUES_GUARDS =
    table(
        """
        CREATE TRIGGER guard_product_custom_values_insert BEFORE INSERT ON product_custom_values
        WHEN NOT IFNULL((
          typeof(NEW.value_text) = 'text' AND length(NEW.value_text) BETWEEN 1 AND 200
          AND typeof(NEW.updated_at) = 'integer'
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:product_custom_values'); END;

        CREATE TRIGGER guard_product_custom_values_update BEFORE UPDATE ON product_custom_values
        WHEN NOT IFNULL((
          typeof(NEW.value_text) = 'text' AND length(NEW.value_text) BETWEEN 1 AND 200
          AND typeof(NEW.updated_at) = 'integer'
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:product_custom_values'); END;
        """,
    )

/** Las etiquetas: nombre con su largo. */
private val TAGS_GUARDS =
    table(
        """
        CREATE TRIGGER guard_tags_insert BEFORE INSERT ON tags
        WHEN NOT IFNULL((
          typeof(NEW.name) = 'text' AND length(NEW.name) BETWEEN 1 AND 40
          AND typeof(NEW.normalized_name) = 'text' AND length(NEW.normalized_name) BETWEEN 1 AND 40
          AND typeof(NEW.created_at) = 'integer'
          AND typeof(NEW.updated_at) = 'integer'
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:tags'); END;

        CREATE TRIGGER guard_tags_update BEFORE UPDATE ON tags
        WHEN NOT IFNULL((
          typeof(NEW.name) = 'text' AND length(NEW.name) BETWEEN 1 AND 40
          AND typeof(NEW.normalized_name) = 'text' AND length(NEW.normalized_name) BETWEEN 1 AND 40
          AND typeof(NEW.created_at) = 'integer'
          AND typeof(NEW.updated_at) = 'integer'
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:tags'); END;
        """,
    )

/** Los vínculos entre productos y etiquetas: dos ids enteros. */
private val PRODUCT_TAGS_GUARDS =
    table(
        """
        CREATE TRIGGER guard_product_tags_insert BEFORE INSERT ON product_tags
        WHEN NOT IFNULL((
          typeof(NEW.product_id) = 'integer' AND typeof(NEW.tag_id) = 'integer'
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:product_tags'); END;

        CREATE TRIGGER guard_product_tags_update BEFORE UPDATE ON product_tags
        WHEN NOT IFNULL((
          typeof(NEW.product_id) = 'integer' AND typeof(NEW.tag_id) = 'integer'
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:product_tags'); END;
        """,
    )

/** Las imágenes: un `sha256` en hexadecimal minúsculo, un tipo conocido y medidas dentro de los
 * límites de lectura. */
private val MEDIA_ASSETS_GUARDS =
    table(
        """
        CREATE TRIGGER guard_media_assets_insert BEFORE INSERT ON media_assets
        WHEN NOT IFNULL((
          length(NEW.sha256) = 64 AND NEW.sha256 NOT GLOB '*[^0-9a-f]*'
          AND NEW.kind IN ('PRODUCT_PHOTO', 'DOCUMENT_ATTACHMENT', 'PAYMENT_QR', 'BUSINESS_LOGO')
          AND typeof(NEW.mime_type) = 'text' AND length(NEW.mime_type) BETWEEN 1 AND 40
          AND typeof(NEW.width_px) = 'integer' AND NEW.width_px BETWEEN 1 AND 20000
          AND typeof(NEW.height_px) = 'integer' AND NEW.height_px BETWEEN 1 AND 20000
          AND typeof(NEW.size_bytes) = 'integer' AND NEW.size_bytes BETWEEN 1 AND 2097152
          AND typeof(NEW.created_at) = 'integer'
          AND (NEW.orphaned_at IS NULL OR (typeof(NEW.orphaned_at) = 'integer'))
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:media_assets'); END;

        CREATE TRIGGER guard_media_assets_update BEFORE UPDATE ON media_assets
        WHEN NOT IFNULL((
          length(NEW.sha256) = 64 AND NEW.sha256 NOT GLOB '*[^0-9a-f]*'
          AND NEW.kind IN ('PRODUCT_PHOTO', 'DOCUMENT_ATTACHMENT', 'PAYMENT_QR', 'BUSINESS_LOGO')
          AND typeof(NEW.mime_type) = 'text' AND length(NEW.mime_type) BETWEEN 1 AND 40
          AND typeof(NEW.width_px) = 'integer' AND NEW.width_px BETWEEN 1 AND 20000
          AND typeof(NEW.height_px) = 'integer' AND NEW.height_px BETWEEN 1 AND 20000
          AND typeof(NEW.size_bytes) = 'integer' AND NEW.size_bytes BETWEEN 1 AND 2097152
          AND typeof(NEW.created_at) = 'integer'
          AND (NEW.orphaned_at IS NULL OR (typeof(NEW.orphaned_at) = 'integer'))
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:media_assets'); END;
        """,
    )

/** Las unidades: la de clave `UNIT`, base por defecto de todo producto, nunca se archiva ni se
 * borra, y una unidad creada por la persona siempre tiene nombre. */
private val UNITS_GUARDS =
    table(
        """
        CREATE TRIGGER guard_units_insert BEFORE INSERT ON units
        WHEN NOT IFNULL((
          (NEW.system_key IS NULL OR (typeof(NEW.system_key) = 'text' AND length(NEW.system_key) BETWEEN 1 AND 40))
          AND (NEW.name IS NULL OR (typeof(NEW.name) = 'text' AND length(NEW.name) BETWEEN 1 AND 40))
          AND (NEW.normalized_name IS NULL OR (typeof(NEW.normalized_name) = 'text' AND length(NEW.normalized_name) BETWEEN 1 AND 40))
          AND (NEW.symbol IS NULL OR (typeof(NEW.symbol) = 'text' AND length(NEW.symbol) BETWEEN 1 AND 10))
          AND NEW.kind IN ('COUNT', 'MASS', 'VOLUME', 'LENGTH', 'OTHER')
          AND NEW.allows_fraction IN (0, 1)
          AND (NEW.archived_at IS NULL OR (typeof(NEW.archived_at) = 'integer'))
          AND typeof(NEW.created_at) = 'integer'
          AND typeof(NEW.updated_at) = 'integer'
          AND (NEW.system_key IS NOT NULL OR NEW.name IS NOT NULL)
          AND (NEW.name IS NULL) = (NEW.normalized_name IS NULL)
          AND (NEW.system_key IS NOT 'UNIT' OR NEW.archived_at IS NULL)
          AND (NEW.archived_at IS NOT NULL OR NEW.normalized_name IS NULL OR NOT EXISTS (SELECT 1 FROM units u WHERE u.normalized_name = NEW.normalized_name AND u.archived_at IS NULL))
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:units'); END;

        CREATE TRIGGER guard_units_update BEFORE UPDATE ON units
        WHEN NOT IFNULL((
          (NEW.system_key IS NULL OR (typeof(NEW.system_key) = 'text' AND length(NEW.system_key) BETWEEN 1 AND 40))
          AND (NEW.name IS NULL OR (typeof(NEW.name) = 'text' AND length(NEW.name) BETWEEN 1 AND 40))
          AND (NEW.normalized_name IS NULL OR (typeof(NEW.normalized_name) = 'text' AND length(NEW.normalized_name) BETWEEN 1 AND 40))
          AND (NEW.symbol IS NULL OR (typeof(NEW.symbol) = 'text' AND length(NEW.symbol) BETWEEN 1 AND 10))
          AND NEW.kind IN ('COUNT', 'MASS', 'VOLUME', 'LENGTH', 'OTHER')
          AND NEW.allows_fraction IN (0, 1)
          AND (NEW.archived_at IS NULL OR (typeof(NEW.archived_at) = 'integer'))
          AND typeof(NEW.created_at) = 'integer'
          AND typeof(NEW.updated_at) = 'integer'
          AND (NEW.system_key IS NOT NULL OR NEW.name IS NOT NULL)
          AND (NEW.name IS NULL) = (NEW.normalized_name IS NULL)
          AND (NEW.system_key IS NOT 'UNIT' OR NEW.archived_at IS NULL)
          AND (NEW.archived_at IS NOT NULL OR NEW.normalized_name IS NULL OR NOT EXISTS (SELECT 1 FROM units u WHERE u.normalized_name = NEW.normalized_name AND u.archived_at IS NULL AND u.id <> NEW.id))
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:units'); END;

        CREATE TRIGGER guard_units_no_delete BEFORE DELETE ON units
        WHEN OLD.system_key = 'UNIT'
        BEGIN SELECT RAISE(ABORT, 'guard:units_system'); END;
        """,
    )

/** Las conversiones de cada producto: factor de al menos una milésima de la unidad base, y un
 * código que no se repite entre conversiones ni con el de otro producto vigente. */
private val PRODUCT_UNITS_GUARDS =
    table(
        """
        CREATE TRIGGER guard_product_units_insert BEFORE INSERT ON product_units
        WHEN NOT IFNULL((
          typeof(NEW.factor_milli) = 'integer' AND NEW.factor_milli BETWEEN 1 AND 1000000000
          AND (NEW.sale_price IS NULL OR (typeof(NEW.sale_price) = 'integer' AND NEW.sale_price BETWEEN 0 AND 1000000000000))
          AND (NEW.barcode IS NULL OR (typeof(NEW.barcode) = 'text' AND length(NEW.barcode) BETWEEN 1 AND 64))
          AND NEW.for_sale IN (0, 1)
          AND NEW.for_purchase IN (0, 1)
          AND typeof(NEW.created_at) = 'integer'
          AND typeof(NEW.updated_at) = 'integer'
          AND (NEW.barcode IS NULL OR NOT EXISTS (SELECT 1 FROM products p WHERE p.barcode = NEW.barcode AND p.archived_at IS NULL AND p.id <> NEW.product_id))
          AND (NEW.barcode IS NULL OR NOT EXISTS (SELECT 1 FROM product_units u WHERE u.barcode = NEW.barcode))
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:product_units'); END;

        CREATE TRIGGER guard_product_units_update BEFORE UPDATE ON product_units
        WHEN NOT IFNULL((
          typeof(NEW.factor_milli) = 'integer' AND NEW.factor_milli BETWEEN 1 AND 1000000000
          AND (NEW.sale_price IS NULL OR (typeof(NEW.sale_price) = 'integer' AND NEW.sale_price BETWEEN 0 AND 1000000000000))
          AND (NEW.barcode IS NULL OR (typeof(NEW.barcode) = 'text' AND length(NEW.barcode) BETWEEN 1 AND 64))
          AND NEW.for_sale IN (0, 1)
          AND NEW.for_purchase IN (0, 1)
          AND typeof(NEW.created_at) = 'integer'
          AND typeof(NEW.updated_at) = 'integer'
          AND (NEW.barcode IS NULL OR NOT EXISTS (SELECT 1 FROM products p WHERE p.barcode = NEW.barcode AND p.archived_at IS NULL AND p.id <> NEW.product_id))
          AND (NEW.barcode IS NULL OR NOT EXISTS (SELECT 1 FROM product_units u WHERE u.barcode = NEW.barcode AND u.id <> NEW.id))
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:product_units'); END;
        """,
    )

/** El libro de stock: solo crece. Cada fila lleva el signo y el origen de su tipo, y una
 * compensación apunta a la fila que compensa, una sola vez.
 *
 * Sin la tabla de lotes no existe ningún lote: la fila no puede llevar uno, igual que con la
 * tabla vacía. Un producto con lotes no recibe filas con cantidad hasta que lleguen. */
private val STOCK_MOVEMENTS_GUARDS =
    table(
        """
        CREATE TRIGGER guard_stock_movements_insert BEFORE INSERT ON stock_movements
        WHEN NOT IFNULL((
          NEW.type IN ('OPENING_BALANCE', 'PURCHASE', 'SALE', 'ADJUSTMENT', 'COST_CORRECTION', 'PURCHASE_VOID', 'SALE_VOID', 'ADJUSTMENT_VOID', 'SALE_RETURN', 'SALE_RETURN_VOID', 'PURCHASE_RETURN', 'PURCHASE_RETURN_VOID', 'PRODUCTION_OUT', 'PRODUCTION_IN', 'PRODUCTION_VOID', 'TRANSFER_OUT', 'TRANSFER_IN', 'TRANSFER_VOID')
          AND (NEW.reason IS NULL OR (NEW.reason IN ('COUNT_CORRECTION', 'DAMAGED', 'EXPIRED', 'LOST_OR_STOLEN', 'OWN_USE', 'GIFT_OR_SAMPLE', 'OTHER')))
          AND typeof(NEW.quantity_milli) = 'integer'
          AND typeof(NEW.unit_cost) = 'integer' AND NEW.unit_cost BETWEEN 0 AND 1000000000000
          AND typeof(NEW.total_cost) = 'integer'
          AND typeof(NEW.average_cost_after) = 'integer' AND NEW.average_cost_after BETWEEN 0 AND 1000000000000
          AND typeof(NEW.stock_value_after) = 'integer' AND NEW.stock_value_after BETWEEN 0 AND 9000000000000000
          AND typeof(NEW.balance_after_milli) = 'integer'
          AND (NEW.note IS NULL OR (typeof(NEW.note) = 'text' AND length(NEW.note) BETWEEN 1 AND 200))
          AND typeof(NEW.occurred_at) = 'integer'
          AND typeof(NEW.created_at) = 'integer'
          AND date(NEW.business_date) = NEW.business_date
          AND EXISTS (SELECT 1 FROM products p WHERE p.id = NEW.product_id AND p.stock_mode = 'TRACKED' AND (NEW.quantity_milli = 0 OR (p.tracks_lots = 1) = (NEW.lot_id IS NOT NULL)))
          AND (NEW.lot_id IS NULL OR EXISTS (SELECT 1 FROM stock_lots l WHERE l.id = NEW.lot_id AND l.product_id = NEW.product_id))
          AND CASE
                WHEN NEW.type IN ('OPENING_BALANCE', 'PURCHASE', 'SALE_VOID', 'SALE_RETURN', 'PURCHASE_RETURN_VOID', 'PRODUCTION_IN', 'TRANSFER_IN') THEN NEW.quantity_milli > 0
                WHEN NEW.type IN ('SALE', 'PURCHASE_VOID', 'SALE_RETURN_VOID', 'PURCHASE_RETURN', 'PRODUCTION_OUT', 'TRANSFER_OUT') THEN NEW.quantity_milli < 0
                WHEN NEW.type = 'COST_CORRECTION' THEN NEW.quantity_milli = 0 AND NEW.total_cost = 0
                ELSE NEW.quantity_milli <> 0
              END
          AND (NEW.type NOT IN ('TRANSFER_OUT', 'TRANSFER_IN', 'TRANSFER_VOID') OR NEW.total_cost = 0)
          AND (NEW.type IN ('SALE', 'SALE_VOID')) = (NEW.sale_line_id IS NOT NULL)
          AND (NEW.type IN ('PURCHASE', 'PURCHASE_VOID')) = (NEW.purchase_line_id IS NOT NULL)
          AND (NEW.type IN ('SALE_RETURN', 'SALE_RETURN_VOID')) = (NEW.sale_return_line_id IS NOT NULL)
          AND (NEW.type IN ('PURCHASE_RETURN', 'PURCHASE_RETURN_VOID')) = (NEW.purchase_return_line_id IS NOT NULL)
          AND (NEW.type IN ('TRANSFER_OUT', 'TRANSFER_IN', 'TRANSFER_VOID')) = (NEW.transfer_line_id IS NOT NULL)
          AND (NEW.type IN ('PRODUCTION_OUT', 'PRODUCTION_IN', 'PRODUCTION_VOID')) = (NEW.production_batch_id IS NOT NULL)
          AND (NEW.count_session_id IS NULL OR (NEW.type = 'ADJUSTMENT' AND NEW.reason = 'COUNT_CORRECTION'))
          AND (NEW.sale_line_component_id IS NULL OR NEW.type IN ('SALE', 'SALE_VOID', 'SALE_RETURN', 'SALE_RETURN_VOID'))
          AND (NEW.type IN ('PURCHASE_VOID', 'SALE_VOID', 'ADJUSTMENT_VOID', 'SALE_RETURN_VOID', 'PURCHASE_RETURN_VOID', 'PRODUCTION_VOID', 'TRANSFER_VOID')) = (NEW.reverses_movement_id IS NOT NULL)
          AND (NEW.type = 'ADJUSTMENT') = (NEW.reason IS NOT NULL)
          AND (NEW.reason IS NULL OR NEW.reason IN ('COUNT_CORRECTION', 'OTHER') OR NEW.quantity_milli < 0)
          AND (NEW.reason IS NOT 'OTHER' OR NEW.note IS NOT NULL)
          AND (NEW.reverses_movement_id IS NULL OR EXISTS (
                SELECT 1 FROM stock_movements o
                WHERE o.id = NEW.reverses_movement_id AND o.product_id = NEW.product_id AND o.location_id = NEW.location_id
                  AND o.lot_id IS NEW.lot_id AND o.quantity_milli = -NEW.quantity_milli
                  AND ((NEW.type = 'SALE_VOID' AND o.type = 'SALE')
                    OR (NEW.type = 'PURCHASE_VOID' AND o.type = 'PURCHASE')
                    OR (NEW.type = 'ADJUSTMENT_VOID' AND o.type IN ('ADJUSTMENT', 'OPENING_BALANCE'))
                    OR (NEW.type = 'SALE_RETURN_VOID' AND o.type = 'SALE_RETURN')
                    OR (NEW.type = 'PURCHASE_RETURN_VOID' AND o.type = 'PURCHASE_RETURN')
                    OR (NEW.type = 'PRODUCTION_VOID' AND o.type IN ('PRODUCTION_OUT', 'PRODUCTION_IN'))
                    OR (NEW.type = 'TRANSFER_VOID' AND o.type IN ('TRANSFER_OUT', 'TRANSFER_IN')))
              ))
          AND NOT EXISTS (SELECT 1 FROM stock_movements m WHERE m.reverses_movement_id = NEW.reverses_movement_id)
          AND NOT EXISTS (SELECT 1 FROM stock_movements x WHERE x.id = NEW.id)
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:stock_movements'); END;

        CREATE TRIGGER guard_stock_movements_no_update BEFORE UPDATE ON stock_movements
        BEGIN SELECT RAISE(ABORT, 'guard:stock_movements_append_only'); END;

        CREATE TRIGGER guard_stock_movements_no_delete BEFORE DELETE ON stock_movements
        BEGIN SELECT RAISE(ABORT, 'guard:stock_movements_append_only'); END;
        """,
        Awaiting.Line(
            "guard_stock_movements_insert",
            "FROM stock_lots",
            listOf("stock_lots"),
            replacement = "  AND NEW.lot_id IS NULL",
        ),
    )

/** Las alertas de stock bajo: una sola activa por producto, y una resuelta no se reabre. */
private val LOW_STOCK_ALERTS_GUARDS =
    table(
        """
        CREATE TRIGGER guard_low_stock_alerts_insert BEFORE INSERT ON low_stock_alerts
        WHEN NOT IFNULL((
          typeof(NEW.on_hand_milli_at_trigger) = 'integer'
          AND typeof(NEW.min_stock_milli_at_trigger) = 'integer'
          AND NEW.notification_status IN ('PENDING', 'SHOWN', 'SUPPRESSED', 'BLOCKED')
          AND typeof(NEW.occurred_at) = 'integer'
          AND date(NEW.business_date) = NEW.business_date
          AND (NEW.resolved_at IS NULL OR (typeof(NEW.resolved_at) = 'integer'))
          AND NEW.resolved_at IS NULL
          AND EXISTS (SELECT 1 FROM products p WHERE p.id = NEW.product_id AND p.stock_mode = 'TRACKED')
          AND NOT EXISTS (SELECT 1 FROM low_stock_alerts a WHERE a.product_id = NEW.product_id AND a.resolved_at IS NULL)
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:low_stock_alerts'); END;

        CREATE TRIGGER guard_low_stock_alerts_update BEFORE UPDATE ON low_stock_alerts
        WHEN NOT IFNULL((
          typeof(NEW.on_hand_milli_at_trigger) = 'integer'
          AND typeof(NEW.min_stock_milli_at_trigger) = 'integer'
          AND NEW.notification_status IN ('PENDING', 'SHOWN', 'SUPPRESSED', 'BLOCKED')
          AND typeof(NEW.occurred_at) = 'integer'
          AND date(NEW.business_date) = NEW.business_date
          AND (NEW.resolved_at IS NULL OR (typeof(NEW.resolved_at) = 'integer'))
          AND NEW.id = OLD.id AND NEW.product_id = OLD.product_id
          AND (OLD.resolved_at IS NULL OR NEW.resolved_at IS OLD.resolved_at)
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:low_stock_alerts'); END;
        """,
    )

/** Las compras: nacen activas, solo cambian al anularse y nunca se borran.
 *
 * Mientras no exista la columna que bloquea la moneda, que llega con la configuración
 * regional, no se exige la moneda confirmada antes del primer registro con dinero. Es la única
 * parte de estas guardas que hoy protege menos que su texto completo. */
private val PURCHASES_GUARDS =
    table(
        """
        CREATE TRIGGER guard_purchases_insert BEFORE INSERT ON purchases
        WHEN NOT IFNULL((
          NEW.status = 'ACTIVE' AND NEW.voided_at IS NULL AND NEW.void_reason IS NULL AND NEW.void_note IS NULL
          AND (NEW.invoice_reference IS NULL OR (typeof(NEW.invoice_reference) = 'text' AND length(NEW.invoice_reference) BETWEEN 1 AND 40))
          AND typeof(NEW.net_total) = 'integer' AND NEW.net_total BETWEEN 0 AND 1000000000000000
          AND typeof(NEW.tax_total) = 'integer' AND NEW.tax_total BETWEEN 0 AND 1000000000000000
          AND typeof(NEW.total) = 'integer' AND NEW.total BETWEEN 0 AND 1000000000000000
          AND typeof(NEW.cost_total) = 'integer' AND NEW.cost_total BETWEEN 0 AND 1000000000000000
          AND typeof(NEW.amount_paid) = 'integer' AND NEW.amount_paid BETWEEN 0 AND 1000000000000000
          AND (NEW.due_date IS NULL OR (date(NEW.due_date) = NEW.due_date))
          AND (NEW.fx_currency_code IS NULL OR (length(NEW.fx_currency_code) = 3 AND NEW.fx_currency_code = upper(NEW.fx_currency_code) AND NEW.fx_currency_code NOT GLOB '*[^A-Z]*'))
          AND (NEW.fx_rate_micro IS NULL OR (typeof(NEW.fx_rate_micro) = 'integer' AND NEW.fx_rate_micro BETWEEN 1 AND 1000000000000000))
          AND typeof(NEW.occurred_at) = 'integer'
          AND typeof(NEW.created_at) = 'integer'
          AND date(NEW.business_date) = NEW.business_date
          AND (SELECT currency_locked_at FROM app_settings WHERE id = 1) IS NOT NULL
          AND NEW.total = NEW.net_total + NEW.tax_total
          AND NEW.amount_paid <= NEW.total
          AND (NEW.amount_paid = NEW.total OR NEW.supplier_id IS NOT NULL)
          AND (NEW.fx_currency_code IS NULL) = (NEW.fx_rate_micro IS NULL)
          AND NOT EXISTS (SELECT 1 FROM purchases x WHERE x.id = NEW.id)
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:purchases'); END;

        CREATE TRIGGER guard_purchases_void_only BEFORE UPDATE ON purchases
        WHEN NOT IFNULL((
          OLD.status = 'ACTIVE' AND NEW.status = 'VOIDED' AND NEW.voided_at IS NOT NULL
          AND NEW.void_reason IN ('ENTRY_ERROR', 'SUPPLIER_RETURN', 'UNDO', 'OTHER')
          AND (NEW.void_note IS NULL OR length(NEW.void_note) <= 200)
          AND NEW.id IS OLD.id AND NEW.supplier_id IS OLD.supplier_id AND NEW.invoice_reference IS OLD.invoice_reference AND NEW.net_total IS OLD.net_total AND NEW.tax_total IS OLD.tax_total AND NEW.total IS OLD.total
          AND NEW.cost_total IS OLD.cost_total AND NEW.amount_paid IS OLD.amount_paid AND NEW.money_account_id IS OLD.money_account_id AND NEW.due_date IS OLD.due_date AND NEW.location_id IS OLD.location_id AND NEW.fx_currency_code IS OLD.fx_currency_code
          AND NEW.fx_rate_micro IS OLD.fx_rate_micro AND NEW.occurred_at IS OLD.occurred_at AND NEW.created_at IS OLD.created_at AND NEW.business_date IS OLD.business_date
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:purchases_void_only'); END;

        CREATE TRIGGER guard_purchases_no_delete BEFORE DELETE ON purchases
        BEGIN SELECT RAISE(ABORT, 'guard:purchases_no_delete'); END;
        """,
        awaitingCurrencyLock("guard_purchases_insert"),
    )

/** Las líneas de compra: no cambian ni se borran. */
private val PURCHASE_LINES_GUARDS =
    table(
        """
        CREATE TRIGGER guard_purchase_lines_insert BEFORE INSERT ON purchase_lines
        WHEN NOT IFNULL((
          typeof(NEW.unit_factor_milli) = 'integer' AND NEW.unit_factor_milli BETWEEN 1 AND 1000000000
          AND typeof(NEW.entered_quantity_milli) = 'integer' AND NEW.entered_quantity_milli BETWEEN 1 AND 1000000000
          AND typeof(NEW.quantity_milli) = 'integer' AND NEW.quantity_milli BETWEEN 1 AND 1000000000
          AND typeof(NEW.unit_cost) = 'integer' AND NEW.unit_cost BETWEEN 0 AND 1000000000000
          AND typeof(NEW.line_total) = 'integer' AND NEW.line_total BETWEEN 0 AND 1000000000000000
          AND typeof(NEW.tax_rate_bp) = 'integer' AND NEW.tax_rate_bp BETWEEN 0 AND 10000
          AND (NEW.tax_name_snapshot IS NULL OR (typeof(NEW.tax_name_snapshot) = 'text' AND length(NEW.tax_name_snapshot) BETWEEN 1 AND 40))
          AND typeof(NEW.tax_amount) = 'integer' AND NEW.tax_amount BETWEEN 0 AND 1000000000000000
          AND typeof(NEW.cost_amount) = 'integer' AND NEW.cost_amount BETWEEN 0 AND 1000000000000000
          AND (NEW.reference_unit_cost IS NULL OR (typeof(NEW.reference_unit_cost) = 'integer' AND NEW.reference_unit_cost BETWEEN 0 AND 1000000000000))
          AND NOT EXISTS (SELECT 1 FROM purchase_lines x WHERE x.id = NEW.id)
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:purchase_lines'); END;

        CREATE TRIGGER guard_purchase_lines_no_update BEFORE UPDATE ON purchase_lines
        BEGIN SELECT RAISE(ABORT, 'guard:purchase_lines_append_only'); END;

        CREATE TRIGGER guard_purchase_lines_no_delete BEFORE DELETE ON purchase_lines
        BEGIN SELECT RAISE(ABORT, 'guard:purchase_lines_append_only'); END;
        """,
    )

/** El libro del proveedor: solo crece. Cada fila lleva el signo y el origen de su tipo, y una
 * compensación apunta a una fila del mismo proveedor, con el monto opuesto, una sola vez.
 *
 * Mientras no exista la columna que bloquea la moneda, que llega con la configuración
 * regional, no se exige la moneda confirmada antes del primer registro con dinero. Es la única
 * parte de estas guardas que hoy protege menos que su texto completo. */
private val SUPPLIER_LEDGER_ENTRIES_GUARDS =
    table(
        """
        CREATE TRIGGER guard_supplier_ledger_entries_insert BEFORE INSERT ON supplier_ledger_entries
        WHEN NOT IFNULL((
          NEW.type IN ('OPENING_BALANCE', 'CREDIT_PURCHASE', 'PAYMENT', 'RETURN_CREDIT', 'ADJUSTMENT', 'ENTRY_VOID')
          AND typeof(NEW.amount) = 'integer'
          AND typeof(NEW.balance_after) = 'integer'
          AND (NEW.due_date IS NULL OR (date(NEW.due_date) = NEW.due_date))
          AND (NEW.note IS NULL OR (typeof(NEW.note) = 'text' AND length(NEW.note) BETWEEN 1 AND 200))
          AND typeof(NEW.occurred_at) = 'integer'
          AND date(NEW.business_date) = NEW.business_date
          AND typeof(NEW.created_at) = 'integer'
          AND (SELECT currency_locked_at FROM app_settings WHERE id = 1) IS NOT NULL
          AND NEW.amount <> 0
          AND CASE NEW.type WHEN 'CREDIT_PURCHASE' THEN NEW.amount > 0 WHEN 'PAYMENT' THEN NEW.amount < 0 WHEN 'RETURN_CREDIT' THEN NEW.amount < 0 ELSE 1 END
          AND (NEW.type = 'CREDIT_PURCHASE') = (NEW.purchase_id IS NOT NULL)
          AND (NEW.type = 'RETURN_CREDIT') = (NEW.purchase_return_id IS NOT NULL)
          AND (NEW.type = 'ENTRY_VOID') = (NEW.reverses_entry_id IS NOT NULL)
          AND (NEW.type <> 'ADJUSTMENT' OR NEW.note IS NOT NULL)
          AND (NEW.reverses_entry_id IS NULL OR EXISTS (SELECT 1 FROM supplier_ledger_entries o WHERE o.id = NEW.reverses_entry_id AND o.supplier_id = NEW.supplier_id AND o.amount = -NEW.amount AND o.type <> 'ENTRY_VOID'))
          AND NOT EXISTS (SELECT 1 FROM supplier_ledger_entries e WHERE e.reverses_entry_id = NEW.reverses_entry_id)
          AND NOT EXISTS (SELECT 1 FROM supplier_ledger_entries x WHERE x.id = NEW.id)
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:supplier_ledger_entries'); END;

        CREATE TRIGGER guard_supplier_ledger_entries_no_update BEFORE UPDATE ON supplier_ledger_entries
        BEGIN SELECT RAISE(ABORT, 'guard:supplier_ledger_entries_append_only'); END;

        CREATE TRIGGER guard_supplier_ledger_entries_no_delete BEFORE DELETE ON supplier_ledger_entries
        BEGIN SELECT RAISE(ABORT, 'guard:supplier_ledger_entries_append_only'); END;
        """,
        awaitingCurrencyLock("guard_supplier_ledger_entries_insert"),
    )

/** Las ventas: nacen activas, solo cambian al anularse y nunca se borran.
 *
 * Mientras no exista la columna que bloquea la moneda, que llega con la configuración
 * regional, no se exige la moneda confirmada antes del primer registro con dinero. Es la única
 * parte de estas guardas que hoy protege menos que su texto completo.
 *
 * Sin devoluciones de venta no hay ninguna activa que impida anular: quitar esa condición da lo
 * mismo que tener su tabla vacía. */
private val SALES_GUARDS =
    table(
        """
        CREATE TRIGGER guard_sales_insert BEFORE INSERT ON sales
        WHEN NOT IFNULL((
          NEW.status = 'ACTIVE' AND NEW.voided_at IS NULL AND NEW.void_reason IS NULL AND NEW.void_note IS NULL
          AND typeof(NEW.net_total) = 'integer' AND NEW.net_total BETWEEN 0 AND 1000000000000000
          AND typeof(NEW.tax_total) = 'integer' AND NEW.tax_total BETWEEN 0 AND 1000000000000000
          AND typeof(NEW.total) = 'integer' AND NEW.total BETWEEN 0 AND 1000000000000000
          AND typeof(NEW.total_cost) = 'integer' AND NEW.total_cost BETWEEN 0 AND 1000000000000000
          AND typeof(NEW.total_replacement_cost) = 'integer' AND NEW.total_replacement_cost BETWEEN 0 AND 1000000000000000
          AND typeof(NEW.order_adjustment) = 'integer' AND NEW.order_adjustment BETWEEN -1000000000000000 AND 1000000000000000
          AND typeof(NEW.tip_amount) = 'integer' AND NEW.tip_amount BETWEEN 0 AND 1000000000000000
          AND typeof(NEW.cash_rounding_amount) = 'integer' AND NEW.cash_rounding_amount BETWEEN -1000000000000 AND 1000000000000
          AND typeof(NEW.amount_due) = 'integer' AND NEW.amount_due BETWEEN 0 AND 1000000000000000
          AND (NEW.note IS NULL OR (typeof(NEW.note) = 'text' AND length(NEW.note) BETWEEN 1 AND 200))
          AND typeof(NEW.occurred_at) = 'integer'
          AND typeof(NEW.created_at) = 'integer'
          AND date(NEW.business_date) = NEW.business_date
          AND (SELECT currency_locked_at FROM app_settings WHERE id = 1) IS NOT NULL
          AND NEW.total = NEW.net_total + NEW.tax_total
          AND NEW.amount_due = NEW.total + NEW.tip_amount + NEW.cash_rounding_amount
          AND NOT EXISTS (SELECT 1 FROM sales x WHERE x.id = NEW.id)
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:sales'); END;

        CREATE TRIGGER guard_sales_void_only BEFORE UPDATE ON sales
        WHEN NOT IFNULL((
          OLD.status = 'ACTIVE' AND NEW.status = 'VOIDED' AND NEW.voided_at IS NOT NULL
          AND NEW.void_reason IN ('ENTRY_ERROR', 'CUSTOMER_RETURN', 'UNDO', 'OTHER')
          AND (NEW.void_note IS NULL OR length(NEW.void_note) <= 200)
          AND NEW.id IS OLD.id AND NEW.net_total IS OLD.net_total AND NEW.tax_total IS OLD.tax_total AND NEW.total IS OLD.total AND NEW.total_cost IS OLD.total_cost AND NEW.total_replacement_cost IS OLD.total_replacement_cost
          AND NEW.order_adjustment IS OLD.order_adjustment AND NEW.tip_amount IS OLD.tip_amount AND NEW.cash_rounding_amount IS OLD.cash_rounding_amount AND NEW.amount_due IS OLD.amount_due AND NEW.customer_id IS OLD.customer_id AND NEW.price_list_id IS OLD.price_list_id
          AND NEW.location_id IS OLD.location_id AND NEW.cash_session_id IS OLD.cash_session_id AND NEW.quote_id IS OLD.quote_id AND NEW.order_id IS OLD.order_id AND NEW.note IS OLD.note AND NEW.occurred_at IS OLD.occurred_at
          AND NEW.created_at IS OLD.created_at AND NEW.business_date IS OLD.business_date
          AND NOT EXISTS (SELECT 1 FROM sale_returns r WHERE r.sale_id = NEW.id AND r.status = 'ACTIVE')
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:sales_void_only'); END;

        CREATE TRIGGER guard_sales_no_delete BEFORE DELETE ON sales
        BEGIN SELECT RAISE(ABORT, 'guard:sales_no_delete'); END;
        """,
        awaitingCurrencyLock("guard_sales_insert"),
        Awaiting.Line("guard_sales_void_only", "FROM sale_returns", listOf("sale_returns")),
    )

/** Las líneas de venta: no cambian ni se borran. */
private val SALE_LINES_GUARDS =
    table(
        """
        CREATE TRIGGER guard_sale_lines_insert BEFORE INSERT ON sale_lines
        WHEN NOT IFNULL((
          typeof(NEW.unit_factor_milli) = 'integer' AND NEW.unit_factor_milli BETWEEN 1 AND 1000000000
          AND typeof(NEW.entered_quantity_milli) = 'integer' AND NEW.entered_quantity_milli BETWEEN 1 AND 1000000000
          AND typeof(NEW.quantity_milli) = 'integer' AND NEW.quantity_milli BETWEEN 1 AND 1000000000
          AND typeof(NEW.unit_price) = 'integer' AND NEW.unit_price BETWEEN 0 AND 1000000000000
          AND typeof(NEW.unit_cost) = 'integer' AND NEW.unit_cost BETWEEN 0 AND 1000000000000
          AND typeof(NEW.line_discount) = 'integer' AND NEW.line_discount BETWEEN 0 AND 1000000000000000
          AND typeof(NEW.order_adjustment_share) = 'integer' AND NEW.order_adjustment_share BETWEEN -1000000000000000 AND 1000000000000000
          AND typeof(NEW.net_amount) = 'integer' AND NEW.net_amount BETWEEN 0 AND 1000000000000000
          AND typeof(NEW.tax_rate_bp) = 'integer' AND NEW.tax_rate_bp BETWEEN 0 AND 10000
          AND (NEW.tax_name_snapshot IS NULL OR (typeof(NEW.tax_name_snapshot) = 'text' AND length(NEW.tax_name_snapshot) BETWEEN 1 AND 40))
          AND typeof(NEW.tax_amount) = 'integer' AND NEW.tax_amount BETWEEN 0 AND 1000000000000000
          AND typeof(NEW.line_total) = 'integer' AND NEW.line_total BETWEEN 0 AND 1000000000000000
          AND typeof(NEW.line_cost) = 'integer' AND NEW.line_cost BETWEEN 0 AND 1000000000000000
          AND typeof(NEW.unit_replacement_cost) = 'integer' AND NEW.unit_replacement_cost BETWEEN 0 AND 1000000000000
          AND typeof(NEW.line_replacement_cost) = 'integer' AND NEW.line_replacement_cost BETWEEN 0 AND 1000000000000000
          AND typeof(NEW.product_name_snapshot) = 'text' AND length(NEW.product_name_snapshot) BETWEEN 1 AND 120
          AND NEW.stock_effect IN ('DIRECT', 'NONE', 'COMPONENTS')
          AND NEW.line_total = NEW.net_amount + NEW.tax_amount
          AND EXISTS (SELECT 1 FROM products p WHERE p.id = NEW.product_id AND ((NEW.stock_effect = 'DIRECT' AND p.stock_mode = 'TRACKED') OR (NEW.stock_effect = 'NONE' AND p.stock_mode = 'UNTRACKED') OR (NEW.stock_effect = 'COMPONENTS' AND p.stock_mode = 'COMPONENTS')))
          AND NOT EXISTS (SELECT 1 FROM sale_lines x WHERE x.id = NEW.id)
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:sale_lines'); END;

        CREATE TRIGGER guard_sale_lines_no_update BEFORE UPDATE ON sale_lines
        BEGIN SELECT RAISE(ABORT, 'guard:sale_lines_append_only'); END;

        CREATE TRIGGER guard_sale_lines_no_delete BEFORE DELETE ON sale_lines
        BEGIN SELECT RAISE(ABORT, 'guard:sale_lines_append_only'); END;
        """,
    )

/** Las tasas de impuesto: la tasa va de 0 a 10.000 puntos básicos. El nombre único lo impone
 * su índice, no la guarda. */
private val TAX_RATES_GUARDS =
    table(
        """
        CREATE TRIGGER guard_tax_rates_insert BEFORE INSERT ON tax_rates
        WHEN NOT IFNULL((
          typeof(NEW.name) = 'text' AND length(NEW.name) BETWEEN 1 AND 40
          AND typeof(NEW.normalized_name) = 'text' AND length(NEW.normalized_name) BETWEEN 1 AND 40
          AND typeof(NEW.rate_bp) = 'integer' AND NEW.rate_bp BETWEEN 0 AND 10000
          AND (NEW.archived_at IS NULL OR (typeof(NEW.archived_at) = 'integer'))
          AND typeof(NEW.created_at) = 'integer'
          AND typeof(NEW.updated_at) = 'integer'
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:tax_rates'); END;

        CREATE TRIGGER guard_tax_rates_update BEFORE UPDATE ON tax_rates
        WHEN NOT IFNULL((
          typeof(NEW.name) = 'text' AND length(NEW.name) BETWEEN 1 AND 40
          AND typeof(NEW.normalized_name) = 'text' AND length(NEW.normalized_name) BETWEEN 1 AND 40
          AND typeof(NEW.rate_bp) = 'integer' AND NEW.rate_bp BETWEEN 0 AND 10000
          AND (NEW.archived_at IS NULL OR (typeof(NEW.archived_at) = 'integer'))
          AND typeof(NEW.created_at) = 'integer'
          AND typeof(NEW.updated_at) = 'integer'
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:tax_rates'); END;
        """,
    )

/** Los componentes vendidos de un combo: no cambian ni se borran. Son de una línea de combo, y
 * cada hoja lleva libro de stock si y solo si se marcó así al vender. */
private val SALE_LINE_COMPONENTS_GUARDS =
    table(
        """
        CREATE TRIGGER guard_sale_line_components_insert BEFORE INSERT ON sale_line_components
        WHEN NOT IFNULL((
          typeof(NEW.quantity_milli) = 'integer' AND NEW.quantity_milli BETWEEN 1 AND 1000000000
          AND NEW.tracked IN (0, 1)
          AND typeof(NEW.unit_cost) = 'integer' AND NEW.unit_cost BETWEEN 0 AND 1000000000000
          AND typeof(NEW.line_cost) = 'integer' AND NEW.line_cost BETWEEN 0 AND 1000000000000000
          AND EXISTS (SELECT 1 FROM sale_lines l WHERE l.id = NEW.sale_line_id AND l.stock_effect = 'COMPONENTS')
          AND EXISTS (SELECT 1 FROM products p WHERE p.id = NEW.component_product_id AND p.stock_mode <> 'COMPONENTS' AND (p.stock_mode = 'TRACKED') = (NEW.tracked = 1))
          AND NOT EXISTS (SELECT 1 FROM sale_line_components x WHERE x.id = NEW.id)
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:sale_line_components'); END;

        CREATE TRIGGER guard_sale_line_components_no_update BEFORE UPDATE ON sale_line_components
        BEGIN SELECT RAISE(ABORT, 'guard:sale_line_components_append_only'); END;

        CREATE TRIGGER guard_sale_line_components_no_delete BEFORE DELETE ON sale_line_components
        BEGIN SELECT RAISE(ABORT, 'guard:sale_line_components_append_only'); END;
        """,
    )

/** Los clientes: solo el nombre es obligatorio, y puede faltar solo en uno anonimizado, que ya
 * no guarda ningún dato personal y no deja de estarlo. */
private val CUSTOMERS_GUARDS =
    table(
        """
        CREATE TRIGGER guard_customers_insert BEFORE INSERT ON customers
        WHEN NOT IFNULL((
          (NEW.name IS NULL OR (typeof(NEW.name) = 'text' AND length(NEW.name) BETWEEN 1 AND 80))
          AND (NEW.normalized_name IS NULL OR (typeof(NEW.normalized_name) = 'text' AND length(NEW.normalized_name) BETWEEN 1 AND 80))
          AND (NEW.phone IS NULL OR (typeof(NEW.phone) = 'text' AND length(NEW.phone) BETWEEN 1 AND 30))
          AND (NEW.email IS NULL OR (typeof(NEW.email) = 'text' AND length(NEW.email) BETWEEN 1 AND 120))
          AND (NEW.notes IS NULL OR (typeof(NEW.notes) = 'text' AND length(NEW.notes) BETWEEN 1 AND 500))
          AND typeof(NEW.balance) = 'integer'
          AND typeof(NEW.points_balance) = 'integer' AND NEW.points_balance BETWEEN -1000000000000 AND 1000000000000
          AND (NEW.anonymized_at IS NULL OR (typeof(NEW.anonymized_at) = 'integer'))
          AND (NEW.archived_at IS NULL OR (typeof(NEW.archived_at) = 'integer'))
          AND typeof(NEW.created_at) = 'integer'
          AND typeof(NEW.updated_at) = 'integer'
          AND (NEW.name IS NULL) = (NEW.normalized_name IS NULL)
          AND (NEW.name IS NOT NULL OR NEW.anonymized_at IS NOT NULL)
          AND (NEW.anonymized_at IS NULL OR (NEW.name IS NULL AND NEW.phone IS NULL AND NEW.email IS NULL AND NEW.notes IS NULL))
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:customers'); END;

        CREATE TRIGGER guard_customers_update BEFORE UPDATE ON customers
        WHEN NOT IFNULL((
          (NEW.name IS NULL OR (typeof(NEW.name) = 'text' AND length(NEW.name) BETWEEN 1 AND 80))
          AND (NEW.normalized_name IS NULL OR (typeof(NEW.normalized_name) = 'text' AND length(NEW.normalized_name) BETWEEN 1 AND 80))
          AND (NEW.phone IS NULL OR (typeof(NEW.phone) = 'text' AND length(NEW.phone) BETWEEN 1 AND 30))
          AND (NEW.email IS NULL OR (typeof(NEW.email) = 'text' AND length(NEW.email) BETWEEN 1 AND 120))
          AND (NEW.notes IS NULL OR (typeof(NEW.notes) = 'text' AND length(NEW.notes) BETWEEN 1 AND 500))
          AND typeof(NEW.balance) = 'integer'
          AND typeof(NEW.points_balance) = 'integer' AND NEW.points_balance BETWEEN -1000000000000 AND 1000000000000
          AND (NEW.anonymized_at IS NULL OR (typeof(NEW.anonymized_at) = 'integer'))
          AND (NEW.archived_at IS NULL OR (typeof(NEW.archived_at) = 'integer'))
          AND typeof(NEW.created_at) = 'integer'
          AND typeof(NEW.updated_at) = 'integer'
          AND (NEW.name IS NULL) = (NEW.normalized_name IS NULL)
          AND (NEW.name IS NOT NULL OR NEW.anonymized_at IS NOT NULL)
          AND (NEW.anonymized_at IS NULL OR (NEW.name IS NULL AND NEW.phone IS NULL AND NEW.email IS NULL AND NEW.notes IS NULL))
          AND (OLD.anonymized_at IS NULL OR NEW.anonymized_at IS OLD.anonymized_at)
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:customers'); END;
        """,
    )

/** El libro del cliente: solo crece. Cada fila lleva el signo y el origen de su tipo, y una
 * compensación apunta a una fila del mismo cliente, con el monto opuesto, una sola vez.
 *
 * Mientras no exista la columna que bloquea la moneda, que llega con la configuración
 * regional, no se exige la moneda confirmada antes del primer registro con dinero. Es la única
 * parte de estas guardas que hoy protege menos que su texto completo. */
private val CUSTOMER_LEDGER_ENTRIES_GUARDS =
    table(
        """
        CREATE TRIGGER guard_customer_ledger_entries_insert BEFORE INSERT ON customer_ledger_entries
        WHEN NOT IFNULL((
          NEW.type IN ('OPENING_BALANCE', 'CREDIT_SALE', 'PAYMENT', 'RETURN_CREDIT', 'ADJUSTMENT', 'ENTRY_VOID')
          AND typeof(NEW.amount) = 'integer'
          AND typeof(NEW.balance_after) = 'integer'
          AND (NEW.due_date IS NULL OR (date(NEW.due_date) = NEW.due_date))
          AND (NEW.note IS NULL OR (typeof(NEW.note) = 'text' AND length(NEW.note) BETWEEN 1 AND 200))
          AND typeof(NEW.occurred_at) = 'integer'
          AND date(NEW.business_date) = NEW.business_date
          AND typeof(NEW.created_at) = 'integer'
          AND (SELECT currency_locked_at FROM app_settings WHERE id = 1) IS NOT NULL
          AND NEW.amount <> 0
          AND CASE NEW.type WHEN 'CREDIT_SALE' THEN NEW.amount > 0 WHEN 'PAYMENT' THEN NEW.amount < 0 WHEN 'RETURN_CREDIT' THEN NEW.amount < 0 ELSE 1 END
          AND (NEW.type = 'CREDIT_SALE') = (NEW.sale_id IS NOT NULL)
          AND (NEW.type = 'RETURN_CREDIT') = (NEW.sale_return_id IS NOT NULL)
          AND (NEW.type = 'ENTRY_VOID') = (NEW.reverses_entry_id IS NOT NULL)
          AND (NEW.type <> 'ADJUSTMENT' OR NEW.note IS NOT NULL)
          AND (NEW.reverses_entry_id IS NULL OR EXISTS (SELECT 1 FROM customer_ledger_entries o WHERE o.id = NEW.reverses_entry_id AND o.customer_id = NEW.customer_id AND o.amount = -NEW.amount AND o.type <> 'ENTRY_VOID'))
          AND NOT EXISTS (SELECT 1 FROM customer_ledger_entries e WHERE e.reverses_entry_id = NEW.reverses_entry_id)
          AND NOT EXISTS (SELECT 1 FROM customer_ledger_entries x WHERE x.id = NEW.id)
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:customer_ledger_entries'); END;

        CREATE TRIGGER guard_customer_ledger_entries_no_update BEFORE UPDATE ON customer_ledger_entries
        BEGIN SELECT RAISE(ABORT, 'guard:customer_ledger_entries_append_only'); END;

        CREATE TRIGGER guard_customer_ledger_entries_no_delete BEFORE DELETE ON customer_ledger_entries
        BEGIN SELECT RAISE(ABORT, 'guard:customer_ledger_entries_append_only'); END;
        """,
        awaitingCurrencyLock("guard_customer_ledger_entries_insert"),
    )

/** El libro de puntos: solo crece, con el mismo patrón que el del cliente. Los puntos no son
 * dinero, así que no esperan la moneda confirmada. */
private val LOYALTY_LEDGER_ENTRIES_GUARDS =
    table(
        """
        CREATE TRIGGER guard_loyalty_ledger_entries_insert BEFORE INSERT ON loyalty_ledger_entries
        WHEN NOT IFNULL((
          NEW.type IN ('EARN', 'REDEEM', 'ADJUSTMENT', 'ENTRY_VOID')
          AND typeof(NEW.points) = 'integer' AND NEW.points BETWEEN -1000000000000 AND 1000000000000
          AND typeof(NEW.balance_after) = 'integer' AND NEW.balance_after BETWEEN -1000000000000 AND 1000000000000
          AND (NEW.note IS NULL OR (typeof(NEW.note) = 'text' AND length(NEW.note) BETWEEN 1 AND 200))
          AND typeof(NEW.occurred_at) = 'integer'
          AND date(NEW.business_date) = NEW.business_date
          AND typeof(NEW.created_at) = 'integer'
          AND NEW.points <> 0
          AND CASE NEW.type WHEN 'EARN' THEN NEW.points > 0 WHEN 'REDEEM' THEN NEW.points < 0 ELSE 1 END
          AND (NEW.type IN ('EARN', 'REDEEM')) = (NEW.sale_id IS NOT NULL)
          AND (NEW.type = 'ENTRY_VOID') = (NEW.reverses_entry_id IS NOT NULL)
          AND (NEW.reverses_entry_id IS NULL OR EXISTS (SELECT 1 FROM loyalty_ledger_entries o WHERE o.id = NEW.reverses_entry_id AND o.customer_id = NEW.customer_id AND o.points = -NEW.points AND o.type <> 'ENTRY_VOID'))
          AND NOT EXISTS (SELECT 1 FROM loyalty_ledger_entries e WHERE e.reverses_entry_id = NEW.reverses_entry_id)
          AND NOT EXISTS (SELECT 1 FROM loyalty_ledger_entries x WHERE x.id = NEW.id)
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:loyalty_ledger_entries'); END;

        CREATE TRIGGER guard_loyalty_ledger_entries_no_update BEFORE UPDATE ON loyalty_ledger_entries
        BEGIN SELECT RAISE(ABORT, 'guard:loyalty_ledger_entries_append_only'); END;

        CREATE TRIGGER guard_loyalty_ledger_entries_no_delete BEFORE DELETE ON loyalty_ledger_entries
        BEGIN SELECT RAISE(ABORT, 'guard:loyalty_ledger_entries_append_only'); END;
        """,
    )

/** Las plantillas de mensaje: una clave conocida, un idioma y un texto con su largo. */
private val MESSAGE_TEMPLATES_GUARDS =
    table(
        """
        CREATE TRIGGER guard_message_templates_insert BEFORE INSERT ON message_templates
        WHEN NOT IFNULL((
          NEW.template_key IN ('PAYMENT_REMINDER')
          AND typeof(NEW.language_tag) = 'text' AND length(NEW.language_tag) BETWEEN 1 AND 8
          AND typeof(NEW.body) = 'text' AND length(NEW.body) BETWEEN 1 AND 1000
          AND typeof(NEW.updated_at) = 'integer'
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:message_templates'); END;

        CREATE TRIGGER guard_message_templates_update BEFORE UPDATE ON message_templates
        WHEN NOT IFNULL((
          NEW.template_key IN ('PAYMENT_REMINDER')
          AND typeof(NEW.language_tag) = 'text' AND length(NEW.language_tag) BETWEEN 1 AND 8
          AND typeof(NEW.body) = 'text' AND length(NEW.body) BETWEEN 1 AND 1000
          AND typeof(NEW.updated_at) = 'integer'
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:message_templates'); END;
        """,
    )

/** Las cuentas de dinero: una clase conocida, una moneda de tres letras mayúsculas con sus
 * decimales, y un nombre o una clave inicial. Con movimientos, la moneda ya no cambia. */
private val MONEY_ACCOUNTS_GUARDS =
    table(
        """
        CREATE TRIGGER guard_money_accounts_insert BEFORE INSERT ON money_accounts
        WHEN NOT IFNULL((
          (NEW.system_key IS NULL OR (typeof(NEW.system_key) = 'text' AND length(NEW.system_key) BETWEEN 1 AND 40))
          AND (NEW.name IS NULL OR (typeof(NEW.name) = 'text' AND length(NEW.name) BETWEEN 1 AND 40))
          AND (NEW.normalized_name IS NULL OR (typeof(NEW.normalized_name) = 'text' AND length(NEW.normalized_name) BETWEEN 1 AND 40))
          AND NEW.kind IN ('CASH', 'BANK', 'WALLET', 'OTHER')
          AND length(NEW.currency_code) = 3 AND NEW.currency_code = upper(NEW.currency_code) AND NEW.currency_code NOT GLOB '*[^A-Z]*'
          AND typeof(NEW.currency_decimals) = 'integer' AND NEW.currency_decimals BETWEEN 0 AND 4
          AND typeof(NEW.balance) = 'integer'
          AND (NEW.archived_at IS NULL OR (typeof(NEW.archived_at) = 'integer'))
          AND typeof(NEW.created_at) = 'integer'
          AND typeof(NEW.updated_at) = 'integer'
          AND (NEW.system_key IS NOT NULL OR NEW.name IS NOT NULL)
          AND (NEW.name IS NULL) = (NEW.normalized_name IS NULL)
          AND (NEW.archived_at IS NOT NULL OR NEW.normalized_name IS NULL OR NOT EXISTS (SELECT 1 FROM money_accounts a WHERE a.normalized_name = NEW.normalized_name AND a.archived_at IS NULL))
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:money_accounts'); END;

        CREATE TRIGGER guard_money_accounts_update BEFORE UPDATE ON money_accounts
        WHEN NOT IFNULL((
          (NEW.system_key IS NULL OR (typeof(NEW.system_key) = 'text' AND length(NEW.system_key) BETWEEN 1 AND 40))
          AND (NEW.name IS NULL OR (typeof(NEW.name) = 'text' AND length(NEW.name) BETWEEN 1 AND 40))
          AND (NEW.normalized_name IS NULL OR (typeof(NEW.normalized_name) = 'text' AND length(NEW.normalized_name) BETWEEN 1 AND 40))
          AND NEW.kind IN ('CASH', 'BANK', 'WALLET', 'OTHER')
          AND length(NEW.currency_code) = 3 AND NEW.currency_code = upper(NEW.currency_code) AND NEW.currency_code NOT GLOB '*[^A-Z]*'
          AND typeof(NEW.currency_decimals) = 'integer' AND NEW.currency_decimals BETWEEN 0 AND 4
          AND typeof(NEW.balance) = 'integer'
          AND (NEW.archived_at IS NULL OR (typeof(NEW.archived_at) = 'integer'))
          AND typeof(NEW.created_at) = 'integer'
          AND typeof(NEW.updated_at) = 'integer'
          AND (NEW.system_key IS NOT NULL OR NEW.name IS NOT NULL)
          AND (NEW.name IS NULL) = (NEW.normalized_name IS NULL)
          AND (NEW.archived_at IS NOT NULL OR NEW.normalized_name IS NULL OR NOT EXISTS (SELECT 1 FROM money_accounts a WHERE a.normalized_name = NEW.normalized_name AND a.archived_at IS NULL AND a.id <> NEW.id))
          AND ((NEW.currency_code = OLD.currency_code AND NEW.currency_decimals = OLD.currency_decimals) OR NOT EXISTS (SELECT 1 FROM money_movements m WHERE m.money_account_id = OLD.id))
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:money_accounts'); END;
        """,
    )

/** El libro de dinero: solo crece. Cada movimiento lleva el signo y el origen de su tipo, una
 * transferencia lleva su grupo, y una anulación apunta a un movimiento de la misma cuenta, con
 * el monto opuesto, una sola vez y nunca a una transferencia.
 *
 * Mientras no exista la columna que bloquea la moneda, que llega con la configuración
 * regional, no se exige la moneda confirmada antes del primer registro con dinero. Es la única
 * parte de estas guardas que hoy protege menos que su texto completo. */
private val MONEY_MOVEMENTS_GUARDS =
    table(
        """
        CREATE TRIGGER guard_money_movements_insert BEFORE INSERT ON money_movements
        WHEN NOT IFNULL((
          NEW.type IN ('OPENING_BALANCE', 'SALE_RECEIPT', 'CUSTOMER_PAYMENT', 'REFUND', 'FEE', 'EXPENSE_PAYMENT', 'PURCHASE_PAYMENT', 'SUPPLIER_PAYMENT', 'SUPPLIER_REFUND', 'OWNER_DRAW', 'OWNER_CONTRIBUTION', 'OTHER_INCOME', 'TRANSFER_OUT', 'TRANSFER_IN', 'CASH_DIFFERENCE', 'MOVEMENT_VOID')
          AND typeof(NEW.amount) = 'integer'
          AND typeof(NEW.balance_after) = 'integer'
          AND (NEW.available_date IS NULL OR (date(NEW.available_date) = NEW.available_date))
          AND (NEW.transfer_group_id IS NULL OR (typeof(NEW.transfer_group_id) = 'text' AND length(NEW.transfer_group_id) BETWEEN 36 AND 36))
          AND (NEW.fx_rate_micro IS NULL OR (typeof(NEW.fx_rate_micro) = 'integer' AND NEW.fx_rate_micro BETWEEN 1 AND 1000000000000000))
          AND (NEW.note IS NULL OR (typeof(NEW.note) = 'text' AND length(NEW.note) BETWEEN 1 AND 200))
          AND typeof(NEW.occurred_at) = 'integer'
          AND date(NEW.business_date) = NEW.business_date
          AND typeof(NEW.created_at) = 'integer'
          AND (SELECT currency_locked_at FROM app_settings WHERE id = 1) IS NOT NULL
          AND NEW.amount <> 0
          AND CASE WHEN NEW.type IN ('SALE_RECEIPT', 'CUSTOMER_PAYMENT', 'SUPPLIER_REFUND', 'OWNER_CONTRIBUTION', 'OTHER_INCOME', 'TRANSFER_IN') THEN NEW.amount > 0 WHEN NEW.type IN ('REFUND', 'FEE', 'EXPENSE_PAYMENT', 'PURCHASE_PAYMENT', 'SUPPLIER_PAYMENT', 'OWNER_DRAW', 'TRANSFER_OUT') THEN NEW.amount < 0 ELSE 1 END
          AND (NEW.type IN ('TRANSFER_OUT', 'TRANSFER_IN')) = (NEW.transfer_group_id IS NOT NULL)
          AND (NEW.type NOT IN ('SALE_RECEIPT', 'FEE') OR NEW.sale_payment_id IS NOT NULL)
          AND (NEW.type <> 'CUSTOMER_PAYMENT' OR NEW.customer_ledger_entry_id IS NOT NULL)
          AND (NEW.type <> 'SUPPLIER_PAYMENT' OR NEW.supplier_ledger_entry_id IS NOT NULL)
          AND (NEW.type <> 'EXPENSE_PAYMENT' OR NEW.expense_id IS NOT NULL)
          AND (NEW.type <> 'PURCHASE_PAYMENT' OR NEW.purchase_id IS NOT NULL)
          AND (NEW.type <> 'REFUND' OR NEW.sale_return_id IS NOT NULL)
          AND (NEW.type <> 'SUPPLIER_REFUND' OR NEW.purchase_return_id IS NOT NULL)
          AND (NEW.type <> 'CASH_DIFFERENCE' OR NEW.cash_session_id IS NOT NULL)
          AND (NEW.type = 'MOVEMENT_VOID') = (NEW.reverses_movement_id IS NOT NULL)
          AND (NEW.reverses_movement_id IS NULL OR EXISTS (SELECT 1 FROM money_movements o WHERE o.id = NEW.reverses_movement_id AND o.money_account_id = NEW.money_account_id AND o.amount = -NEW.amount AND o.type NOT IN ('MOVEMENT_VOID', 'TRANSFER_OUT', 'TRANSFER_IN')))
          AND NOT EXISTS (SELECT 1 FROM money_movements m WHERE m.reverses_movement_id = NEW.reverses_movement_id)
          AND NOT EXISTS (SELECT 1 FROM money_movements x WHERE x.id = NEW.id)
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:money_movements'); END;

        CREATE TRIGGER guard_money_movements_no_update BEFORE UPDATE ON money_movements
        BEGIN SELECT RAISE(ABORT, 'guard:money_movements_append_only'); END;

        CREATE TRIGGER guard_money_movements_no_delete BEFORE DELETE ON money_movements
        BEGIN SELECT RAISE(ABORT, 'guard:money_movements_append_only'); END;
        """,
        awaitingCurrencyLock("guard_money_movements_insert"),
    )

/** Las sesiones de caja: una sola abierta por cuenta. Nace abierta, cambia solo mientras lo
 * está, y se cierra una vez con lo esperado, lo contado y su diferencia exacta. */
private val CASH_SESSIONS_GUARDS =
    table(
        """
        CREATE TRIGGER guard_cash_sessions_insert BEFORE INSERT ON cash_sessions
        WHEN NOT IFNULL((
          NEW.status IN ('OPEN', 'CLOSED')
          AND typeof(NEW.opening_amount) = 'integer'
          AND (NEW.expected_amount IS NULL OR (typeof(NEW.expected_amount) = 'integer'))
          AND (NEW.counted_amount IS NULL OR (typeof(NEW.counted_amount) = 'integer'))
          AND (NEW.difference_amount IS NULL OR (typeof(NEW.difference_amount) = 'integer'))
          AND typeof(NEW.opened_at) = 'integer'
          AND (NEW.closed_at IS NULL OR (typeof(NEW.closed_at) = 'integer'))
          AND date(NEW.business_date) = NEW.business_date
          AND (NEW.note IS NULL OR (typeof(NEW.note) = 'text' AND length(NEW.note) BETWEEN 1 AND 200))
          AND NEW.status = 'OPEN'
          AND NEW.closed_at IS NULL
          AND NOT EXISTS (SELECT 1 FROM cash_sessions c WHERE c.money_account_id = NEW.money_account_id AND c.status = 'OPEN')
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:cash_sessions'); END;

        CREATE TRIGGER guard_cash_sessions_update BEFORE UPDATE ON cash_sessions
        WHEN NOT IFNULL((
          NEW.status IN ('OPEN', 'CLOSED')
          AND typeof(NEW.opening_amount) = 'integer'
          AND (NEW.expected_amount IS NULL OR (typeof(NEW.expected_amount) = 'integer'))
          AND (NEW.counted_amount IS NULL OR (typeof(NEW.counted_amount) = 'integer'))
          AND (NEW.difference_amount IS NULL OR (typeof(NEW.difference_amount) = 'integer'))
          AND typeof(NEW.opened_at) = 'integer'
          AND (NEW.closed_at IS NULL OR (typeof(NEW.closed_at) = 'integer'))
          AND date(NEW.business_date) = NEW.business_date
          AND (NEW.note IS NULL OR (typeof(NEW.note) = 'text' AND length(NEW.note) BETWEEN 1 AND 200))
          AND OLD.status = 'OPEN'
          AND NEW.id = OLD.id AND NEW.money_account_id = OLD.money_account_id AND NEW.opening_amount = OLD.opening_amount AND NEW.opened_at = OLD.opened_at
          AND (NEW.status = 'OPEN' OR (NEW.closed_at IS NOT NULL AND NEW.expected_amount IS NOT NULL AND NEW.counted_amount IS NOT NULL AND NEW.difference_amount = NEW.counted_amount - NEW.expected_amount))
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:cash_sessions'); END;
        """,
    )

/** Las denominaciones de la caja: un valor positivo en la unidad menor. */
private val CASH_DENOMINATIONS_GUARDS =
    table(
        """
        CREATE TRIGGER guard_cash_denominations_insert BEFORE INSERT ON cash_denominations
        WHEN NOT IFNULL((
          typeof(NEW.value) = 'integer' AND NEW.value BETWEEN 1 AND 1000000000000
          AND NEW.is_active IN (0, 1)
          AND typeof(NEW.sort_order) = 'integer'
          AND typeof(NEW.created_at) = 'integer'
          AND typeof(NEW.updated_at) = 'integer'
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:cash_denominations'); END;

        CREATE TRIGGER guard_cash_denominations_update BEFORE UPDATE ON cash_denominations
        WHEN NOT IFNULL((
          typeof(NEW.value) = 'integer' AND NEW.value BETWEEN 1 AND 1000000000000
          AND NEW.is_active IN (0, 1)
          AND typeof(NEW.sort_order) = 'integer'
          AND typeof(NEW.created_at) = 'integer'
          AND typeof(NEW.updated_at) = 'integer'
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:cash_denominations'); END;
        """,
    )

/** Los conteos por denominación: un entero por fase, que no cambia ni se borra. */
private val CASH_SESSION_COUNTS_GUARDS =
    table(
        """
        CREATE TRIGGER guard_cash_session_counts_insert BEFORE INSERT ON cash_session_counts
        WHEN NOT IFNULL((
          NEW.phase IN ('OPENING', 'CLOSING')
          AND typeof(NEW.count) = 'integer' AND NEW.count BETWEEN 0 AND 1000000000
          AND NOT EXISTS (SELECT 1 FROM cash_session_counts x WHERE x.id = NEW.id)
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:cash_session_counts'); END;

        CREATE TRIGGER guard_cash_session_counts_no_update BEFORE UPDATE ON cash_session_counts
        BEGIN SELECT RAISE(ABORT, 'guard:cash_session_counts_append_only'); END;

        CREATE TRIGGER guard_cash_session_counts_no_delete BEFORE DELETE ON cash_session_counts
        BEGIN SELECT RAISE(ABORT, 'guard:cash_session_counts_append_only'); END;
        """,
    )

/** Las categorías de gasto: el nombre es único entre las vigentes, y la de otros gastos no
 * se archiva ni se borra. */
private val EXPENSE_CATEGORIES_GUARDS =
    table(
        """
        CREATE TRIGGER guard_expense_categories_insert BEFORE INSERT ON expense_categories
        WHEN NOT IFNULL((
          (NEW.system_key IS NULL OR (typeof(NEW.system_key) = 'text' AND length(NEW.system_key) BETWEEN 1 AND 40))
          AND (NEW.name IS NULL OR (typeof(NEW.name) = 'text' AND length(NEW.name) BETWEEN 1 AND 40))
          AND (NEW.normalized_name IS NULL OR (typeof(NEW.normalized_name) = 'text' AND length(NEW.normalized_name) BETWEEN 1 AND 40))
          AND NEW.is_system IN (0, 1)
          AND NEW.is_fixed IN (0, 1)
          AND (NEW.archived_at IS NULL OR (typeof(NEW.archived_at) = 'integer'))
          AND typeof(NEW.created_at) = 'integer'
          AND typeof(NEW.updated_at) = 'integer'
          AND (NEW.system_key IS NOT NULL OR NEW.name IS NOT NULL)
          AND (NEW.name IS NULL) = (NEW.normalized_name IS NULL)
          AND (NEW.archived_at IS NOT NULL OR NEW.normalized_name IS NULL OR NOT EXISTS (SELECT 1 FROM expense_categories c WHERE c.normalized_name = NEW.normalized_name AND c.archived_at IS NULL))
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:expense_categories'); END;

        CREATE TRIGGER guard_expense_categories_update BEFORE UPDATE ON expense_categories
        WHEN NOT IFNULL((
          (NEW.system_key IS NULL OR (typeof(NEW.system_key) = 'text' AND length(NEW.system_key) BETWEEN 1 AND 40))
          AND (NEW.name IS NULL OR (typeof(NEW.name) = 'text' AND length(NEW.name) BETWEEN 1 AND 40))
          AND (NEW.normalized_name IS NULL OR (typeof(NEW.normalized_name) = 'text' AND length(NEW.normalized_name) BETWEEN 1 AND 40))
          AND NEW.is_system IN (0, 1)
          AND NEW.is_fixed IN (0, 1)
          AND (NEW.archived_at IS NULL OR (typeof(NEW.archived_at) = 'integer'))
          AND typeof(NEW.created_at) = 'integer'
          AND typeof(NEW.updated_at) = 'integer'
          AND (NEW.system_key IS NOT NULL OR NEW.name IS NOT NULL)
          AND (NEW.name IS NULL) = (NEW.normalized_name IS NULL)
          AND (NEW.is_system = 0 OR NEW.archived_at IS NULL)
          AND (NEW.archived_at IS NOT NULL OR NEW.normalized_name IS NULL OR NOT EXISTS (SELECT 1 FROM expense_categories c WHERE c.normalized_name = NEW.normalized_name AND c.archived_at IS NULL AND c.id <> NEW.id))
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:expense_categories'); END;

        CREATE TRIGGER guard_expense_categories_no_delete BEFORE DELETE ON expense_categories
        WHEN OLD.is_system = 1
        BEGIN SELECT RAISE(ABORT, 'guard:expense_categories_system'); END;
        """,
    )

/** Los gastos: nacen activos, solo cambian al anularse y nunca se borran.
 *
 * Mientras no exista la columna que bloquea la moneda, que llega con la configuración
 * regional, no se exige la moneda confirmada antes del primer registro con dinero. Es la única
 * parte de estas guardas que hoy protege menos que su texto completo. */
private val EXPENSES_GUARDS =
    table(
        """
        CREATE TRIGGER guard_expenses_insert BEFORE INSERT ON expenses
        WHEN NOT IFNULL((
          NEW.status = 'ACTIVE' AND NEW.voided_at IS NULL AND NEW.void_reason IS NULL AND NEW.void_note IS NULL
          AND typeof(NEW.amount) = 'integer' AND NEW.amount BETWEEN 1 AND 1000000000000000
          AND (NEW.description IS NULL OR (typeof(NEW.description) = 'text' AND length(NEW.description) BETWEEN 1 AND 200))
          AND typeof(NEW.occurred_at) = 'integer'
          AND typeof(NEW.created_at) = 'integer'
          AND date(NEW.business_date) = NEW.business_date
          AND (SELECT currency_locked_at FROM app_settings WHERE id = 1) IS NOT NULL
          AND NOT EXISTS (SELECT 1 FROM expenses x WHERE x.id = NEW.id)
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:expenses'); END;

        CREATE TRIGGER guard_expenses_void_only BEFORE UPDATE ON expenses
        WHEN NOT IFNULL((
          OLD.status = 'ACTIVE' AND NEW.status = 'VOIDED' AND NEW.voided_at IS NOT NULL
          AND NEW.void_reason IN ('ENTRY_ERROR', 'UNDO', 'OTHER')
          AND (NEW.void_note IS NULL OR length(NEW.void_note) <= 200)
          AND NEW.id IS OLD.id AND NEW.expense_category_id IS OLD.expense_category_id AND NEW.amount IS OLD.amount AND NEW.description IS OLD.description AND NEW.money_account_id IS OLD.money_account_id AND NEW.recurring_expense_id IS OLD.recurring_expense_id
          AND NEW.occurred_at IS OLD.occurred_at AND NEW.created_at IS OLD.created_at AND NEW.business_date IS OLD.business_date
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:expenses_void_only'); END;

        CREATE TRIGGER guard_expenses_no_delete BEFORE DELETE ON expenses
        BEGIN SELECT RAISE(ABORT, 'guard:expenses_no_delete'); END;
        """,
        awaitingCurrencyLock("guard_expenses_insert"),
    )

/** Las listas de precios: la inicial es fija, no se archiva ni se borra. */
private val PRICE_LISTS_GUARDS =
    table(
        """
        CREATE TRIGGER guard_price_lists_insert BEFORE INSERT ON price_lists
        WHEN NOT IFNULL((
          (NEW.system_key IS NULL OR (typeof(NEW.system_key) = 'text' AND length(NEW.system_key) BETWEEN 1 AND 40))
          AND (NEW.name IS NULL OR (typeof(NEW.name) = 'text' AND length(NEW.name) BETWEEN 1 AND 40))
          AND (NEW.normalized_name IS NULL OR (typeof(NEW.normalized_name) = 'text' AND length(NEW.normalized_name) BETWEEN 1 AND 40))
          AND NEW.kind IN ('FIXED', 'RELATIVE')
          AND (NEW.adjustment_bp IS NULL OR (typeof(NEW.adjustment_bp) = 'integer' AND NEW.adjustment_bp BETWEEN -10000 AND 1000000))
          AND (NEW.archived_at IS NULL OR (typeof(NEW.archived_at) = 'integer'))
          AND typeof(NEW.created_at) = 'integer'
          AND typeof(NEW.updated_at) = 'integer'
          AND (NEW.system_key IS NOT NULL OR NEW.name IS NOT NULL)
          AND (NEW.name IS NULL) = (NEW.normalized_name IS NULL)
          AND (NEW.kind = 'RELATIVE') = (NEW.base_price_list_id IS NOT NULL)
          AND (NEW.kind = 'RELATIVE') = (NEW.adjustment_bp IS NOT NULL)
          AND (NEW.base_price_list_id IS NULL OR NEW.base_price_list_id <> NEW.id)
          AND (NEW.system_key IS NOT 'DEFAULT' OR (NEW.kind = 'FIXED' AND NEW.archived_at IS NULL))
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:price_lists'); END;

        CREATE TRIGGER guard_price_lists_update BEFORE UPDATE ON price_lists
        WHEN NOT IFNULL((
          (NEW.system_key IS NULL OR (typeof(NEW.system_key) = 'text' AND length(NEW.system_key) BETWEEN 1 AND 40))
          AND (NEW.name IS NULL OR (typeof(NEW.name) = 'text' AND length(NEW.name) BETWEEN 1 AND 40))
          AND (NEW.normalized_name IS NULL OR (typeof(NEW.normalized_name) = 'text' AND length(NEW.normalized_name) BETWEEN 1 AND 40))
          AND NEW.kind IN ('FIXED', 'RELATIVE')
          AND (NEW.adjustment_bp IS NULL OR (typeof(NEW.adjustment_bp) = 'integer' AND NEW.adjustment_bp BETWEEN -10000 AND 1000000))
          AND (NEW.archived_at IS NULL OR (typeof(NEW.archived_at) = 'integer'))
          AND typeof(NEW.created_at) = 'integer'
          AND typeof(NEW.updated_at) = 'integer'
          AND (NEW.system_key IS NOT NULL OR NEW.name IS NOT NULL)
          AND (NEW.name IS NULL) = (NEW.normalized_name IS NULL)
          AND (NEW.kind = 'RELATIVE') = (NEW.base_price_list_id IS NOT NULL)
          AND (NEW.kind = 'RELATIVE') = (NEW.adjustment_bp IS NOT NULL)
          AND (NEW.base_price_list_id IS NULL OR NEW.base_price_list_id <> NEW.id)
          AND (NEW.system_key IS NOT 'DEFAULT' OR (NEW.kind = 'FIXED' AND NEW.archived_at IS NULL))
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:price_lists'); END;

        CREATE TRIGGER guard_price_lists_no_delete BEFORE DELETE ON price_lists
        WHEN OLD.system_key = 'DEFAULT'
        BEGIN SELECT RAISE(ABORT, 'guard:price_lists_system'); END;
        """,
    )

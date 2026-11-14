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
 * distinta mientras tanto: quien crea esa tabla o columna borra esa línea, y nada más. Con el
 * esquema 1 completo, ninguna espera nada: todas se instalan con su texto completo.
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
            PRODUCT_MODIFIERS_GUARDS,
            CUSTOM_FIELD_DEFINITIONS_GUARDS,
            PRODUCT_CUSTOM_VALUES_GUARDS,
            TAGS_GUARDS,
            PRODUCT_TAGS_GUARDS,
            MEDIA_ASSETS_GUARDS,
            UNITS_GUARDS,
            PRODUCT_UNITS_GUARDS,
            STOCK_MOVEMENTS_GUARDS,
            STOCK_BALANCES_GUARDS,
            STOCK_LOTS_GUARDS,
            LOCATIONS_GUARDS,
            STOCK_TRANSFERS_GUARDS,
            STOCK_TRANSFER_LINES_GUARDS,
            PRODUCTION_BATCHES_GUARDS,
            PRODUCTION_BATCH_LINES_GUARDS,
            COUNT_SESSIONS_GUARDS,
            COUNT_SESSION_LINES_GUARDS,
            LOW_STOCK_ALERTS_GUARDS,
            PURCHASES_GUARDS,
            PURCHASE_LINES_GUARDS,
            PURCHASE_RETURNS_GUARDS,
            PURCHASE_RETURN_LINES_GUARDS,
            SUPPLIER_LEDGER_ENTRIES_GUARDS,
            SALES_GUARDS,
            SALE_LINES_GUARDS,
            SALE_LINE_COMPONENTS_GUARDS,
            TAX_RATES_GUARDS,
            SALE_DRAFTS_GUARDS,
            SALE_DRAFT_LINES_GUARDS,
            SALE_RETURNS_GUARDS,
            SALE_RETURN_LINES_GUARDS,
            PAYMENT_METHODS_GUARDS,
            SALE_PAYMENTS_GUARDS,
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
            RECURRING_EXPENSES_GUARDS,
            PRICE_LISTS_GUARDS,
            PRODUCT_PRICES_GUARDS,
            FX_RATES_GUARDS,
            PRICE_INDEX_ENTRIES_GUARDS,
            QUOTES_GUARDS,
            QUOTE_LINES_GUARDS,
            ORDERS_GUARDS,
            ORDER_LINES_GUARDS,
            SALE_FISCAL_MARKS_GUARDS,
            DOCUMENT_ATTACHMENTS_GUARDS,
            BUSINESS_GOALS_GUARDS,
            AUDIT_EVENTS_GUARDS,
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

/** Los ajustes: fila única, que nunca se borra.
 *
 * La moneda es de tres letras mayúsculas, con 0 a 4 decimales. Desde que se bloquea, ni ella ni
 * sus decimales cambian, el bloqueo no se quita, y nunca se bloquea `XXX`, que es sin moneda. */
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
 * Un registro con dinero exige la moneda confirmada y bloqueada en los ajustes: sin moneda, un
 * monto no tendría unidad. */
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
          AND EXISTS (SELECT 1 FROM products p WHERE p.id = NEW.parent_product_id AND p.stock_mode IN ('COMPONENTS', 'TRACKED'))
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
          AND EXISTS (SELECT 1 FROM products p WHERE p.id = NEW.parent_product_id AND p.stock_mode IN ('COMPONENTS', 'TRACKED'))
          AND NOT EXISTS (SELECT 1 FROM product_components a JOIN product_components b ON b.parent_product_id = a.component_product_id WHERE a.parent_product_id = NEW.component_product_id)
          AND NOT (EXISTS (SELECT 1 FROM product_components a WHERE a.component_product_id = NEW.parent_product_id) AND EXISTS (SELECT 1 FROM product_components b WHERE b.parent_product_id = NEW.component_product_id))
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:product_components'); END;
        """,
    )

/** Los extras de un producto: otro producto, con su grupo y su orden. */
private val PRODUCT_MODIFIERS_GUARDS =
    table(
        """
        CREATE TRIGGER guard_product_modifiers_insert BEFORE INSERT ON product_modifiers
        WHEN NOT IFNULL((
          (NEW.group_name IS NULL OR (typeof(NEW.group_name) = 'text' AND length(NEW.group_name) BETWEEN 1 AND 40))
          AND typeof(NEW.sort_order) = 'integer'
          AND typeof(NEW.created_at) = 'integer'
          AND typeof(NEW.updated_at) = 'integer'
          AND NEW.product_id <> NEW.modifier_product_id
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:product_modifiers'); END;

        CREATE TRIGGER guard_product_modifiers_update BEFORE UPDATE ON product_modifiers
        WHEN NOT IFNULL((
          (NEW.group_name IS NULL OR (typeof(NEW.group_name) = 'text' AND length(NEW.group_name) BETWEEN 1 AND 40))
          AND typeof(NEW.sort_order) = 'integer'
          AND typeof(NEW.created_at) = 'integer'
          AND typeof(NEW.updated_at) = 'integer'
          AND NEW.product_id <> NEW.modifier_product_id
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:product_modifiers'); END;
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
 * compensación apunta a la fila que compensa, una sola vez. Un producto con lotes lleva el lote en
 * cada fila con cantidad, y el lote es del mismo producto. */
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
    )

/** Los saldos por producto, ubicación y lote: enteros, y `lot_key` es un lote o 0. */
private val STOCK_BALANCES_GUARDS =
    table(
        """
        CREATE TRIGGER guard_stock_balances_insert BEFORE INSERT ON stock_balances
        WHEN NOT IFNULL((
          typeof(NEW.lot_key) = 'integer' AND NEW.lot_key >= 0
          AND typeof(NEW.on_hand_milli) = 'integer'
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:stock_balances'); END;

        CREATE TRIGGER guard_stock_balances_update BEFORE UPDATE ON stock_balances
        WHEN NOT IFNULL((
          typeof(NEW.lot_key) = 'integer' AND NEW.lot_key >= 0
          AND typeof(NEW.on_hand_milli) = 'integer'
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:stock_balances'); END;
        """,
    )

/** Los lotes: código y vencimiento válidos, y un solo lote sin asignar por producto, que no deja
 * de serlo ni cambia de producto. */
private val STOCK_LOTS_GUARDS =
    table(
        """
        CREATE TRIGGER guard_stock_lots_insert BEFORE INSERT ON stock_lots
        WHEN NOT IFNULL((
          (NEW.lot_code IS NULL OR (typeof(NEW.lot_code) = 'text' AND length(NEW.lot_code) BETWEEN 1 AND 60))
          AND NEW.is_unassigned IN (0, 1)
          AND (NEW.expiry_date IS NULL OR (date(NEW.expiry_date) = NEW.expiry_date))
          AND (NEW.expiry_notified_at IS NULL OR (typeof(NEW.expiry_notified_at) = 'integer'))
          AND typeof(NEW.created_at) = 'integer'
          AND (NEW.closed_at IS NULL OR (typeof(NEW.closed_at) = 'integer'))
          AND (NEW.is_unassigned = 0 OR (NEW.lot_code IS NULL AND NEW.expiry_date IS NULL))
          AND (NEW.is_unassigned = 0 OR NOT EXISTS (SELECT 1 FROM stock_lots l WHERE l.product_id = NEW.product_id AND l.is_unassigned = 1))
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:stock_lots'); END;

        CREATE TRIGGER guard_stock_lots_update BEFORE UPDATE ON stock_lots
        WHEN NOT IFNULL((
          (NEW.lot_code IS NULL OR (typeof(NEW.lot_code) = 'text' AND length(NEW.lot_code) BETWEEN 1 AND 60))
          AND NEW.is_unassigned IN (0, 1)
          AND (NEW.expiry_date IS NULL OR (date(NEW.expiry_date) = NEW.expiry_date))
          AND (NEW.expiry_notified_at IS NULL OR (typeof(NEW.expiry_notified_at) = 'integer'))
          AND typeof(NEW.created_at) = 'integer'
          AND (NEW.closed_at IS NULL OR (typeof(NEW.closed_at) = 'integer'))
          AND (NEW.is_unassigned = 0 OR (NEW.lot_code IS NULL AND NEW.expiry_date IS NULL))
          AND NEW.product_id = OLD.product_id AND NEW.is_unassigned = OLD.is_unassigned
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:stock_lots'); END;
        """,
    )

/** Las ubicaciones: la de clave `MAIN` nunca se archiva ni se borra, y una creada por la persona
 * siempre tiene nombre. */
private val LOCATIONS_GUARDS =
    table(
        """
        CREATE TRIGGER guard_locations_insert BEFORE INSERT ON locations
        WHEN NOT IFNULL((
          (NEW.system_key IS NULL OR (typeof(NEW.system_key) = 'text' AND length(NEW.system_key) BETWEEN 1 AND 40))
          AND (NEW.name IS NULL OR (typeof(NEW.name) = 'text' AND length(NEW.name) BETWEEN 1 AND 40))
          AND (NEW.normalized_name IS NULL OR (typeof(NEW.normalized_name) = 'text' AND length(NEW.normalized_name) BETWEEN 1 AND 40))
          AND NEW.kind IN ('STORE', 'WAREHOUSE', 'STALL', 'OTHER')
          AND (NEW.archived_at IS NULL OR (typeof(NEW.archived_at) = 'integer'))
          AND typeof(NEW.created_at) = 'integer'
          AND typeof(NEW.updated_at) = 'integer'
          AND (NEW.system_key IS NOT NULL OR NEW.name IS NOT NULL)
          AND (NEW.name IS NULL) = (NEW.normalized_name IS NULL)
          AND (NEW.system_key IS NOT 'MAIN' OR NEW.archived_at IS NULL)
          AND (NEW.archived_at IS NOT NULL OR NEW.normalized_name IS NULL OR NOT EXISTS (SELECT 1 FROM locations o WHERE o.normalized_name = NEW.normalized_name AND o.archived_at IS NULL))
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:locations'); END;

        CREATE TRIGGER guard_locations_update BEFORE UPDATE ON locations
        WHEN NOT IFNULL((
          (NEW.system_key IS NULL OR (typeof(NEW.system_key) = 'text' AND length(NEW.system_key) BETWEEN 1 AND 40))
          AND (NEW.name IS NULL OR (typeof(NEW.name) = 'text' AND length(NEW.name) BETWEEN 1 AND 40))
          AND (NEW.normalized_name IS NULL OR (typeof(NEW.normalized_name) = 'text' AND length(NEW.normalized_name) BETWEEN 1 AND 40))
          AND NEW.kind IN ('STORE', 'WAREHOUSE', 'STALL', 'OTHER')
          AND (NEW.archived_at IS NULL OR (typeof(NEW.archived_at) = 'integer'))
          AND typeof(NEW.created_at) = 'integer'
          AND typeof(NEW.updated_at) = 'integer'
          AND (NEW.system_key IS NOT NULL OR NEW.name IS NOT NULL)
          AND (NEW.name IS NULL) = (NEW.normalized_name IS NULL)
          AND (NEW.system_key IS NOT 'MAIN' OR NEW.archived_at IS NULL)
          AND (NEW.archived_at IS NOT NULL OR NEW.normalized_name IS NULL OR NOT EXISTS (SELECT 1 FROM locations o WHERE o.normalized_name = NEW.normalized_name AND o.archived_at IS NULL AND o.id <> NEW.id))
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:locations'); END;

        CREATE TRIGGER guard_locations_no_delete BEFORE DELETE ON locations
        WHEN OLD.system_key = 'MAIN'
        BEGIN SELECT RAISE(ABORT, 'guard:locations_system'); END;
        """,
    )

/** Las transferencias: nacen activas entre dos ubicaciones distintas, y solo cambian al anularse,
 * una vez. */
private val STOCK_TRANSFERS_GUARDS =
    table(
        """
        CREATE TRIGGER guard_stock_transfers_insert BEFORE INSERT ON stock_transfers
        WHEN NOT IFNULL((
          NEW.status = 'ACTIVE' AND NEW.voided_at IS NULL AND NEW.void_reason IS NULL AND NEW.void_note IS NULL
          AND (NEW.note IS NULL OR (typeof(NEW.note) = 'text' AND length(NEW.note) BETWEEN 1 AND 200))
          AND typeof(NEW.occurred_at) = 'integer'
          AND date(NEW.business_date) = NEW.business_date
          AND typeof(NEW.created_at) = 'integer'
          AND NEW.from_location_id <> NEW.to_location_id
          AND NOT EXISTS (SELECT 1 FROM stock_transfers x WHERE x.id = NEW.id)
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:stock_transfers'); END;

        CREATE TRIGGER guard_stock_transfers_void_only BEFORE UPDATE ON stock_transfers
        WHEN NOT IFNULL((
          OLD.status = 'ACTIVE' AND NEW.status = 'VOIDED' AND NEW.voided_at IS NOT NULL
          AND NEW.void_reason IN ('ENTRY_ERROR', 'UNDO', 'OTHER')
          AND (NEW.void_note IS NULL OR length(NEW.void_note) <= 200)
          AND NEW.id IS OLD.id AND NEW.from_location_id IS OLD.from_location_id AND NEW.to_location_id IS OLD.to_location_id AND NEW.note IS OLD.note AND NEW.occurred_at IS OLD.occurred_at AND NEW.business_date IS OLD.business_date
          AND NEW.created_at IS OLD.created_at
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:stock_transfers_void_only'); END;

        CREATE TRIGGER guard_stock_transfers_no_delete BEFORE DELETE ON stock_transfers
        BEGIN SELECT RAISE(ABORT, 'guard:stock_transfers_no_delete'); END;
        """,
    )

/** Las líneas de una transferencia: una cantidad positiva, que no cambia nunca. */
private val STOCK_TRANSFER_LINES_GUARDS =
    table(
        """
        CREATE TRIGGER guard_stock_transfer_lines_insert BEFORE INSERT ON stock_transfer_lines
        WHEN NOT IFNULL((
          typeof(NEW.quantity_milli) = 'integer' AND NEW.quantity_milli BETWEEN 1 AND 1000000000
          AND NOT EXISTS (SELECT 1 FROM stock_transfer_lines x WHERE x.id = NEW.id)
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:stock_transfer_lines'); END;

        CREATE TRIGGER guard_stock_transfer_lines_no_update BEFORE UPDATE ON stock_transfer_lines
        BEGIN SELECT RAISE(ABORT, 'guard:stock_transfer_lines_append_only'); END;

        CREATE TRIGGER guard_stock_transfer_lines_no_delete BEFORE DELETE ON stock_transfer_lines
        BEGIN SELECT RAISE(ABORT, 'guard:stock_transfer_lines_append_only'); END;
        """,
    )

/** Los lotes de producción: nacen activos, y solo cambian al anularse, una vez. */
private val PRODUCTION_BATCHES_GUARDS =
    table(
        """
        CREATE TRIGGER guard_production_batches_insert BEFORE INSERT ON production_batches
        WHEN NOT IFNULL((
          NEW.status = 'ACTIVE' AND NEW.voided_at IS NULL AND NEW.void_reason IS NULL AND NEW.void_note IS NULL
          AND typeof(NEW.quantity_milli) = 'integer' AND NEW.quantity_milli BETWEEN 1 AND 1000000000
          AND typeof(NEW.total_cost) = 'integer' AND NEW.total_cost BETWEEN 0 AND 1000000000000000
          AND (NEW.note IS NULL OR (typeof(NEW.note) = 'text' AND length(NEW.note) BETWEEN 1 AND 200))
          AND typeof(NEW.occurred_at) = 'integer'
          AND date(NEW.business_date) = NEW.business_date
          AND typeof(NEW.created_at) = 'integer'
          AND NOT EXISTS (SELECT 1 FROM production_batches x WHERE x.id = NEW.id)
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:production_batches'); END;

        CREATE TRIGGER guard_production_batches_void_only BEFORE UPDATE ON production_batches
        WHEN NOT IFNULL((
          OLD.status = 'ACTIVE' AND NEW.status = 'VOIDED' AND NEW.voided_at IS NOT NULL
          AND NEW.void_reason IN ('ENTRY_ERROR', 'UNDO', 'OTHER')
          AND (NEW.void_note IS NULL OR length(NEW.void_note) <= 200)
          AND NEW.id IS OLD.id AND NEW.product_id IS OLD.product_id AND NEW.location_id IS OLD.location_id AND NEW.quantity_milli IS OLD.quantity_milli AND NEW.total_cost IS OLD.total_cost AND NEW.note IS OLD.note
          AND NEW.occurred_at IS OLD.occurred_at AND NEW.business_date IS OLD.business_date AND NEW.created_at IS OLD.created_at
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:production_batches_void_only'); END;

        CREATE TRIGGER guard_production_batches_no_delete BEFORE DELETE ON production_batches
        BEGIN SELECT RAISE(ABORT, 'guard:production_batches_no_delete'); END;
        """,
    )

/** Los insumos de producción: cantidad y costos enteros dentro de sus topes, que no cambian nunca. */
private val PRODUCTION_BATCH_LINES_GUARDS =
    table(
        """
        CREATE TRIGGER guard_production_batch_lines_insert BEFORE INSERT ON production_batch_lines
        WHEN NOT IFNULL((
          typeof(NEW.quantity_milli) = 'integer' AND NEW.quantity_milli BETWEEN 1 AND 1000000000
          AND typeof(NEW.unit_cost) = 'integer' AND NEW.unit_cost BETWEEN 0 AND 1000000000000
          AND typeof(NEW.line_cost) = 'integer' AND NEW.line_cost BETWEEN 0 AND 1000000000000000
          AND NOT EXISTS (SELECT 1 FROM production_batch_lines x WHERE x.id = NEW.id)
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:production_batch_lines'); END;

        CREATE TRIGGER guard_production_batch_lines_no_update BEFORE UPDATE ON production_batch_lines
        BEGIN SELECT RAISE(ABORT, 'guard:production_batch_lines_append_only'); END;

        CREATE TRIGGER guard_production_batch_lines_no_delete BEFORE DELETE ON production_batch_lines
        BEGIN SELECT RAISE(ABORT, 'guard:production_batch_lines_append_only'); END;
        """,
    )

/** Las sesiones de conteo: nacen abiertas, y solo una abierta cambia o se cierra, con su instante. */
private val COUNT_SESSIONS_GUARDS =
    table(
        """
        CREATE TRIGGER guard_count_sessions_insert BEFORE INSERT ON count_sessions
        WHEN NOT IFNULL((
          NEW.status IN ('OPEN', 'APPLIED', 'CANCELLED')
          AND NEW.is_blind IN (0, 1)
          AND (NEW.note IS NULL OR (typeof(NEW.note) = 'text' AND length(NEW.note) BETWEEN 1 AND 200))
          AND typeof(NEW.started_at) = 'integer'
          AND (NEW.finished_at IS NULL OR (typeof(NEW.finished_at) = 'integer'))
          AND date(NEW.business_date) = NEW.business_date
          AND NEW.status = 'OPEN'
          AND NEW.finished_at IS NULL
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:count_sessions'); END;

        CREATE TRIGGER guard_count_sessions_update BEFORE UPDATE ON count_sessions
        WHEN NOT IFNULL((
          NEW.status IN ('OPEN', 'APPLIED', 'CANCELLED')
          AND NEW.is_blind IN (0, 1)
          AND (NEW.note IS NULL OR (typeof(NEW.note) = 'text' AND length(NEW.note) BETWEEN 1 AND 200))
          AND typeof(NEW.started_at) = 'integer'
          AND (NEW.finished_at IS NULL OR (typeof(NEW.finished_at) = 'integer'))
          AND date(NEW.business_date) = NEW.business_date
          AND NEW.id = OLD.id AND NEW.location_id = OLD.location_id AND NEW.is_blind = OLD.is_blind AND NEW.started_at = OLD.started_at
          AND (OLD.status = 'OPEN' OR (NEW.status = OLD.status AND NEW.finished_at IS OLD.finished_at))
          AND (NEW.status = 'OPEN') = (NEW.finished_at IS NULL)
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:count_sessions'); END;
        """,
    )

/** Lo contado en cada sesión: solo cambia con la sesión abierta. */
private val COUNT_SESSION_LINES_GUARDS =
    table(
        """
        CREATE TRIGGER guard_count_session_lines_insert BEFORE INSERT ON count_session_lines
        WHEN NOT IFNULL((
          typeof(NEW.expected_milli) = 'integer'
          AND (NEW.counted_milli IS NULL OR (typeof(NEW.counted_milli) = 'integer' AND NEW.counted_milli BETWEEN 0 AND 8000000000))
          AND EXISTS (SELECT 1 FROM count_sessions c WHERE c.id = NEW.count_session_id AND c.status = 'OPEN')
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:count_session_lines'); END;

        CREATE TRIGGER guard_count_session_lines_update BEFORE UPDATE ON count_session_lines
        WHEN NOT IFNULL((
          typeof(NEW.expected_milli) = 'integer'
          AND (NEW.counted_milli IS NULL OR (typeof(NEW.counted_milli) = 'integer' AND NEW.counted_milli BETWEEN 0 AND 8000000000))
          AND EXISTS (SELECT 1 FROM count_sessions c WHERE c.id = NEW.count_session_id AND c.status = 'OPEN')
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:count_session_lines'); END;
        """,
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
 * Un registro con dinero exige la moneda confirmada y bloqueada en los ajustes: sin moneda, un
 * monto no tendría unidad. */
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
 * Un registro con dinero exige la moneda confirmada y bloqueada en los ajustes: sin moneda, un
 * monto no tendría unidad. */
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
    )

/** Las devoluciones a proveedor: nacen activas, solo cambian al anularse y nunca se borran. Un
 * saldo a favor pide saber cuál proveedor es. */
private val PURCHASE_RETURNS_GUARDS =
    table(
        """
        CREATE TRIGGER guard_purchase_returns_insert BEFORE INSERT ON purchase_returns
        WHEN NOT IFNULL((
          NEW.status = 'ACTIVE' AND NEW.voided_at IS NULL AND NEW.void_reason IS NULL AND NEW.void_note IS NULL
          AND NEW.resolution IN ('CASH_REFUND', 'SUPPLIER_CREDIT')
          AND typeof(NEW.total) = 'integer' AND NEW.total BETWEEN 0 AND 1000000000000000
          AND (NEW.note IS NULL OR (typeof(NEW.note) = 'text' AND length(NEW.note) BETWEEN 1 AND 200))
          AND typeof(NEW.occurred_at) = 'integer'
          AND date(NEW.business_date) = NEW.business_date
          AND typeof(NEW.created_at) = 'integer'
          AND (NEW.resolution <> 'SUPPLIER_CREDIT' OR NEW.supplier_id IS NOT NULL)
          AND NOT EXISTS (SELECT 1 FROM purchase_returns x WHERE x.id = NEW.id)
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:purchase_returns'); END;

        CREATE TRIGGER guard_purchase_returns_void_only BEFORE UPDATE ON purchase_returns
        WHEN NOT IFNULL((
          OLD.status = 'ACTIVE' AND NEW.status = 'VOIDED' AND NEW.voided_at IS NOT NULL
          AND NEW.void_reason IN ('ENTRY_ERROR', 'UNDO', 'OTHER')
          AND (NEW.void_note IS NULL OR length(NEW.void_note) <= 200)
          AND NEW.id IS OLD.id AND NEW.purchase_id IS OLD.purchase_id AND NEW.supplier_id IS OLD.supplier_id AND NEW.resolution IS OLD.resolution AND NEW.total IS OLD.total AND NEW.money_account_id IS OLD.money_account_id
          AND NEW.note IS OLD.note AND NEW.occurred_at IS OLD.occurred_at AND NEW.business_date IS OLD.business_date AND NEW.created_at IS OLD.created_at
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:purchase_returns_void_only'); END;

        CREATE TRIGGER guard_purchase_returns_no_delete BEFORE DELETE ON purchase_returns
        BEGIN SELECT RAISE(ABORT, 'guard:purchase_returns_no_delete'); END;
        """,
    )

/** Las líneas de las devoluciones a proveedor: no cambian ni se borran. */
private val PURCHASE_RETURN_LINES_GUARDS =
    table(
        """
        CREATE TRIGGER guard_purchase_return_lines_insert BEFORE INSERT ON purchase_return_lines
        WHEN NOT IFNULL((
          typeof(NEW.quantity_milli) = 'integer' AND NEW.quantity_milli BETWEEN 1 AND 1000000000
          AND typeof(NEW.unit_cost) = 'integer' AND NEW.unit_cost BETWEEN 0 AND 1000000000000
          AND typeof(NEW.line_total) = 'integer' AND NEW.line_total BETWEEN 0 AND 1000000000000000
          AND NOT EXISTS (SELECT 1 FROM purchase_return_lines x WHERE x.id = NEW.id)
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:purchase_return_lines'); END;

        CREATE TRIGGER guard_purchase_return_lines_no_update BEFORE UPDATE ON purchase_return_lines
        BEGIN SELECT RAISE(ABORT, 'guard:purchase_return_lines_append_only'); END;

        CREATE TRIGGER guard_purchase_return_lines_no_delete BEFORE DELETE ON purchase_return_lines
        BEGIN SELECT RAISE(ABORT, 'guard:purchase_return_lines_append_only'); END;
        """,
    )

/** Las ventas: nacen activas, solo cambian al anularse y nunca se borran.
 *
 * Un registro con dinero exige la moneda confirmada y bloqueada en los ajustes: sin moneda, un
 * monto no tendría unidad.
 */
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
          AND (NEW.parent_line_id IS NULL OR EXISTS (SELECT 1 FROM sale_lines pl WHERE pl.id = NEW.parent_line_id AND pl.sale_id = NEW.sale_id AND pl.parent_line_id IS NULL))
          AND NOT EXISTS (SELECT 1 FROM sale_lines x WHERE x.id = NEW.id)
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:sale_lines'); END;

        CREATE TRIGGER guard_sale_lines_no_update BEFORE UPDATE ON sale_lines
        BEGIN SELECT RAISE(ABORT, 'guard:sale_lines_append_only'); END;

        CREATE TRIGGER guard_sale_lines_no_delete BEFORE DELETE ON sale_lines
        BEGIN SELECT RAISE(ABORT, 'guard:sale_lines_append_only'); END;
        """,
    )

/** Las tasas de impuesto: la tasa va de 0 a 10.000 puntos básicos, y el nombre es único entre
 * las vigentes. */
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
          AND (NEW.archived_at IS NOT NULL OR NOT EXISTS (SELECT 1 FROM tax_rates t WHERE t.normalized_name = NEW.normalized_name AND t.archived_at IS NULL))
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
          AND (NEW.archived_at IS NOT NULL OR NOT EXISTS (SELECT 1 FROM tax_rates t WHERE t.normalized_name = NEW.normalized_name AND t.archived_at IS NULL AND t.id <> NEW.id))
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:tax_rates'); END;
        """,
    )

/** Los métodos de pago: el tipo es uno de los seis que conoce la app, un método sin clave inicial
 * tiene nombre, y el nombre es único entre los vigentes. */
private val PAYMENT_METHODS_GUARDS =
    table(
        """
        CREATE TRIGGER guard_payment_methods_insert BEFORE INSERT ON payment_methods
        WHEN NOT IFNULL((
          (NEW.system_key IS NULL OR (typeof(NEW.system_key) = 'text' AND length(NEW.system_key) BETWEEN 1 AND 40))
          AND (NEW.name IS NULL OR (typeof(NEW.name) = 'text' AND length(NEW.name) BETWEEN 1 AND 40))
          AND (NEW.normalized_name IS NULL OR (typeof(NEW.normalized_name) = 'text' AND length(NEW.normalized_name) BETWEEN 1 AND 40))
          AND NEW.kind IN ('CASH', 'CARD', 'TRANSFER', 'WALLET', 'ON_ACCOUNT', 'OTHER')
          AND typeof(NEW.fee_bp) = 'integer' AND NEW.fee_bp BETWEEN 0 AND 10000
          AND typeof(NEW.fee_fixed) = 'integer' AND NEW.fee_fixed BETWEEN 0 AND 1000000000000
          AND typeof(NEW.settlement_days) = 'integer' AND NEW.settlement_days BETWEEN 0 AND 3650
          AND typeof(NEW.price_adjustment_bp) = 'integer' AND NEW.price_adjustment_bp BETWEEN -10000 AND 10000
          AND (NEW.instructions_text IS NULL OR (typeof(NEW.instructions_text) = 'text' AND length(NEW.instructions_text) BETWEEN 1 AND 500))
          AND typeof(NEW.sort_order) = 'integer'
          AND (NEW.archived_at IS NULL OR (typeof(NEW.archived_at) = 'integer'))
          AND typeof(NEW.created_at) = 'integer'
          AND typeof(NEW.updated_at) = 'integer'
          AND (NEW.system_key IS NOT NULL OR NEW.name IS NOT NULL)
          AND (NEW.name IS NULL) = (NEW.normalized_name IS NULL)
          AND (NEW.archived_at IS NOT NULL OR NEW.normalized_name IS NULL OR NOT EXISTS (SELECT 1 FROM payment_methods p WHERE p.normalized_name = NEW.normalized_name AND p.archived_at IS NULL))
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:payment_methods'); END;

        CREATE TRIGGER guard_payment_methods_update BEFORE UPDATE ON payment_methods
        WHEN NOT IFNULL((
          (NEW.system_key IS NULL OR (typeof(NEW.system_key) = 'text' AND length(NEW.system_key) BETWEEN 1 AND 40))
          AND (NEW.name IS NULL OR (typeof(NEW.name) = 'text' AND length(NEW.name) BETWEEN 1 AND 40))
          AND (NEW.normalized_name IS NULL OR (typeof(NEW.normalized_name) = 'text' AND length(NEW.normalized_name) BETWEEN 1 AND 40))
          AND NEW.kind IN ('CASH', 'CARD', 'TRANSFER', 'WALLET', 'ON_ACCOUNT', 'OTHER')
          AND typeof(NEW.fee_bp) = 'integer' AND NEW.fee_bp BETWEEN 0 AND 10000
          AND typeof(NEW.fee_fixed) = 'integer' AND NEW.fee_fixed BETWEEN 0 AND 1000000000000
          AND typeof(NEW.settlement_days) = 'integer' AND NEW.settlement_days BETWEEN 0 AND 3650
          AND typeof(NEW.price_adjustment_bp) = 'integer' AND NEW.price_adjustment_bp BETWEEN -10000 AND 10000
          AND (NEW.instructions_text IS NULL OR (typeof(NEW.instructions_text) = 'text' AND length(NEW.instructions_text) BETWEEN 1 AND 500))
          AND typeof(NEW.sort_order) = 'integer'
          AND (NEW.archived_at IS NULL OR (typeof(NEW.archived_at) = 'integer'))
          AND typeof(NEW.created_at) = 'integer'
          AND typeof(NEW.updated_at) = 'integer'
          AND (NEW.system_key IS NOT NULL OR NEW.name IS NOT NULL)
          AND (NEW.name IS NULL) = (NEW.normalized_name IS NULL)
          AND (NEW.archived_at IS NOT NULL OR NEW.normalized_name IS NULL OR NOT EXISTS (SELECT 1 FROM payment_methods p WHERE p.normalized_name = NEW.normalized_name AND p.archived_at IS NULL AND p.id <> NEW.id))
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:payment_methods'); END;
        """,
    )

/** Los pagos de una venta: solo crecen. Un pago a crédito no va a una cuenta y exige cliente, y
 * el recibido, el cambio y la otra moneda van con sus columnas. */
private val SALE_PAYMENTS_GUARDS =
    table(
        """
        CREATE TRIGGER guard_sale_payments_insert BEFORE INSERT ON sale_payments
        WHEN NOT IFNULL((
          typeof(NEW.method_name_snapshot) = 'text' AND length(NEW.method_name_snapshot) BETWEEN 1 AND 40
          AND NEW.method_kind_snapshot IN ('CASH', 'CARD', 'TRANSFER', 'WALLET', 'ON_ACCOUNT', 'OTHER')
          AND typeof(NEW.amount) = 'integer' AND NEW.amount BETWEEN 0 AND 1000000000000000
          AND typeof(NEW.fee_amount) = 'integer' AND NEW.fee_amount BETWEEN 0 AND 1000000000000000
          AND date(NEW.settlement_date) = NEW.settlement_date
          AND (NEW.tendered_amount IS NULL OR (typeof(NEW.tendered_amount) = 'integer' AND NEW.tendered_amount BETWEEN 0 AND 1000000000000000))
          AND (NEW.change_amount IS NULL OR (typeof(NEW.change_amount) = 'integer' AND NEW.change_amount BETWEEN 0 AND 1000000000000000))
          AND (NEW.tender_currency_code IS NULL OR (length(NEW.tender_currency_code) = 3 AND NEW.tender_currency_code = upper(NEW.tender_currency_code) AND NEW.tender_currency_code NOT GLOB '*[^A-Z]*'))
          AND (NEW.tender_currency_decimals IS NULL OR (typeof(NEW.tender_currency_decimals) = 'integer' AND NEW.tender_currency_decimals BETWEEN 0 AND 4))
          AND (NEW.tender_amount IS NULL OR (typeof(NEW.tender_amount) = 'integer' AND NEW.tender_amount BETWEEN 0 AND 1000000000000000))
          AND (NEW.fx_rate_micro IS NULL OR (typeof(NEW.fx_rate_micro) = 'integer' AND NEW.fx_rate_micro BETWEEN 1 AND 1000000000000000))
          AND typeof(NEW.created_at) = 'integer'
          AND (NEW.tendered_amount IS NULL) = (NEW.change_amount IS NULL)
          AND (NEW.tendered_amount IS NULL OR (NEW.method_kind_snapshot = 'CASH' AND NEW.tendered_amount - NEW.change_amount = NEW.amount))
          AND (NEW.tender_currency_code IS NULL) = (NEW.tender_currency_decimals IS NULL)
          AND (NEW.tender_currency_code IS NULL) = (NEW.tender_amount IS NULL)
          AND (NEW.tender_currency_code IS NULL) = (NEW.fx_rate_micro IS NULL)
          AND (NEW.method_kind_snapshot <> 'ON_ACCOUNT' OR (NEW.money_account_id IS NULL AND EXISTS (SELECT 1 FROM sales s WHERE s.id = NEW.sale_id AND s.customer_id IS NOT NULL)))
          AND NOT EXISTS (SELECT 1 FROM sale_payments x WHERE x.id = NEW.id)
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:sale_payments'); END;

        CREATE TRIGGER guard_sale_payments_no_update BEFORE UPDATE ON sale_payments
        BEGIN SELECT RAISE(ABORT, 'guard:sale_payments_append_only'); END;

        CREATE TRIGGER guard_sale_payments_no_delete BEFORE DELETE ON sale_payments
        BEGIN SELECT RAISE(ABORT, 'guard:sale_payments_append_only'); END;
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

/** Las ventas en espera: nombre opcional con su largo. No son historia: cambian y se borran. */
private val SALE_DRAFTS_GUARDS =
    table(
        """
        CREATE TRIGGER guard_sale_drafts_insert BEFORE INSERT ON sale_drafts
        WHEN NOT IFNULL((
          (NEW.label IS NULL OR (typeof(NEW.label) = 'text' AND length(NEW.label) BETWEEN 1 AND 40))
          AND typeof(NEW.created_at) = 'integer'
          AND typeof(NEW.updated_at) = 'integer'
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:sale_drafts'); END;

        CREATE TRIGGER guard_sale_drafts_update BEFORE UPDATE ON sale_drafts
        WHEN NOT IFNULL((
          (NEW.label IS NULL OR (typeof(NEW.label) = 'text' AND length(NEW.label) BETWEEN 1 AND 40))
          AND typeof(NEW.created_at) = 'integer'
          AND typeof(NEW.updated_at) = 'integer'
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:sale_drafts'); END;
        """,
    )

/** Las líneas de una venta en espera: cantidad, precio opcional y descuento en sus topes. */
private val SALE_DRAFT_LINES_GUARDS =
    table(
        """
        CREATE TRIGGER guard_sale_draft_lines_insert BEFORE INSERT ON sale_draft_lines
        WHEN NOT IFNULL((
          typeof(NEW.entered_quantity_milli) = 'integer' AND NEW.entered_quantity_milli BETWEEN 1 AND 1000000000
          AND (NEW.unit_price IS NULL OR (typeof(NEW.unit_price) = 'integer' AND NEW.unit_price BETWEEN 0 AND 1000000000000))
          AND typeof(NEW.line_discount) = 'integer' AND NEW.line_discount BETWEEN 0 AND 1000000000000000
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:sale_draft_lines'); END;

        CREATE TRIGGER guard_sale_draft_lines_update BEFORE UPDATE ON sale_draft_lines
        WHEN NOT IFNULL((
          typeof(NEW.entered_quantity_milli) = 'integer' AND NEW.entered_quantity_milli BETWEEN 1 AND 1000000000
          AND (NEW.unit_price IS NULL OR (typeof(NEW.unit_price) = 'integer' AND NEW.unit_price BETWEEN 0 AND 1000000000000))
          AND typeof(NEW.line_discount) = 'integer' AND NEW.line_discount BETWEEN 0 AND 1000000000000000
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:sale_draft_lines'); END;
        """,
    )

/** Las devoluciones de venta: nacen activas, de una venta activa, cuadradas, solo cambian al
 * anularse y nunca se borran. */
private val SALE_RETURNS_GUARDS =
    table(
        """
        CREATE TRIGGER guard_sale_returns_insert BEFORE INSERT ON sale_returns
        WHEN NOT IFNULL((
          NEW.status = 'ACTIVE' AND NEW.voided_at IS NULL AND NEW.void_reason IS NULL AND NEW.void_note IS NULL
          AND NEW.resolution IN ('REFUND', 'STORE_CREDIT', 'EXCHANGE')
          AND typeof(NEW.net_refund) = 'integer' AND NEW.net_refund BETWEEN 0 AND 1000000000000000
          AND typeof(NEW.tax_refund) = 'integer' AND NEW.tax_refund BETWEEN 0 AND 1000000000000000
          AND typeof(NEW.total_refund) = 'integer' AND NEW.total_refund BETWEEN 0 AND 1000000000000000
          AND typeof(NEW.cost_returned) = 'integer' AND NEW.cost_returned BETWEEN 0 AND 1000000000000000
          AND (NEW.note IS NULL OR (typeof(NEW.note) = 'text' AND length(NEW.note) BETWEEN 1 AND 200))
          AND typeof(NEW.occurred_at) = 'integer'
          AND date(NEW.business_date) = NEW.business_date
          AND typeof(NEW.created_at) = 'integer'
          AND NEW.total_refund = NEW.net_refund + NEW.tax_refund
          AND EXISTS (SELECT 1 FROM sales s WHERE s.id = NEW.sale_id AND s.status = 'ACTIVE')
          AND NOT EXISTS (SELECT 1 FROM sale_returns x WHERE x.id = NEW.id)
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:sale_returns'); END;

        CREATE TRIGGER guard_sale_returns_void_only BEFORE UPDATE ON sale_returns
        WHEN NOT IFNULL((
          OLD.status = 'ACTIVE' AND NEW.status = 'VOIDED' AND NEW.voided_at IS NOT NULL
          AND NEW.void_reason IN ('ENTRY_ERROR', 'UNDO', 'OTHER')
          AND (NEW.void_note IS NULL OR length(NEW.void_note) <= 200)
          AND NEW.id IS OLD.id AND NEW.sale_id IS OLD.sale_id AND NEW.resolution IS OLD.resolution AND NEW.net_refund IS OLD.net_refund AND NEW.tax_refund IS OLD.tax_refund AND NEW.total_refund IS OLD.total_refund
          AND NEW.cost_returned IS OLD.cost_returned AND NEW.money_account_id IS OLD.money_account_id AND NEW.replacement_sale_id IS OLD.replacement_sale_id AND NEW.note IS OLD.note AND NEW.occurred_at IS OLD.occurred_at AND NEW.business_date IS OLD.business_date
          AND NEW.created_at IS OLD.created_at
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:sale_returns_void_only'); END;

        CREATE TRIGGER guard_sale_returns_no_delete BEFORE DELETE ON sale_returns
        BEGIN SELECT RAISE(ABORT, 'guard:sale_returns_no_delete'); END;
        """,
    )

/** Las líneas de las devoluciones de venta: no cambian ni se borran, y entre todas las
 * devoluciones activas una línea vendida no devuelve más de lo que se vendió. */
private val SALE_RETURN_LINES_GUARDS =
    table(
        """
        CREATE TRIGGER guard_sale_return_lines_insert BEFORE INSERT ON sale_return_lines
        WHEN NOT IFNULL((
          typeof(NEW.quantity_milli) = 'integer' AND NEW.quantity_milli BETWEEN 1 AND 1000000000
          AND typeof(NEW.net_amount) = 'integer' AND NEW.net_amount BETWEEN 0 AND 1000000000000000
          AND typeof(NEW.tax_amount) = 'integer' AND NEW.tax_amount BETWEEN 0 AND 1000000000000000
          AND typeof(NEW.line_total) = 'integer' AND NEW.line_total BETWEEN 0 AND 1000000000000000
          AND typeof(NEW.line_cost) = 'integer' AND NEW.line_cost BETWEEN 0 AND 1000000000000000
          AND NEW.restock IN (0, 1)
          AND NEW.line_total = NEW.net_amount + NEW.tax_amount
          AND (SELECT COALESCE(SUM(rl.quantity_milli), 0) FROM sale_return_lines rl JOIN sale_returns r ON r.id = rl.sale_return_id WHERE rl.sale_line_id = NEW.sale_line_id AND r.status = 'ACTIVE') + NEW.quantity_milli <= (SELECT sl.quantity_milli FROM sale_lines sl WHERE sl.id = NEW.sale_line_id)
          AND NOT EXISTS (SELECT 1 FROM sale_return_lines x WHERE x.id = NEW.id)
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:sale_return_lines'); END;

        CREATE TRIGGER guard_sale_return_lines_no_update BEFORE UPDATE ON sale_return_lines
        BEGIN SELECT RAISE(ABORT, 'guard:sale_return_lines_append_only'); END;

        CREATE TRIGGER guard_sale_return_lines_no_delete BEFORE DELETE ON sale_return_lines
        BEGIN SELECT RAISE(ABORT, 'guard:sale_return_lines_append_only'); END;
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
 * Un registro con dinero exige la moneda confirmada y bloqueada en los ajustes: sin moneda, un
 * monto no tendría unidad. */
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
 * el monto opuesto, una sola vez y nunca a otra anulación. Una transferencia se anula con dos
 * anulaciones, una por parte, en la misma transacción: la guarda ve una fila a la vez, y que vayan
 * las dos lo cuida el caso de uso.
 *
 * Un registro con dinero exige la moneda confirmada y bloqueada en los ajustes: sin moneda, un
 * monto no tendría unidad. */
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
          AND (NEW.reverses_movement_id IS NULL OR EXISTS (SELECT 1 FROM money_movements o WHERE o.id = NEW.reverses_movement_id AND o.money_account_id = NEW.money_account_id AND o.amount = -NEW.amount AND o.type <> 'MOVEMENT_VOID'))
          AND NOT EXISTS (SELECT 1 FROM money_movements m WHERE m.reverses_movement_id = NEW.reverses_movement_id)
          AND NOT EXISTS (SELECT 1 FROM money_movements x WHERE x.id = NEW.id)
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:money_movements'); END;

        CREATE TRIGGER guard_money_movements_no_update BEFORE UPDATE ON money_movements
        BEGIN SELECT RAISE(ABORT, 'guard:money_movements_append_only'); END;

        CREATE TRIGGER guard_money_movements_no_delete BEFORE DELETE ON money_movements
        BEGIN SELECT RAISE(ABORT, 'guard:money_movements_append_only'); END;
        """,
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
 * Un registro con dinero exige la moneda confirmada y bloqueada en los ajustes: sin moneda, un
 * monto no tendría unidad. */
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
    )

/** Los gastos recurrentes: el día cabe en su período, y el próximo vencimiento es una fecha. */
private val RECURRING_EXPENSES_GUARDS =
    table(
        """
        CREATE TRIGGER guard_recurring_expenses_insert BEFORE INSERT ON recurring_expenses
        WHEN NOT IFNULL((
          typeof(NEW.amount) = 'integer' AND NEW.amount BETWEEN 1 AND 1000000000000000
          AND (NEW.description IS NULL OR (typeof(NEW.description) = 'text' AND length(NEW.description) BETWEEN 1 AND 200))
          AND NEW.recurrence IN ('WEEKLY', 'MONTHLY', 'YEARLY')
          AND typeof(NEW.day_of_period) = 'integer' AND NEW.day_of_period BETWEEN 1 AND 366
          AND date(NEW.next_due_date) = NEW.next_due_date
          AND NEW.reminder_enabled IN (0, 1)
          AND (NEW.archived_at IS NULL OR (typeof(NEW.archived_at) = 'integer'))
          AND typeof(NEW.created_at) = 'integer'
          AND typeof(NEW.updated_at) = 'integer'
          AND CASE NEW.recurrence WHEN 'WEEKLY' THEN NEW.day_of_period <= 7 WHEN 'MONTHLY' THEN NEW.day_of_period <= 31 ELSE 1 END
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:recurring_expenses'); END;

        CREATE TRIGGER guard_recurring_expenses_update BEFORE UPDATE ON recurring_expenses
        WHEN NOT IFNULL((
          typeof(NEW.amount) = 'integer' AND NEW.amount BETWEEN 1 AND 1000000000000000
          AND (NEW.description IS NULL OR (typeof(NEW.description) = 'text' AND length(NEW.description) BETWEEN 1 AND 200))
          AND NEW.recurrence IN ('WEEKLY', 'MONTHLY', 'YEARLY')
          AND typeof(NEW.day_of_period) = 'integer' AND NEW.day_of_period BETWEEN 1 AND 366
          AND date(NEW.next_due_date) = NEW.next_due_date
          AND NEW.reminder_enabled IN (0, 1)
          AND (NEW.archived_at IS NULL OR (typeof(NEW.archived_at) = 'integer'))
          AND typeof(NEW.created_at) = 'integer'
          AND typeof(NEW.updated_at) = 'integer'
          AND CASE NEW.recurrence WHEN 'WEEKLY' THEN NEW.day_of_period <= 7 WHEN 'MONTHLY' THEN NEW.day_of_period <= 31 ELSE 1 END
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:recurring_expenses'); END;
        """,
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
          AND (NEW.archived_at IS NOT NULL OR NEW.normalized_name IS NULL OR NOT EXISTS (SELECT 1 FROM price_lists l WHERE l.normalized_name = NEW.normalized_name AND l.archived_at IS NULL))
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
          AND (NEW.archived_at IS NOT NULL OR NEW.normalized_name IS NULL OR NOT EXISTS (SELECT 1 FROM price_lists l WHERE l.normalized_name = NEW.normalized_name AND l.archived_at IS NULL AND l.id <> NEW.id))
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:price_lists'); END;

        CREATE TRIGGER guard_price_lists_no_delete BEFORE DELETE ON price_lists
        WHEN OLD.system_key = 'DEFAULT'
        BEGIN SELECT RAISE(ABORT, 'guard:price_lists_system'); END;
        """,
    )

/** Los precios por lista: solo en una lista fija distinta de la inicial, que usa el precio de
 * venta, y nunca en una relativa, que se calcula. */
private val PRODUCT_PRICES_GUARDS =
    table(
        """
        CREATE TRIGGER guard_product_prices_insert BEFORE INSERT ON product_prices
        WHEN NOT IFNULL((
          typeof(NEW.price) = 'integer' AND NEW.price BETWEEN 0 AND 1000000000000
          AND typeof(NEW.created_at) = 'integer'
          AND typeof(NEW.updated_at) = 'integer'
          AND EXISTS (SELECT 1 FROM price_lists l WHERE l.id = NEW.price_list_id AND l.kind = 'FIXED' AND l.system_key IS NOT 'DEFAULT')
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:product_prices'); END;

        CREATE TRIGGER guard_product_prices_update BEFORE UPDATE ON product_prices
        WHEN NOT IFNULL((
          typeof(NEW.price) = 'integer' AND NEW.price BETWEEN 0 AND 1000000000000
          AND typeof(NEW.created_at) = 'integer'
          AND typeof(NEW.updated_at) = 'integer'
          AND EXISTS (SELECT 1 FROM price_lists l WHERE l.id = NEW.price_list_id AND l.kind = 'FIXED' AND l.system_key IS NOT 'DEFAULT')
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:product_prices'); END;
        """,
    )

/** Las cotizaciones manuales: solo crecen, y la cotización es siempre mayor que cero. */
private val FX_RATES_GUARDS =
    table(
        """
        CREATE TRIGGER guard_fx_rates_insert BEFORE INSERT ON fx_rates
        WHEN NOT IFNULL((
          length(NEW.currency_code) = 3 AND NEW.currency_code = upper(NEW.currency_code) AND NEW.currency_code NOT GLOB '*[^A-Z]*'
          AND typeof(NEW.rate_micro) = 'integer' AND NEW.rate_micro BETWEEN 1 AND 1000000000000000
          AND (NEW.note IS NULL OR (typeof(NEW.note) = 'text' AND length(NEW.note) BETWEEN 1 AND 200))
          AND typeof(NEW.occurred_at) = 'integer'
          AND date(NEW.business_date) = NEW.business_date
          AND typeof(NEW.created_at) = 'integer'
          AND NOT EXISTS (SELECT 1 FROM fx_rates x WHERE x.id = NEW.id)
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:fx_rates'); END;

        CREATE TRIGGER guard_fx_rates_no_update BEFORE UPDATE ON fx_rates
        BEGIN SELECT RAISE(ABORT, 'guard:fx_rates_append_only'); END;

        CREATE TRIGGER guard_fx_rates_no_delete BEFORE DELETE ON fx_rates
        BEGIN SELECT RAISE(ABORT, 'guard:fx_rates_append_only'); END;
        """,
    )

/** El índice de precios: una variación por mes `YYYY-MM`, dentro de su rango. */
private val PRICE_INDEX_ENTRIES_GUARDS =
    table(
        """
        CREATE TRIGGER guard_price_index_entries_insert BEFORE INSERT ON price_index_entries
        WHEN NOT IFNULL((
          length(NEW.month) = 7 AND date(NEW.month || '-01') = NEW.month || '-01'
          AND typeof(NEW.change_bp) = 'integer' AND NEW.change_bp BETWEEN -10000 AND 1000000
          AND (NEW.source_note IS NULL OR (typeof(NEW.source_note) = 'text' AND length(NEW.source_note) BETWEEN 1 AND 120))
          AND typeof(NEW.created_at) = 'integer'
          AND typeof(NEW.updated_at) = 'integer'
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:price_index_entries'); END;

        CREATE TRIGGER guard_price_index_entries_update BEFORE UPDATE ON price_index_entries
        WHEN NOT IFNULL((
          length(NEW.month) = 7 AND date(NEW.month || '-01') = NEW.month || '-01'
          AND typeof(NEW.change_bp) = 'integer' AND NEW.change_bp BETWEEN -10000 AND 1000000
          AND (NEW.source_note IS NULL OR (typeof(NEW.source_note) = 'text' AND length(NEW.source_note) BETWEEN 1 AND 120))
          AND typeof(NEW.created_at) = 'integer'
          AND typeof(NEW.updated_at) = 'integer'
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:price_index_entries'); END;
        """,
    )

/**
 * Las cotizaciones: nacen abiertas, cuadran su total y se convierten o cancelan una sola vez. Ya
 * cerradas, no cambian: solo `updated_at`.
 */
private val QUOTES_GUARDS =
    table(
        """
        CREATE TRIGGER guard_quotes_insert BEFORE INSERT ON quotes
        WHEN NOT IFNULL((
          NEW.status IN ('OPEN', 'CONVERTED', 'CANCELLED')
          AND (NEW.valid_until IS NULL OR (date(NEW.valid_until) = NEW.valid_until))
          AND typeof(NEW.net_total) = 'integer' AND NEW.net_total BETWEEN 0 AND 1000000000000000
          AND typeof(NEW.tax_total) = 'integer' AND NEW.tax_total BETWEEN 0 AND 1000000000000000
          AND typeof(NEW.total) = 'integer' AND NEW.total BETWEEN 0 AND 1000000000000000
          AND (NEW.note IS NULL OR (typeof(NEW.note) = 'text' AND length(NEW.note) BETWEEN 1 AND 200))
          AND typeof(NEW.occurred_at) = 'integer'
          AND date(NEW.business_date) = NEW.business_date
          AND typeof(NEW.created_at) = 'integer'
          AND typeof(NEW.updated_at) = 'integer'
          AND NEW.total = NEW.net_total + NEW.tax_total
          AND (NEW.status = 'CONVERTED') = (NEW.converted_sale_id IS NOT NULL)
          AND NEW.status = 'OPEN'
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:quotes'); END;

        CREATE TRIGGER guard_quotes_update BEFORE UPDATE ON quotes
        WHEN NOT IFNULL((
          NEW.status IN ('OPEN', 'CONVERTED', 'CANCELLED')
          AND (NEW.valid_until IS NULL OR (date(NEW.valid_until) = NEW.valid_until))
          AND typeof(NEW.net_total) = 'integer' AND NEW.net_total BETWEEN 0 AND 1000000000000000
          AND typeof(NEW.tax_total) = 'integer' AND NEW.tax_total BETWEEN 0 AND 1000000000000000
          AND typeof(NEW.total) = 'integer' AND NEW.total BETWEEN 0 AND 1000000000000000
          AND (NEW.note IS NULL OR (typeof(NEW.note) = 'text' AND length(NEW.note) BETWEEN 1 AND 200))
          AND typeof(NEW.occurred_at) = 'integer'
          AND date(NEW.business_date) = NEW.business_date
          AND typeof(NEW.created_at) = 'integer'
          AND typeof(NEW.updated_at) = 'integer'
          AND NEW.total = NEW.net_total + NEW.tax_total
          AND (NEW.status = 'CONVERTED') = (NEW.converted_sale_id IS NOT NULL)
          AND (OLD.status = 'OPEN' OR (NEW.status = OLD.status AND NEW.converted_sale_id IS OLD.converted_sale_id AND NEW.customer_id IS OLD.customer_id AND NEW.valid_until IS OLD.valid_until AND NEW.net_total = OLD.net_total AND NEW.tax_total = OLD.tax_total AND NEW.total = OLD.total AND NEW.note IS OLD.note AND NEW.occurred_at = OLD.occurred_at AND NEW.business_date = OLD.business_date AND NEW.created_at = OLD.created_at))
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:quotes'); END;
        """,
    )

/**
 * Las líneas de cotización: cuadran su total, y solo cambian o se borran con su cotización
 * abierta.
 */
private val QUOTE_LINES_GUARDS =
    table(
        """
        CREATE TRIGGER guard_quote_lines_insert BEFORE INSERT ON quote_lines
        WHEN NOT IFNULL((
          typeof(NEW.unit_factor_milli) = 'integer' AND NEW.unit_factor_milli BETWEEN 1 AND 1000000000
          AND typeof(NEW.entered_quantity_milli) = 'integer' AND NEW.entered_quantity_milli BETWEEN 1 AND 1000000000
          AND typeof(NEW.quantity_milli) = 'integer' AND NEW.quantity_milli BETWEEN 1 AND 1000000000
          AND typeof(NEW.unit_price) = 'integer' AND NEW.unit_price BETWEEN 0 AND 1000000000000
          AND typeof(NEW.line_discount) = 'integer' AND NEW.line_discount BETWEEN 0 AND 1000000000000000
          AND typeof(NEW.net_amount) = 'integer' AND NEW.net_amount BETWEEN 0 AND 1000000000000000
          AND typeof(NEW.tax_rate_bp) = 'integer' AND NEW.tax_rate_bp BETWEEN 0 AND 10000
          AND (NEW.tax_name_snapshot IS NULL OR (typeof(NEW.tax_name_snapshot) = 'text' AND length(NEW.tax_name_snapshot) BETWEEN 1 AND 40))
          AND typeof(NEW.tax_amount) = 'integer' AND NEW.tax_amount BETWEEN 0 AND 1000000000000000
          AND typeof(NEW.line_total) = 'integer' AND NEW.line_total BETWEEN 0 AND 1000000000000000
          AND typeof(NEW.product_name_snapshot) = 'text' AND length(NEW.product_name_snapshot) BETWEEN 1 AND 120
          AND NEW.line_total = NEW.net_amount + NEW.tax_amount
          AND EXISTS (SELECT 1 FROM quotes d WHERE d.id = NEW.quote_id AND d.status = 'OPEN')
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:quote_lines'); END;

        CREATE TRIGGER guard_quote_lines_update BEFORE UPDATE ON quote_lines
        WHEN NOT IFNULL((
          typeof(NEW.unit_factor_milli) = 'integer' AND NEW.unit_factor_milli BETWEEN 1 AND 1000000000
          AND typeof(NEW.entered_quantity_milli) = 'integer' AND NEW.entered_quantity_milli BETWEEN 1 AND 1000000000
          AND typeof(NEW.quantity_milli) = 'integer' AND NEW.quantity_milli BETWEEN 1 AND 1000000000
          AND typeof(NEW.unit_price) = 'integer' AND NEW.unit_price BETWEEN 0 AND 1000000000000
          AND typeof(NEW.line_discount) = 'integer' AND NEW.line_discount BETWEEN 0 AND 1000000000000000
          AND typeof(NEW.net_amount) = 'integer' AND NEW.net_amount BETWEEN 0 AND 1000000000000000
          AND typeof(NEW.tax_rate_bp) = 'integer' AND NEW.tax_rate_bp BETWEEN 0 AND 10000
          AND (NEW.tax_name_snapshot IS NULL OR (typeof(NEW.tax_name_snapshot) = 'text' AND length(NEW.tax_name_snapshot) BETWEEN 1 AND 40))
          AND typeof(NEW.tax_amount) = 'integer' AND NEW.tax_amount BETWEEN 0 AND 1000000000000000
          AND typeof(NEW.line_total) = 'integer' AND NEW.line_total BETWEEN 0 AND 1000000000000000
          AND typeof(NEW.product_name_snapshot) = 'text' AND length(NEW.product_name_snapshot) BETWEEN 1 AND 120
          AND NEW.line_total = NEW.net_amount + NEW.tax_amount
          AND EXISTS (SELECT 1 FROM quotes d WHERE d.id = NEW.quote_id AND d.status = 'OPEN')
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:quote_lines'); END;

        CREATE TRIGGER guard_quote_lines_no_delete BEFORE DELETE ON quote_lines
        WHEN EXISTS (SELECT 1 FROM quotes d WHERE d.id = OLD.quote_id AND d.status <> 'OPEN')
        BEGIN SELECT RAISE(ABORT, 'guard:quote_lines_closed'); END;
        """,
    )

/**
 * Los pedidos: nacen pendientes, y se entregan o cancelan una sola vez. Ya cerrados, no cambian,
 * salvo `updated_at` y vaciar la dirección y la nota de entrega, que es lo que borra anonimizar al
 * cliente.
 */
private val ORDERS_GUARDS =
    table(
        """
        CREATE TRIGGER guard_orders_insert BEFORE INSERT ON orders
        WHEN NOT IFNULL((
          NEW.status IN ('PENDING', 'READY', 'DELIVERED', 'CANCELLED')
          AND (NEW.delivery_address IS NULL OR (typeof(NEW.delivery_address) = 'text' AND length(NEW.delivery_address) BETWEEN 1 AND 200))
          AND (NEW.delivery_note IS NULL OR (typeof(NEW.delivery_note) = 'text' AND length(NEW.delivery_note) BETWEEN 1 AND 200))
          AND typeof(NEW.shipping_amount) = 'integer' AND NEW.shipping_amount BETWEEN 0 AND 1000000000000000
          AND (NEW.due_date IS NULL OR (date(NEW.due_date) = NEW.due_date))
          AND typeof(NEW.total) = 'integer' AND NEW.total BETWEEN 0 AND 1000000000000000
          AND typeof(NEW.occurred_at) = 'integer'
          AND date(NEW.business_date) = NEW.business_date
          AND typeof(NEW.created_at) = 'integer'
          AND typeof(NEW.updated_at) = 'integer'
          AND (NEW.status = 'DELIVERED') = (NEW.delivered_sale_id IS NOT NULL)
          AND NEW.status = 'PENDING'
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:orders'); END;

        CREATE TRIGGER guard_orders_update BEFORE UPDATE ON orders
        WHEN NOT IFNULL((
          NEW.status IN ('PENDING', 'READY', 'DELIVERED', 'CANCELLED')
          AND (NEW.delivery_address IS NULL OR (typeof(NEW.delivery_address) = 'text' AND length(NEW.delivery_address) BETWEEN 1 AND 200))
          AND (NEW.delivery_note IS NULL OR (typeof(NEW.delivery_note) = 'text' AND length(NEW.delivery_note) BETWEEN 1 AND 200))
          AND typeof(NEW.shipping_amount) = 'integer' AND NEW.shipping_amount BETWEEN 0 AND 1000000000000000
          AND (NEW.due_date IS NULL OR (date(NEW.due_date) = NEW.due_date))
          AND typeof(NEW.total) = 'integer' AND NEW.total BETWEEN 0 AND 1000000000000000
          AND typeof(NEW.occurred_at) = 'integer'
          AND date(NEW.business_date) = NEW.business_date
          AND typeof(NEW.created_at) = 'integer'
          AND typeof(NEW.updated_at) = 'integer'
          AND (NEW.status = 'DELIVERED') = (NEW.delivered_sale_id IS NOT NULL)
          AND (OLD.status IN ('PENDING', 'READY') OR (NEW.status = OLD.status AND NEW.delivered_sale_id IS OLD.delivered_sale_id AND NEW.customer_id IS OLD.customer_id AND (NEW.delivery_address IS OLD.delivery_address OR NEW.delivery_address IS NULL) AND (NEW.delivery_note IS OLD.delivery_note OR NEW.delivery_note IS NULL) AND NEW.shipping_amount = OLD.shipping_amount AND NEW.due_date IS OLD.due_date AND NEW.total = OLD.total AND NEW.occurred_at = OLD.occurred_at AND NEW.business_date = OLD.business_date AND NEW.created_at = OLD.created_at))
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:orders'); END;
        """,
    )

/**
 * Las líneas de pedido: cuadran su total, y solo cambian o se borran con su pedido sin entregar ni
 * cancelar.
 */
private val ORDER_LINES_GUARDS =
    table(
        """
        CREATE TRIGGER guard_order_lines_insert BEFORE INSERT ON order_lines
        WHEN NOT IFNULL((
          typeof(NEW.unit_factor_milli) = 'integer' AND NEW.unit_factor_milli BETWEEN 1 AND 1000000000
          AND typeof(NEW.entered_quantity_milli) = 'integer' AND NEW.entered_quantity_milli BETWEEN 1 AND 1000000000
          AND typeof(NEW.quantity_milli) = 'integer' AND NEW.quantity_milli BETWEEN 1 AND 1000000000
          AND typeof(NEW.unit_price) = 'integer' AND NEW.unit_price BETWEEN 0 AND 1000000000000
          AND typeof(NEW.line_discount) = 'integer' AND NEW.line_discount BETWEEN 0 AND 1000000000000000
          AND typeof(NEW.net_amount) = 'integer' AND NEW.net_amount BETWEEN 0 AND 1000000000000000
          AND typeof(NEW.tax_rate_bp) = 'integer' AND NEW.tax_rate_bp BETWEEN 0 AND 10000
          AND (NEW.tax_name_snapshot IS NULL OR (typeof(NEW.tax_name_snapshot) = 'text' AND length(NEW.tax_name_snapshot) BETWEEN 1 AND 40))
          AND typeof(NEW.tax_amount) = 'integer' AND NEW.tax_amount BETWEEN 0 AND 1000000000000000
          AND typeof(NEW.line_total) = 'integer' AND NEW.line_total BETWEEN 0 AND 1000000000000000
          AND typeof(NEW.product_name_snapshot) = 'text' AND length(NEW.product_name_snapshot) BETWEEN 1 AND 120
          AND NEW.line_total = NEW.net_amount + NEW.tax_amount
          AND EXISTS (SELECT 1 FROM orders d WHERE d.id = NEW.order_id AND d.status IN ('PENDING', 'READY'))
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:order_lines'); END;

        CREATE TRIGGER guard_order_lines_update BEFORE UPDATE ON order_lines
        WHEN NOT IFNULL((
          typeof(NEW.unit_factor_milli) = 'integer' AND NEW.unit_factor_milli BETWEEN 1 AND 1000000000
          AND typeof(NEW.entered_quantity_milli) = 'integer' AND NEW.entered_quantity_milli BETWEEN 1 AND 1000000000
          AND typeof(NEW.quantity_milli) = 'integer' AND NEW.quantity_milli BETWEEN 1 AND 1000000000
          AND typeof(NEW.unit_price) = 'integer' AND NEW.unit_price BETWEEN 0 AND 1000000000000
          AND typeof(NEW.line_discount) = 'integer' AND NEW.line_discount BETWEEN 0 AND 1000000000000000
          AND typeof(NEW.net_amount) = 'integer' AND NEW.net_amount BETWEEN 0 AND 1000000000000000
          AND typeof(NEW.tax_rate_bp) = 'integer' AND NEW.tax_rate_bp BETWEEN 0 AND 10000
          AND (NEW.tax_name_snapshot IS NULL OR (typeof(NEW.tax_name_snapshot) = 'text' AND length(NEW.tax_name_snapshot) BETWEEN 1 AND 40))
          AND typeof(NEW.tax_amount) = 'integer' AND NEW.tax_amount BETWEEN 0 AND 1000000000000000
          AND typeof(NEW.line_total) = 'integer' AND NEW.line_total BETWEEN 0 AND 1000000000000000
          AND typeof(NEW.product_name_snapshot) = 'text' AND length(NEW.product_name_snapshot) BETWEEN 1 AND 120
          AND NEW.line_total = NEW.net_amount + NEW.tax_amount
          AND EXISTS (SELECT 1 FROM orders d WHERE d.id = NEW.order_id AND d.status IN ('PENDING', 'READY'))
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:order_lines'); END;

        CREATE TRIGGER guard_order_lines_no_delete BEFORE DELETE ON order_lines
        WHEN EXISTS (SELECT 1 FROM orders d WHERE d.id = OLD.order_id AND d.status NOT IN ('PENDING', 'READY'))
        BEGIN SELECT RAISE(ABORT, 'guard:order_lines_closed'); END;
        """,
    )

/** Las marcas de las ventas: `RECORDED` con su instante, y nunca pasan a otra venta. */
private val SALE_FISCAL_MARKS_GUARDS =
    table(
        """
        CREATE TRIGGER guard_sale_fiscal_marks_insert BEFORE INSERT ON sale_fiscal_marks
        WHEN NOT IFNULL((
          NEW.status IN ('PENDING', 'RECORDED', 'NOT_NEEDED')
          AND (NEW.external_reference IS NULL OR (typeof(NEW.external_reference) = 'text' AND length(NEW.external_reference) BETWEEN 1 AND 60))
          AND (NEW.recorded_at IS NULL OR (typeof(NEW.recorded_at) = 'integer'))
          AND typeof(NEW.updated_at) = 'integer'
          AND (NEW.status <> 'RECORDED' OR NEW.recorded_at IS NOT NULL)
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:sale_fiscal_marks'); END;

        CREATE TRIGGER guard_sale_fiscal_marks_update BEFORE UPDATE ON sale_fiscal_marks
        WHEN NOT IFNULL((
          NEW.status IN ('PENDING', 'RECORDED', 'NOT_NEEDED')
          AND (NEW.external_reference IS NULL OR (typeof(NEW.external_reference) = 'text' AND length(NEW.external_reference) BETWEEN 1 AND 60))
          AND (NEW.recorded_at IS NULL OR (typeof(NEW.recorded_at) = 'integer'))
          AND typeof(NEW.updated_at) = 'integer'
          AND (NEW.status <> 'RECORDED' OR NEW.recorded_at IS NOT NULL)
          AND NEW.sale_id = OLD.sale_id
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:sale_fiscal_marks'); END;
        """,
    )

/** Las fotos de comprobantes: cada una de exactamente un documento. */
private val DOCUMENT_ATTACHMENTS_GUARDS =
    table(
        """
        CREATE TRIGGER guard_document_attachments_insert BEFORE INSERT ON document_attachments
        WHEN NOT IFNULL((
          (NEW.caption IS NULL OR (typeof(NEW.caption) = 'text' AND length(NEW.caption) BETWEEN 1 AND 120))
          AND typeof(NEW.created_at) = 'integer'
          AND (NEW.expense_id IS NULL) <> (NEW.purchase_id IS NULL)
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:document_attachments'); END;

        CREATE TRIGGER guard_document_attachments_update BEFORE UPDATE ON document_attachments
        WHEN NOT IFNULL((
          (NEW.caption IS NULL OR (typeof(NEW.caption) = 'text' AND length(NEW.caption) BETWEEN 1 AND 120))
          AND typeof(NEW.created_at) = 'integer'
          AND (NEW.expense_id IS NULL) <> (NEW.purchase_id IS NULL)
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:document_attachments'); END;
        """,
    )

/** Las metas: una fila por mes `YYYY-MM`, con metas dentro del rango del dinero. */
private val BUSINESS_GOALS_GUARDS =
    table(
        """
        CREATE TRIGGER guard_business_goals_insert BEFORE INSERT ON business_goals
        WHEN NOT IFNULL((
          length(NEW.month) = 7 AND date(NEW.month || '-01') = NEW.month || '-01'
          AND (NEW.sales_goal IS NULL OR (typeof(NEW.sales_goal) = 'integer' AND NEW.sales_goal BETWEEN 0 AND 9000000000000000))
          AND (NEW.profit_goal IS NULL OR (typeof(NEW.profit_goal) = 'integer' AND NEW.profit_goal BETWEEN -9000000000000000 AND 9000000000000000))
          AND typeof(NEW.updated_at) = 'integer'
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:business_goals'); END;

        CREATE TRIGGER guard_business_goals_update BEFORE UPDATE ON business_goals
        WHEN NOT IFNULL((
          length(NEW.month) = 7 AND date(NEW.month || '-01') = NEW.month || '-01'
          AND (NEW.sales_goal IS NULL OR (typeof(NEW.sales_goal) = 'integer' AND NEW.sales_goal BETWEEN 0 AND 9000000000000000))
          AND (NEW.profit_goal IS NULL OR (typeof(NEW.profit_goal) = 'integer' AND NEW.profit_goal BETWEEN -9000000000000000 AND 9000000000000000))
          AND typeof(NEW.updated_at) = 'integer'
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:business_goals'); END;
        """,
    )

/** El registro de auditoría: solo crece, y solo se borra lo que pasa de las 5.000 filas. */
private val AUDIT_EVENTS_GUARDS =
    table(
        """
        CREATE TRIGGER guard_audit_events_insert BEFORE INSERT ON audit_events
        WHEN NOT IFNULL((
          typeof(NEW.occurred_at) = 'integer'
          AND NEW.event_type IN ('EXPORT_CSV', 'EXPORT_FULL_COPY', 'RESTORE', 'RESTORE_REJECTED', 'DESTINATION_DISCONNECTED', 'DESTINATION_CHANGED', 'APP_LOCK_CHANGED', 'ENCRYPTION_CHANGED', 'RECOVERY_CODE_VIEWED', 'DELETE_MY_DATA', 'AUTH_FAILED')
          AND NEW.result IN ('SUCCESS', 'FAILURE')
          AND NOT EXISTS (SELECT 1 FROM audit_events x WHERE x.id = NEW.id)
        ), 0)
        BEGIN SELECT RAISE(ABORT, 'guard:audit_events'); END;

        CREATE TRIGGER guard_audit_events_no_update BEFORE UPDATE ON audit_events
        BEGIN SELECT RAISE(ABORT, 'guard:audit_events_append_only'); END;

        CREATE TRIGGER guard_audit_events_no_delete BEFORE DELETE ON audit_events
        WHEN (SELECT COUNT(*) FROM audit_events) <= 5000
        BEGIN SELECT RAISE(ABORT, 'guard:audit_events_append_only'); END;
        """,
    )

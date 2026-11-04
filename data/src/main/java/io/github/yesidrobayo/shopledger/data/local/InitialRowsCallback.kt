package io.github.yesidrobayo.shopledger.data.local

import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteConnection
import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.catalog.UnitKind
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.settings.AppLockTimeout
import io.github.yesidrobayo.shopledger.domain.model.settings.ColorPalette
import io.github.yesidrobayo.shopledger.domain.model.settings.MeteredBackupPolicy
import io.github.yesidrobayo.shopledger.domain.model.settings.ThemeMode
import io.github.yesidrobayo.shopledger.domain.model.stock.LocationKind
import io.github.yesidrobayo.shopledger.domain.port.Clock

/**
 * Crea, al nacer la base, las filas que la app necesita desde la primera pantalla: la única de
 * `app_settings` y la única de `backup_state`, las dos con `id` 1, la cuenta de dinero `CASH`, las
 * unidades iniciales, la ubicación inicial, los seis métodos de pago y la lista de precios
 * inicial.
 *
 * Room llama a [onCreate] solo cuando crea la base, dentro de la misma transacción exclusiva en
 * que crea las tablas y antes de fijar la versión: si una inserción falla, no queda ni la base a
 * medias ni una versión que diga que está completa, y la próxima apertura la crea de nuevo. Una
 * base que ya existía, como una restaurada o una migrada, nunca pasa por aquí.
 *
 * Los valores iniciales son los del modelo de datos, sin nada de un país ni de un idioma: la
 * configuración regional llega en sus propias columnas. Las filas iniciales de un catálogo llevan
 * solo su clave estable en inglés, sin nombre ni símbolo: el texto visible sale de los recursos
 * traducibles de esa clave, en el idioma de la interfaz, hasta que la persona lo edite. [clock] da
 * el instante de `updated_at`, y el de `created_at` donde lo hay.
 */
internal class InitialRowsCallback(private val clock: Clock) : RoomDatabase.Callback() {
    override suspend fun onCreate(connection: SQLiteConnection) {
        val now = clock.nowEpochMillis()
        connection.prepare(INSERT_APP_SETTINGS_SQL).use { statement ->
            statement.bindLong(1, now)
            statement.step()
        }
        connection.prepare(INSERT_BACKUP_STATE_SQL).use { it.step() }
        connection.prepare(INSERT_CASH_ACCOUNT_SQL).use { statement ->
            statement.bindLong(1, now)
            statement.bindLong(2, now)
            statement.step()
        }
        connection.prepare(INSERT_UNIT_SQL).use { statement ->
            for (unit in INITIAL_UNITS) {
                statement.bindText(1, unit.systemKey)
                statement.bindText(2, unit.kind.name)
                statement.bindLong(3, if (unit.allowsFraction) 1L else 0L)
                statement.bindLong(4, now)
                statement.bindLong(5, now)
                statement.step()
                statement.reset()
            }
        }
        connection.prepare(INSERT_MAIN_LOCATION_SQL).use { statement ->
            statement.bindLong(1, now)
            statement.bindLong(2, now)
            statement.step()
        }
        insertPaymentMethods(connection, now)
        connection.prepare(INSERT_DEFAULT_PRICE_LIST_SQL).use { statement ->
            statement.bindLong(1, now)
            statement.bindLong(2, now)
            statement.step()
        }
    }

    /**
     * Los seis métodos de pago iniciales, uno por tipo y en el orden en que se ofrecen al cobrar,
     * con el efectivo primero. Solo llevan su clave: sin nombre, que sale traducido de la clave, y
     * sin comisión, días de acreditación, ajuste, cuenta ni instrucciones, que escribe la persona.
     */
    private fun insertPaymentMethods(connection: SQLiteConnection, now: Long) {
        connection.prepare(INSERT_PAYMENT_METHOD_SQL).use { statement ->
            INITIAL_PAYMENT_METHODS.forEachIndexed { index, kind ->
                statement.reset()
                statement.bindText(1, kind.name)
                statement.bindText(2, kind.name)
                statement.bindLong(3, index + 1L)
                statement.bindLong(4, now)
                statement.bindLong(5, now)
                statement.step()
            }
        }
    }
}

/**
 * Los tipos de los métodos iniciales, en su orden. La clave estable de cada uno es el nombre de
 * su tipo: un método genérico por tipo, sin ninguna marca.
 */
private val INITIAL_PAYMENT_METHODS =
    listOf(
        PaymentMethodKind.CASH,
        PaymentMethodKind.CARD,
        PaymentMethodKind.TRANSFER,
        PaymentMethodKind.WALLET,
        PaymentMethodKind.ON_ACCOUNT,
        PaymentMethodKind.OTHER,
    )

private const val INSERT_PAYMENT_METHOD_SQL =
    "INSERT INTO payment_methods (system_key, name, normalized_name, kind, fee_bp, fee_fixed, " +
        "settlement_days, price_adjustment_bp, money_account_id, instructions_text, " +
        "instructions_media_id, sort_order, archived_at, created_at, updated_at) " +
        "VALUES (?, NULL, NULL, ?, 0, 0, 0, 0, NULL, NULL, NULL, ?, NULL, ?, ?)"

/**
 * Una unidad inicial: su clave estable, qué mide y si admite decimales. Las de conteo no los
 * admiten, porque media caja casi siempre es un error al escribir.
 */
private class InitialUnit(val systemKey: String, val kind: UnitKind, val allowsFraction: Boolean)

/**
 * Las unidades iniciales, en el orden del modelo de datos: la primera, `UNIT`, es la unidad base por
 * defecto de todo producto. Ninguna trae conversión: cuántas unidades tiene una caja lo dice cada
 * producto.
 */
private val INITIAL_UNITS =
    listOf(
        InitialUnit("UNIT", UnitKind.COUNT, allowsFraction = false),
        InitialUnit("KILOGRAM", UnitKind.MASS, allowsFraction = true),
        InitialUnit("GRAM", UnitKind.MASS, allowsFraction = true),
        InitialUnit("LITER", UnitKind.VOLUME, allowsFraction = true),
        InitialUnit("MILLILITER", UnitKind.VOLUME, allowsFraction = true),
        InitialUnit("POUND", UnitKind.MASS, allowsFraction = true),
        InitialUnit("OUNCE", UnitKind.MASS, allowsFraction = true),
        InitialUnit("GALLON", UnitKind.VOLUME, allowsFraction = true),
        InitialUnit("METER", UnitKind.LENGTH, allowsFraction = true),
        InitialUnit("BOX", UnitKind.COUNT, allowsFraction = false),
        InitialUnit("PACK", UnitKind.COUNT, allowsFraction = false),
        InitialUnit("DOZEN", UnitKind.COUNT, allowsFraction = false),
    )

/** Una unidad inicial, sin nombre ni símbolo y vigente. */
private const val INSERT_UNIT_SQL =
    "INSERT INTO units (system_key, name, normalized_name, symbol, kind, allows_fraction, " +
        "archived_at, created_at, updated_at) VALUES (?, NULL, NULL, NULL, ?, ?, NULL, ?, ?)"

/**
 * La fila inicial de `app_settings`: sin nombre del negocio, con el margen objetivo inicial, avisos
 * de stock encendidos, tema del sistema con la paleta inicial, sin bloqueo pero con un minuto de
 * espera para cuando se active, una copia como mucho cada hora con datos móviles y el primer uso
 * sin terminar.
 */
private val INSERT_APP_SETTINGS_SQL =
    "INSERT INTO app_settings (id, business_name, default_target_margin_bp, " +
        "low_stock_notifications_enabled, theme_mode, color_palette, app_lock_enabled, " +
        "app_lock_timeout_seconds, metered_backup_policy, onboarding_completed_at, updated_at) " +
        "VALUES (1, NULL, " +
        "${BasisPoints.DEFAULT_TARGET_MARGIN.bp}, 1, '${ThemeMode.SYSTEM.name}', " +
        "'${ColorPalette.INDIGO_MUSTARD.name}', 0, " +
        "${AppLockTimeout.ONE_MINUTE.seconds}, '${MeteredBackupPolicy.EVERY_60_MIN.name}', NULL, ?)"

/**
 * La fila inicial de `backup_state`: ningún cambio todavía, ningún destino elegido, sin fallos y
 * sin cabeza conocida. Lo demás queda nulo hasta que el respaldo lo escriba.
 */
private const val INSERT_BACKUP_STATE_SQL =
    "INSERT INTO backup_state (id, change_seq, verified_seq, link_state, consecutive_failures, " +
        "invariant_failures, head_generation) VALUES (1, 0, 0, 'NOT_CONNECTED', 0, 0, 0)"

/**
 * La cuenta de dinero inicial: la caja, con su clave estable y sin nombre propio, que se muestra
 * con el texto traducido de la clave. Nace sin saldo y con `XXX`, el código de ISO 4217 que
 * significa sin moneda: la del negocio todavía no existe ni está confirmada, y confirmarla la
 * escribe aquí en la misma transacción, mientras la cuenta no tenga movimientos.
 */
private const val INSERT_CASH_ACCOUNT_SQL =
    "INSERT INTO money_accounts (system_key, name, normalized_name, kind, currency_code, " +
        "currency_decimals, balance, archived_at, created_at, updated_at) " +
        "VALUES ('CASH', NULL, NULL, 'CASH', 'XXX', 0, 0, NULL, ?, ?)"

/**
 * La ubicación inicial, de clave `MAIN`, sin nombre y vigente: toda fila del libro, compra y venta
 * lleva una ubicación desde el principio, y con una sola la interfaz no habla de ubicaciones. Nace
 * con `id` 1. Su clase es `OTHER`, porque no se asume que el negocio tenga un local: puede ser la
 * casa de la persona o un puesto.
 */
private val INSERT_MAIN_LOCATION_SQL =
    "INSERT INTO locations (system_key, name, normalized_name, kind, archived_at, created_at, " +
        "updated_at) VALUES ('MAIN', NULL, NULL, '${LocationKind.OTHER.name}', NULL, ?, ?)"

/**
 * La lista de precios inicial, de clave `DEFAULT`: fija, sin base ni ajuste, y sin nombre, que sale
 * traducido de la clave. Su precio es el de venta de cada producto, así que no guarda precios.
 */
private const val INSERT_DEFAULT_PRICE_LIST_SQL =
    "INSERT INTO price_lists (system_key, name, normalized_name, kind, base_price_list_id, " +
        "adjustment_bp, archived_at, created_at, updated_at) " +
        "VALUES ('DEFAULT', NULL, NULL, 'FIXED', NULL, NULL, NULL, ?, ?)"

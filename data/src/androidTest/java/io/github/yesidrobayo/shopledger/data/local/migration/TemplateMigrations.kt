package io.github.yesidrobayo.shopledger.data.local.migration

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/*
 * Los saltos de la base ficticia, escritos como se escribirá cada salto de la base real: SQL
 * literal, copiado del JSON exportado de la versión de destino, dentro de un CheckedMigration.
 */

/** De la versión 1 a la 2: solo agrega columnas, así que no toca ninguna fila existente. */
internal object TemplateMigration1To2 : CheckedMigration(startVersion = 1) {
    override suspend fun migrateSchema(connection: SQLiteConnection) {
        // El valor por defecto es el mismo del JSON de la versión 2: Room lo compara al validar.
        connection.execSQL(
            "ALTER TABLE `products` ADD COLUMN `reorder_point_milli` INTEGER NOT NULL DEFAULT 0",
        )
        connection.execSQL("ALTER TABLE `stock_movements` ADD COLUMN `note` TEXT")
    }
}

/**
 * Los pasos de la versión 2 a la 3, en orden. Reconstruyen `products` para darle a `category_id`
 * su llave foránea, sin cambiar ningún `id` ni la secuencia que los entrega.
 *
 * Las pruebas de fallas corren una parte de estos mismos pasos, para fallar en un punto preciso.
 */
internal val TEMPLATE_STEPS_2_TO_3 =
    listOf(
        "CREATE TABLE IF NOT EXISTS `categories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT " +
            "NOT NULL, `name` TEXT NOT NULL)",
        // Una guarda que lee products impediría el renombrado de abajo: SQLite revisa todo el
        // esquema al renombrar y la encontraría apuntando a una tabla que ya no existe. Se borra, y
        // la sincronización de guardas la recrea al abrir.
        "DROP TRIGGER IF EXISTS `$GUARD_LEDGER_PRODUCT`",
        "CREATE TABLE IF NOT EXISTS `products_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT " +
            "NOT NULL, `name` TEXT NOT NULL, `category_id` INTEGER, " +
            "`sale_price` INTEGER NOT NULL, `average_cost` INTEGER NOT NULL, " +
            "`on_hand_milli` INTEGER NOT NULL, " +
            "`reorder_point_milli` INTEGER NOT NULL DEFAULT 0, FOREIGN KEY(`category_id`) " +
            "REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
        // Columnas explícitas en los dos lados: un orden distinto no cambia nada de sitio.
        "INSERT INTO `products_new` (`id`, `name`, `category_id`, `sale_price`, `average_cost`, " +
            "`on_hand_milli`, `reorder_point_milli`) SELECT `id`, `name`, `category_id`, " +
            "`sale_price`, `average_cost`, `on_hand_milli`, `reorder_point_milli` FROM `products`",
        // La secuencia guarda el `id` más alto que se entregó, aunque esa fila ya no exista. La
        // tabla nueva la toma de la vieja: sin esto, un `id` borrado volvería a entregarse.
        "DELETE FROM sqlite_sequence WHERE name = 'products_new'",
        "INSERT INTO sqlite_sequence (name, seq) SELECT 'products_new', seq FROM sqlite_sequence " +
            "WHERE name = 'products'",
        DROP_OLD_PRODUCTS,
        // Se renombra la nueva, nunca la vieja: las llaves del libro siguen diciendo `products`.
        "ALTER TABLE `products_new` RENAME TO `products`",
        "CREATE INDEX IF NOT EXISTS `index_products_category_id` ON `products` (`category_id`)",
    )

/** El paso tras el cual la tabla vieja ya no existe: el peor momento para fallar. */
internal const val DROP_OLD_PRODUCTS = "DROP TABLE `products`"

/** De la versión 2 a la 3: reconstruye `products`, padre del libro. */
internal object TemplateMigration2To3 : CheckedMigration(startVersion = 2) {
    override suspend fun migrateSchema(connection: SQLiteConnection) {
        TEMPLATE_STEPS_2_TO_3.forEach { connection.execSQL(it) }
    }
}

/** Todos los saltos de la base ficticia, como `AppMigrations.all` para la real. */
internal val TEMPLATE_MIGRATIONS = listOf(TemplateMigration1To2, TemplateMigration2To3)

/** Guarda del libro que no deja editar una fila, como las de la base real. */
internal const val GUARD_LEDGER_NO_UPDATE = "guard_stock_movements_no_update"

/** Guarda del libro que lee la tabla de productos, como las que miran la fila padre. */
internal const val GUARD_LEDGER_PRODUCT = "guard_stock_movements_product"

/**
 * Las guardas de la base ficticia. Las crea la prueba, como si una apertura anterior las hubiera
 * sincronizado, y la apertura las vuelve a crear si una migración las borró.
 */
internal val TEMPLATE_GUARDS =
    listOf(
        "CREATE TRIGGER IF NOT EXISTS `$GUARD_LEDGER_NO_UPDATE` BEFORE UPDATE " +
            "ON `stock_movements` BEGIN SELECT RAISE(ABORT, 'el libro no se edita'); END",
        "CREATE TRIGGER IF NOT EXISTS `$GUARD_LEDGER_PRODUCT` BEFORE INSERT ON `stock_movements` " +
            "WHEN NOT EXISTS (SELECT 1 FROM `products` WHERE `id` = NEW.`product_id`) " +
            "BEGIN SELECT RAISE(ABORT, 'producto inexistente'); END",
    )

internal fun SQLiteConnection.syncTemplateGuards() {
    TEMPLATE_GUARDS.forEach { execSQL(it) }
}
